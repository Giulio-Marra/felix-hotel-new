package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.CameraMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.CameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementazione dell'inventario delle camere.
 *
 * <p>Ricalca gli altri CRUD del progetto — doppio controllo sul duplicato,
 * violazione di vincolo intercettata dov'e' generata col {@code saveAndFlush}
 * dentro il try — e ne aggiunge una cosa sua: la camera <b>appartiene</b> a una
 * tipologia, quindi ogni scrittura deve prima risolvere quel riferimento.
 *
 * <p><b>Una tipologia sconosciuta e' 400 e non 404</b>, per la stessa ragione
 * gia' scelta per le dotazioni: il 404 di questi endpoint significa "questa
 * camera non esiste", e riusarlo per un id dentro il corpo renderebbe
 * indistinguibili due errori che si riparano in modo diverso.
 */
@Service
@RequiredArgsConstructor
public class CameraServiceImpl implements CameraService {

    private final CameraRepository cameraRepository;

    /** Serve a risolvere il {@code tipologiaCameraId} delle richieste di scrittura. */
    private final TipologiaCameraRepository tipologiaCameraRepository;

    private final CameraMapper cameraMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Elenco paginato e filtrato, ordinato per numero.
     *
     * <p>L'ordine e' alfabetico e non numerico, quindi la 10 viene prima della 9:
     * e' la conseguenza di tenere il numero come testo (vedi {@code Camera#numero}),
     * accettata perche' i numeri di camera sono targhette e non quantita'.
     *
     * <p><b>I filtri null non sono un caso da evitare, sono il caso normale</b>:
     * "nessun filtro" e' come si apre la pagina la prima volta. Li gestisce la
     * query con un {@code is null or}, cosi' la decisione sta tutta in un posto
     * solo invece che in una catena di {@code if} qui.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size, Long tipologiaCameraId, StatoCamera stato) {
        Page<Camera> pagina = cameraRepository.cerca(
                tipologiaCameraId,
                stato == null ? null : cameraMapper.toStatoEntity(stato),
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "numero")));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Camere recuperate",
                cameraMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse dettaglio(Long id) {
        Camera camera = trovaOrElseThrow(id);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Camera recuperata",
                cameraMapper.toResponse(camera));
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(CameraRequest request) {
        if (cameraRepository.existsByNumeroIgnoreCase(request.getNumero())) {
            throw new ConflictException("Esiste gia' una camera con questo numero");
        }

        Camera camera = new Camera();
        applicaCampi(camera, request);

        Camera salvata = salvaGestendoIlDuplicato(camera);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Camera creata",
                cameraMapper.toResponse(salvata));
    }

    /**
     * Aggiornamento completo: e' una PUT, quindi i campi assenti vengono
     * riportati al loro valore di partenza e non lasciati com'erano. Per lo stato
     * questo significa che <b>ometterlo riporta la camera a LIBERA</b>: e' il
     * significato del verbo, ed e' anche il motivo per cui il lavoro quotidiano
     * passa da {@link #impostaStato} e non da qui.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long id, CameraRequest request) {
        Camera camera = trovaOrElseThrow(id);

        // Escludendo se stessa: senza IdNot, risalvare una camera senza cambiarle il
        // numero darebbe 409 contro il proprio numero.
        if (cameraRepository.existsByNumeroIgnoreCaseAndIdNot(request.getNumero(), id)) {
            throw new ConflictException("Esiste gia' un'altra camera con questo numero");
        }

        applicaCampi(camera, request);

        Camera salvata = salvaGestendoIlDuplicato(camera);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Camera aggiornata",
                cameraMapper.toResponse(salvata));
    }

    /**
     * Eliminazione. Le prenotazioni puntano alla camera con una chiave esterna
     * senza cascata, quindi cancellarne una gia' usata da' 409: portarsi via lo
     * storico per togliere una stanza dall'elenco sarebbe il rimedio peggiore del
     * problema. Una camera fuori uso si segna {@code MANUTENZIONE}.
     *
     * <p>Come per le tipologie, a dire com'e' andata e' il database: un conteggio
     * preventivo lascerebbe aperta la finestra fra il controllo e la
     * cancellazione, e l'entity {@code Prenotazione} non esiste nemmeno ancora.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long id) {
        Camera camera = trovaOrElseThrow(id);

        try {
            cameraRepository.delete(camera);
            cameraRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "Impossibile eliminare la camera: e' ancora usata da delle prenotazioni", ex);
        }

        return apiResponseMapper.toResponse(HttpStatus.OK, "Camera eliminata", null);
    }

    /**
     * Cambio di stato, l'operazione quotidiana del personale.
     *
     * <p>Non c'e' nessuna macchina a stati che vieti certi passaggi, ed e' una
     * scelta: in un albergo vero ogni transizione capita davvero — una stanza in
     * manutenzione che si scopre gia' pulita, una occupata che si libera prima.
     * Un elenco di passaggi leciti scritto oggi sarebbe una regola inventata a
     * tavolino che qualcuno dovrebbe aggirare la settimana dopo.
     *
     * <p>Rimandare lo stato che la camera ha gia' risponde 200 e non 409:
     * l'operazione e' idempotente, e chi la ripete perche' non era sicuro che la
     * prima fosse arrivata non sta sbagliando niente.
     */
    @Override
    @Transactional
    public ApiBaseResponse impostaStato(Long id, CameraStatoRequest request) {
        Camera camera = trovaOrElseThrow(id);

        camera.setStato(cameraMapper.toStatoEntity(request.getStato()));

        Camera salvata = cameraRepository.save(camera);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Stato della camera aggiornato",
                cameraMapper.toResponse(salvata));
    }

    /** Lettura per id, con il 404 gia' pronto: e' il preambolo di quattro metodi su cinque. */
    private Camera trovaOrElseThrow(Long id) {
        return cameraRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Camera non trovata"));
    }

    /**
     * Copia nell'entity i campi che il client puo' decidere, risolvendo il
     * riferimento alla tipologia.
     *
     * <p>Lo stato assente vale {@code LIBERA} e non "lascia com'era": e' una PUT,
     * e un campo omesso torna al suo valore di partenza. In creazione e' anche
     * l'unico comportamento sensato — una stanza nuova nasce libera.
     */
    private void applicaCampi(Camera camera, CameraRequest request) {
        camera.setNumero(request.getNumero());
        camera.setPiano(request.getPiano());
        camera.setTipologiaCamera(trovaTipologiaOrElseThrow(request.getTipologiaCameraId()));
        // Lo stato si legge una volta sola e si tiene: chiamare il getter due volte —
        // una per il controllo e una per l'uso — vuol dire fidarsi che dia la stessa
        // risposta a entrambe. Qui lo farebbe, ma e' una garanzia che nessuno ha
        // scritto da nessuna parte, ed e' il rilievo che SpotBugs ha alzato
        // (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
        StatoCamera statoRichiesto = request.getStato();

        camera.setStato(statoRichiesto == null
                ? com.felixhotel.backend.entity.enums.StatoCamera.LIBERA
                : cameraMapper.toStatoEntity(statoRichiesto));
    }

    /**
     * Risolve la tipologia indicata nella richiesta. Il 400 e non il 404 e' la
     * stessa scelta gia' fatta per gli id delle dotazioni: qui il 404 e'
     * riservato alla camera dell'URL.
     *
     * <p>Passa da {@code trovaSenzaCollezioni} e non da {@code findById}: alla
     * camera della tipologia serve il riferimento, e nella risposta questa
     * compare in sintesi (id e nome). Con {@code findById} ogni scrittura si
     * tirerebbe dietro anche le sue dotazioni, per via dell'{@code @EntityGraph}
     * dichiarato la' — uno spreco notato il 2026-08-25 e lasciato aperto perche'
     * con un chiamante solo il rimedio costava piu' di quanto risparmiasse. Da
     * quando la galleria delle foto fa lo stesso, i chiamanti sono due ed e' il
     * metodo di lettura a giustificarsi, non l'eccezione.
     */
    private TipologiaCamera trovaTipologiaOrElseThrow(Long tipologiaCameraId) {
        return tipologiaCameraRepository.trovaSenzaCollezioni(tipologiaCameraId)
                .orElseThrow(() -> new BadRequestException(
                        "La tipologia di camera indicata non esiste: " + tipologiaCameraId));
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico sul numero. E' la rete sotto al controllo
     * {@code existsBy}: copre la richiesta gemella arrivata nel frattempo, che
     * nessun controllo preventivo puo' vedere.
     */
    private Camera salvaGestendoIlDuplicato(Camera camera) {
        try {
            return cameraRepository.saveAndFlush(camera);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Esiste gia' una camera con questo numero", ex);
        }
    }
}
