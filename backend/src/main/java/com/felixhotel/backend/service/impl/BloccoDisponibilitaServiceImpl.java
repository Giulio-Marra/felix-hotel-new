package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.BloccoRequest;
import com.felixhotel.backend.entity.BloccoDisponibilita;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.OrigineBlocco;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.BloccoDisponibilitaMapper;
import com.felixhotel.backend.repository.BloccoDisponibilitaRepository;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.BloccoDisponibilitaService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Implementazione dei blocchi di disponibilita'.
 *
 * <p><b>E' un CRUD corto, e le uniche tre cose che valgono una lettura sono i tre
 * controlli</b>: che la camera sia della tipologia dichiarata, che le date stiano in
 * piedi, e che quella camera non sia gia' bloccata in quelle notti.
 *
 * <p><b>Quel che questo Service deliberatamente non controlla</b> e' altrettanto
 * importante: <i>non guarda se in quelle notti ci dorma qualcuno</i>. Se una camera si
 * rompe stanotte, il fatto che ci sia un ospite dentro non la ripara — il blocco si
 * crea, la disponibilita' scende e il conflitto lo risolve chi sta al banco spostando la
 * persona. Impedirlo vorrebbe dire costringere la reception a mentire al sistema per
 * poter fare il proprio lavoro, che e' il modo piu' sicuro di far smettere alla gente di
 * usarlo.
 *
 * <p><b>Il permesso si ferma al ruolo</b>, al contrario degli ospiti e delle schedine:
 * qui non passano dati di nessuno — una camera, due date e una nota — quindi
 * {@code @PreAuthorize} basta e non serve pretendere anche il tipo dell'account.
 */
@Service
@RequiredArgsConstructor
public class BloccoDisponibilitaServiceImpl implements BloccoDisponibilitaService {

    private final BloccoDisponibilitaRepository bloccoRepository;
    private final TipologiaCameraRepository tipologiaCameraRepository;

    /** Serve solo a risolvere la camera quando viene nominata, e a controllarne la tipologia. */
    private final CameraRepository cameraRepository;

    private final BloccoDisponibilitaMapper bloccoMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(Long tipologiaCameraId, Long cameraId,
                                           LocalDate da, LocalDate a, int page, int size) {
        // I due booleani dicono se il filtro sulle date si applichi. Sembrano ridondanti
        // e non lo sono: senza, il parametro nullo finirebbe in un "is null" da cui
        // Postgres non sa dedurre nessun tipo — vedi il javadoc della query, dove c'e'
        // per esteso il difetto che questa forma evita.
        Page<BloccoDisponibilita> pagina = bloccoRepository.cerca(
                tipologiaCameraId, cameraId, da != null, da, a != null, a,
                PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Blocchi recuperati",
                bloccoMapper.toResponseList(pagina.getContent()), pagina);
    }

    /**
     * Crea un blocco.
     *
     * <p>L'ordine dei controlli non e' casuale, come per gli ospiti: prima le
     * <b>date</b>, perche' un periodo che non sta in piedi rende inutile ogni altro
     * messaggio; poi la <b>tipologia</b>, che e' il 404; poi la <b>camera</b>, che
     * dipende dalla tipologia per poter dire se le appartenga; e per ultimo la
     * <b>sovrapposizione</b>, che e' l'unica cosa che richieda di guardare le altre righe.
     */
    @Override
    @Transactional
    public ApiBaseResponse crea(BloccoRequest request) {
        verificaPeriodo(request.getDataInizio(), request.getDataFine());

        TipologiaCamera tipologia = tipologiaCameraRepository.findById(request.getTipologiaCameraId())
                .orElseThrow(() -> new NotFoundException("Tipologia di camera non trovata"));

        Camera camera = risolviCamera(request, tipologia);

        if (camera != null && bloccoRepository.esisteSovrapposizioneSuCamera(
                camera.getId(), request.getDataInizio(), request.getDataFine(), null)) {
            throw new ConflictException("La camera " + camera.getNumero()
                    + " ha gia' un blocco che si sovrappone a queste date");
        }

        BloccoDisponibilita blocco = new BloccoDisponibilita();
        blocco.setTipologiaCamera(tipologia);
        blocco.setCamera(camera);
        blocco.setDataInizio(request.getDataInizio());
        blocco.setDataFine(request.getDataFine());
        // MANUALE per definizione: questa rotta la usa una persona. Lasciarlo scegliere a
        // chi chiama vorrebbe dire permettergli di scrivere un blocco che la sincronia
        // con un canale si sentira' in diritto di cancellare.
        blocco.setOrigine(OrigineBlocco.MANUALE);
        blocco.setNote(request.getNote());

        BloccoDisponibilita salvato = salvaGestendoLaSovrapposizione(blocco);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Blocco creato",
                bloccoMapper.toResponse(salvato));
    }

    /**
     * Toglie un blocco.
     *
     * <p>Nessun {@code flush} dentro un try, come per le foto e per gli ospiti: verso
     * questa tabella non punta nessuna chiave esterna, quindi non c'e' nessuna violazione
     * da tradurre in 409.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long bloccoId) {
        BloccoDisponibilita blocco = bloccoRepository.findById(bloccoId)
                .orElseThrow(() -> new NotFoundException("Blocco non trovato"));

        bloccoRepository.delete(blocco);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Blocco rimosso", null);
    }

    /**
     * Che il periodo abbia almeno una notte.
     *
     * <p>Lo vieta anche il {@code CHECK} del V15, e il controllo sta qui lo stesso per la
     * ragione di sempre: il database direbbe la stessa cosa con un 500 tradotto a fatica,
     * questo dice 400 con una frase. La rete resta sotto.
     */
    private void verificaPeriodo(LocalDate dataInizio, LocalDate dataFine) {
        if (!dataFine.isAfter(dataInizio)) {
            throw new BadRequestException(
                    "La data di fine deve essere successiva a quella di inizio:"
                            + " un blocco di zero notti non rende invendibile niente");
        }
    }

    /**
     * La camera nominata, se c'e', e solo se e' di quella tipologia.
     *
     * <p><b>404 se la camera non esiste, 400 se esiste ma e' di un'altra tipologia</b>, e
     * la differenza e' voluta: nel primo caso chi chiama ha indicato qualcosa che non c'e',
     * nel secondo ha indicato due cose che esistono e non stanno insieme. Il secondo e' il
     * caso che il database non sa vedere — un {@code CHECK} non legge un'altra tabella —
     * quindi se non lo prendesse questo metodo passerebbe, e quel blocco toglierebbe una
     * unita' alla tipologia sbagliata.
     */
    private Camera risolviCamera(BloccoRequest request, TipologiaCamera tipologia) {
        if (request.getCameraId() == null) {
            return null;
        }

        Camera camera = cameraRepository.findById(request.getCameraId())
                .orElseThrow(() -> new NotFoundException("Camera non trovata"));

        if (!camera.getTipologiaCamera().getId().equals(tipologia.getId())) {
            throw new BadRequestException("La camera " + camera.getNumero()
                    + " non e' della tipologia indicata");
        }
        return camera;
    }

    /**
     * Scrive subito per poter tradurre in 409 la violazione del vincolo di esclusione del
     * V15. E' la rete sotto al controllo preventivo: copre la richiesta gemella arrivata
     * nel frattempo, che nessun {@code exists} puo' vedere.
     *
     * <p>Stessa forma gia' usata dai periodi tariffari e dalle aliquote, che hanno lo
     * stesso tipo di vincolo.
     */
    private BloccoDisponibilita salvaGestendoLaSovrapposizione(BloccoDisponibilita blocco) {
        try {
            return bloccoRepository.saveAndFlush(blocco);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "Quella camera ha gia' un blocco che si sovrappone a queste date", ex);
        }
    }
}
