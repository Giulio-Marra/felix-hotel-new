package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.SincronizzazioneResponse;
import com.felixhotel.backend.dto.SorgenteCalendarioRequest;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.SorgenteCalendario;
import com.felixhotel.backend.entity.enums.EsitoSincronizzazione;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.SorgenteCalendarioMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.SorgenteCalendarioRepository;
import com.felixhotel.backend.service.SorgenteCalendarioService;
import com.felixhotel.backend.service.impl.SincronizzatoreSorgente.EsitoSorgente;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * I calendari esterni: registrarli, toglierli, e farli rileggere.
 *
 * <p><b>Qui non c'e' il giro vero</b>, che sta in {@link SincronizzatoreSorgente}: questa
 * classe decide <i>quali</i> sorgenti leggere e cosa fare quando una va male, quella
 * legge <i>una</i> sorgente e riscrive i suoi blocchi. La divisione non e' di comodo — e'
 * cio' che permette a ogni sorgente di avere le proprie transazioni, e all'annotazione
 * dell'esito di sopravvivere al fallimento della scrittura.
 *
 * <p><b>Una sorgente non puo' far fallire le altre</b>, ed e' l'unica regola che questa
 * classe ha davvero. Un canale irraggiungibile e' la cosa piu' normale che possa capitare
 * a un giro periodico — manutenzione, rete, un indirizzo cambiato — e se interrompesse il
 * giro, un solo canale rotto lascerebbe tutti gli altri fermi senza che nessuno lo sappia.
 * Diventa quindi un esito {@code ERRORE} sulla sua riga, e il giro prosegue.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SorgenteCalendarioServiceImpl implements SorgenteCalendarioService {

    /** Gli unici schemi che ha senso scaricare. Vedi {@link #verificaIndirizzo}. */
    private static final List<String> SCHEMI_AMMESSI = List.of("http", "https");

    private final SorgenteCalendarioRepository sorgenteRepository;
    private final CameraRepository cameraRepository;
    private final SincronizzatoreSorgente sincronizzatore;
    private final SorgenteCalendarioMapper sorgenteMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(Long cameraId, int page, int size) {
        Page<SorgenteCalendario> pagina =
                sorgenteRepository.cerca(cameraId, PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Sorgenti recuperate",
                sorgenteMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(SorgenteCalendarioRequest request) {
        verificaIndirizzo(request.getUrl());

        Camera camera = cameraRepository.findById(request.getCameraId())
                .orElseThrow(() -> new NotFoundException("Camera non trovata"));

        if (sorgenteRepository.existsByCameraIdAndUrl(camera.getId(), request.getUrl())) {
            throw new ConflictException("Quell'indirizzo e' gia' registrato sulla camera "
                    + camera.getNumero());
        }

        SorgenteCalendario sorgente = new SorgenteCalendario();
        sorgente.setCamera(camera);
        sorgente.setNome(request.getNome());
        sorgente.setUrl(request.getUrl());

        SorgenteCalendario salvata = salvaGestendoIlDoppione(sorgente);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Sorgente registrata",
                sorgenteMapper.toResponse(salvata));
    }

    /**
     * Toglie la sorgente.
     *
     * <p><b>Nessuna cancellazione esplicita dei blocchi</b>: la chiave esterna del V17 ha
     * {@code ON DELETE CASCADE}, quindi se ne vanno con lei. E' la risposta giusta e non
     * una scorciatoia — smettere di leggere un canale vuol dire che quel canale non ci
     * dice piu' niente, non che l'ultima cosa detta resta vera per sempre.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long sorgenteId) {
        SorgenteCalendario sorgente = sorgenteRepository.findById(sorgenteId)
                .orElseThrow(() -> new NotFoundException("Sorgente non trovata"));

        sorgenteRepository.delete(sorgente);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Sorgente rimossa", null);
    }

    /**
     * <b>Non transazionale</b>, e va detto perche' e' l'opposto di ogni altro metodo di
     * questo Service. Qui non si scrive niente — a scrivere e' il sincronizzatore, una
     * sorgente per volta e con una transazione per volta. Aprirne una qui le
     * inghiottirebbe tutte in una sola, e allora un canale che fallisce a meta' del giro
     * porterebbe via anche i blocchi gia' riscritti per gli altri.
     */
    @Override
    public ApiBaseResponse sincronizzaTutte() {
        return apiResponseMapper.toResponse(HttpStatus.OK, "Sincronizzazione eseguita",
                giro().toResponse());
    }

    /**
     * Il giro periodico.
     *
     * <p><b>Un quarto d'ora</b>, che e' l'intervallo con cui i canali stessi si aspettano
     * di essere letti — piu' spesso e' inutile perche' anche loro rigenerano il file a
     * intervalli, piu' di rado allarga la finestra in cui una camera venduta risulta ancora
     * libera da noi.
     *
     * <p><b>E' una proprieta' con un default, e non per la regola 24</b>: quel valore non e'
     * qualcosa che due alberghi vorrebbero diverso, e infatti nessuno lo configurera' mai in
     * produzione. Esiste per i <b>test</b>: il contesto Spring e' condiviso da tutta la suite
     * di integrazione, e un giro che partisse da solo a meta' di un test riscriverebbe i
     * blocchi che quel test sta verificando. Nel profilo {@code test} l'intervallo e' quindi
     * lungo abbastanza da non scattare mai, e il giro lo fanno partire i test quando serve.
     *
     * <p><b>{@code fixedDelay} e non {@code fixedRate}</b>: il ritardo si conta dalla fine
     * del giro precedente, quindi due giri non si accavallano mai. Con {@code fixedRate},
     * un canale lento farebbe partire il successivo mentre il primo sta ancora scrivendo.
     *
     * <p>Perche' scatti davvero serve {@code @EnableScheduling}, in
     * {@code SchedulingConfig}: senza, questo metodo non verrebbe mai eseguito e non lo si
     * scoprirebbe fino alla prima camera rivenduta.
     */
    @Scheduled(fixedDelayString = "${felix.canale.intervallo-sincronizzazione:PT15M}")
    public void sincronizzaPeriodicamente() {
        Riepilogo riepilogo = giro();

        // A INFO e non a DEBUG anche quando va tutto bene: e' l'unica traccia che questo
        // giro esista, e un'operazione di sfondo che non lascia traccia e' un'operazione
        // di cui nessuno si accorge quando smette di partire.
        log.info("Sincronizzazione calendari: {} sorgenti, {} blocchi, {} con conflitti, {} in errore",
                riepilogo.sorgenti(), riepilogo.blocchiScritti(),
                riepilogo.conConflitti(), riepilogo.inErrore());
    }

    /**
     * Legge tutte le sorgenti, una per una, e non si ferma davanti a nessun errore.
     *
     * <p><b>Il {@code catch} e' su {@code RuntimeException} e non su una lista di
     * eccezioni</b>, che e' l'unica volta in cui questo progetto lo fa. La ragione e' la
     * regola: <i>nessuna sorgente puo' far fallire le altre</i>, e un elenco di eccezioni
     * previste la manterrebbe solo per quelle previste — bastera' una libreria che ne
     * sollevi una nuova per fermare di nuovo tutto il giro. Qui l'eccezione non e' un caso
     * da distinguere, e' un esito da annotare.
     */
    private Riepilogo giro() {
        List<Long> sorgenti = sorgenteRepository.tuttiGliId();
        int blocchi = 0;
        int conConflitti = 0;
        int inErrore = 0;

        for (Long sorgenteId : sorgenti) {
            try {
                EsitoSorgente esito = sincronizzatore.sincronizza(sorgenteId);
                blocchi += esito.blocchiScritti();

                if (esito.esito() == EsitoSincronizzazione.CONFLITTI) {
                    conConflitti++;
                }
                sincronizzatore.registraEsito(sorgenteId, esito.esito(), esito.messaggio());

            } catch (RuntimeException ex) {
                inErrore++;
                log.warn("Sorgente {} non sincronizzata: {}", sorgenteId, ex.getMessage());
                sincronizzatore.registraEsito(sorgenteId, EsitoSincronizzazione.ERRORE, ex.getMessage());
            }
        }

        return new Riepilogo(sorgenti.size(), blocchi, conConflitti, inErrore);
    }

    /**
     * Che l'indirizzo sia scaricabile, e che sia http o https.
     *
     * <p><b>Non e' una formalita' sul formato</b>: quel valore diventa una richiesta che
     * parte dal nostro server, e gli altri schemi — {@code file:}, {@code jar:} — servirebbero
     * solo a fargli leggere qualcosa che non e' il calendario di un canale.
     */
    private void verificaIndirizzo(String url) {
        String schema;
        try {
            schema = new URI(url).getScheme();
        } catch (URISyntaxException ex) {
            throw new BadRequestException("L'indirizzo non e' scritto in modo valido", ex);
        }

        if (schema == null || !SCHEMI_AMMESSI.contains(schema.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("L'indirizzo del calendario deve essere http o https");
        }
    }

    /**
     * Scrive subito per poter tradurre in 409 la violazione dell'indice unico del V17. E'
     * la rete sotto al controllo preventivo: copre la richiesta gemella arrivata nel
     * frattempo, che nessun {@code exists} puo' vedere. Stessa forma dei blocchi e delle
     * aliquote.
     */
    private SorgenteCalendario salvaGestendoIlDoppione(SorgenteCalendario sorgente) {
        try {
            return sorgenteRepository.saveAndFlush(sorgente);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Quell'indirizzo e' gia' registrato su quella camera", ex);
        }
    }

    /**
     * Com'e' andato un giro completo. <b>Conta le sorgenti e non le occupazioni</b>: il
     * dettaglio di ognuna sta sulla sua riga.
     */
    private record Riepilogo(int sorgenti, int blocchiScritti, int conConflitti, int inErrore) {

        SincronizzazioneResponse toResponse() {
            return new SincronizzazioneResponse()
                    .sorgenti(sorgenti)
                    .blocchiScritti(blocchiScritti)
                    .conConflitti(conConflitti)
                    .inErrore(inErrore);
        }
    }
}
