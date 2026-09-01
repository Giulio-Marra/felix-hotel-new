package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneCheckInRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.CanalePrenotazione;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.StatoCamera;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.PrenotazioneMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.PreventivoTipologia;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.PrenotazioneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Implementazione del ciclo di vita delle prenotazioni.
 *
 * <p>E' la prima classe del progetto con una <b>macchina a stati</b> invece di
 * un CRUD, e le due cose si comportano in modo diverso su un punto: qui il
 * risultato di un'operazione dipende da com'era messa la riga prima. Confermare
 * una prenotazione gia' confermata non e' idempotente come rimandare lo stato
 * che una camera ha gia' — la' non cambiava niente, qui la seconda chiamata
 * chiederebbe un posto che la prima ha gia' preso.
 *
 * <p><b>Due controlli di disponibilita', uno per scopo.</b> Alla creazione e'
 * una cortesia: evita di far riempire il carrello con qualcosa gia' esaurito.
 * Alla conferma e' il controllo vero, ed e' quello che sostituisce la scadenza a
 * tempo sul carrello (valutata e scartata a suo tempo): finche' nessuno
 * conferma, nessuno ha preso niente, quindi non serve nessun lavoro in
 * background che ripulisca i carrelli abbandonati.
 *
 * <p><b>L'account di chi chiama si risolve per id, dicendo prima di quale
 * tabella.</b> Il principal porta un {@code userId} affiancato da un
 * {@link TipoAccount} che dice su quale delle due popolazioni vale: da soli
 * nessuno dei due identifica una persona, insieme si'. Ne discende che qui si
 * legge il database solo dove serve l'<b>entita'</b> — cioe' per intestare una
 * prenotazione e per registrarne il gestore; dove basta confrontare un id non
 * c'e' niente da leggere.
 */
@Service
public class PrenotazioneServiceImpl implements PrenotazioneService {

    /**
     * Il massimo che entra in {@code importo_totale}, che e' NUMERIC(10,2).
     *
     * <p>Serve perche' il totale non lo scrive il client ma lo calcoliamo noi:
     * su tutti gli altri campi il tetto sta nello spec e lo fa rispettare la
     * validazione, qui il valore nasce da una somma sulle notti e nessuno lo ha
     * validato prima di noi.
     */
    private static final BigDecimal IMPORTO_MASSIMO = new BigDecimal("99999999.99");

    /**
     * Ordine dell'elenco: prima gli arrivi piu' recenti, e a parita' di giorno le
     * prenotazioni aperte piu' di recente.
     *
     * <p><b>Il secondo criterio non e' un abbellimento, tiene in piedi la
     * paginazione.</b> Le camere si ordinano per numero, che e' unico, quindi li'
     * un criterio solo basta; qui la data di arrivo e' tutto il contrario di
     * unica — in un albergo pieno decine di prenotazioni cominciano lo stesso
     * giorno, ed e' il caso normale, non il caso limite. Con la sola data il
     * database e' libero di restituire le righe pari merito in ordine diverso a
     * ogni query, e la stessa prenotazione puo' comparire in due pagine o in
     * nessuna. L'id spareggia sempre, perche' unico per costruzione.
     */
    private static final Sort ORDINE = Sort.by(Sort.Order.desc("dataCheckIn"), Sort.Order.desc("id"));

    private final PrenotazioneRepository prenotazioneRepository;
    private final TipologiaCameraRepository tipologiaCameraRepository;

    /** Serve alla meta' del calcolo della disponibilita' che conta le camere esistenti. */
    private final CameraRepository cameraRepository;

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;

    /**
     * Serve a una domanda sola, ed e' il check-in a farla: quante persone sono
     * gia' state registrate col loro documento.
     */
    private final OspiteRepository ospiteRepository;

    /**
     * Quanto costa un soggiorno e quante notti pretende come minimo. E' l'unico
     * posto del progetto in cui il prezzo si calcola, e ci si passa anche da qui:
     * dal 2026-09-01 {@code importoTotale} non e' piu' una moltiplicazione ma la
     * somma delle notti, e la formula sta scritta una volta sola perche' la
     * ricerca di disponibilita' mostri lo stesso numero che questa creazione
     * fotografa.
     */
    private final PeriodoTariffarioRepository periodoTariffarioRepository;

    private final PrenotazioneMapper prenotazioneMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Chi sta chiamando e se e' del personale: due domande che decidono cosa
     * questo Service fa, e che dal 2026-08-28 non si risponde piu' da se'.
     */
    private final ChiamanteCorrente chiamanteCorrente;

    /**
     * Da cosa dipende "oggi", che qui e' una regola di dominio e non un
     * dettaglio: una prenotazione non puo' cominciare nel passato.
     */
    private final Clock clock;

    /**
     * Costruttore usato da Spring. L'annotazione e' necessaria perche' i
     * costruttori pubblici sono due e senza indicazione il contesto non saprebbe
     * quale scegliere — stessa forma gia' usata da {@code LoginAttemptServiceImpl}.
     */
    @Autowired
    public PrenotazioneServiceImpl(PrenotazioneRepository prenotazioneRepository,
                                   TipologiaCameraRepository tipologiaCameraRepository,
                                   CameraRepository cameraRepository,
                                   UtenteRepository utenteRepository,
                                   StaffRepository staffRepository,
                                   OspiteRepository ospiteRepository,
                                   PeriodoTariffarioRepository periodoTariffarioRepository,
                                   PrenotazioneMapper prenotazioneMapper,
                                   ApiResponseMapper apiResponseMapper,
                                   ChiamanteCorrente chiamanteCorrente) {
        // Il fuso e' quello di sistema e non UTC, al contrario dei contatori del
        // ritardo progressivo: quelli misurano durate, a cui il fuso non serve, mentre
        // "oggi" per un albergo e' il giorno che si legge sul calendario alla
        // reception. Con UTC, alle due di notte in Italia sarebbe ancora ieri.
        this(prenotazioneRepository, tipologiaCameraRepository, cameraRepository, utenteRepository,
                staffRepository, ospiteRepository, periodoTariffarioRepository, prenotazioneMapper,
                apiResponseMapper, chiamanteCorrente, Clock.systemDefaultZone());
    }

    /**
     * Costruttore per i test, che passando un {@link Clock} pilotabile possono
     * decidere che giorno e' senza dipendere dalla data di esecuzione — vedi
     * {@code OrologioPilotato}. Un test che dicesse "fra tre giorni" calcolandolo
     * dall'orologio vero verificherebbe la stessa aritmetica che sta verificando.
     */
    public PrenotazioneServiceImpl(PrenotazioneRepository prenotazioneRepository,
                                   TipologiaCameraRepository tipologiaCameraRepository,
                                   CameraRepository cameraRepository,
                                   UtenteRepository utenteRepository,
                                   StaffRepository staffRepository,
                                   OspiteRepository ospiteRepository,
                                   PeriodoTariffarioRepository periodoTariffarioRepository,
                                   PrenotazioneMapper prenotazioneMapper,
                                   ApiResponseMapper apiResponseMapper,
                                   ChiamanteCorrente chiamanteCorrente,
                                   Clock clock) {
        this.prenotazioneRepository = prenotazioneRepository;
        this.tipologiaCameraRepository = tipologiaCameraRepository;
        this.cameraRepository = cameraRepository;
        this.utenteRepository = utenteRepository;
        this.staffRepository = staffRepository;
        this.ospiteRepository = ospiteRepository;
        this.periodoTariffarioRepository = periodoTariffarioRepository;
        this.prenotazioneMapper = prenotazioneMapper;
        this.apiResponseMapper = apiResponseMapper;
        this.chiamanteCorrente = chiamanteCorrente;
        this.clock = clock;
    }

    /**
     * Elenco paginato, dalla data di arrivo piu' recente.
     *
     * <p>L'ordine e' decrescente al contrario di quello delle camere, e non e'
     * un capriccio: un elenco di prenotazioni si guarda per sapere cosa sta per
     * succedere o cos'e' appena successo, non per scorrere l'archivio dal
     * principio.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size,
                                           com.felixhotel.backend.dto.StatoPrenotazione stato) {
        AppUserPrincipal chiamante = chiamanteCorrente.autenticato();

        // Null vuol dire "tutte" ed e' un privilegio, non l'assenza di un filtro: lo
        // ottiene solo chi e' del personale. Per un cliente l'id e' sempre il proprio,
        // e non perche' l'abbia chiesto.
        Long utenteId = chiamanteCorrente.personale(chiamante) ? null : idClienteChiamante(chiamante);

        Page<Prenotazione> pagina = prenotazioneRepository.cerca(
                utenteId,
                stato == null ? null : prenotazioneMapper.toStatoEntity(stato),
                PageRequest.of(page, size, ORDINE));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Prenotazioni recuperate",
                prenotazioneMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse dettaglio(Long id) {
        Prenotazione prenotazione = trovaVisibileOrElseThrow(id);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Prenotazione recuperata",
                prenotazioneMapper.toResponse(prenotazione));
    }

    /**
     * Apertura del carrello.
     *
     * <p><b>L'ordine dei controlli va dal piu' economico al piu' caro.</b> Le
     * date si verificano in memoria e non costano niente, quindi vengono per
     * prime: risolvere prima il cliente e il personale vorrebbe dire pagare due
     * o tre letture a database per una richiesta che si sapeva gia' sbagliata.
     * Poi la tipologia, che serve comunque a tutto il resto; poi la capienza,
     * che e' un confronto fra due numeri gia' in mano. La disponibilita' resta
     * per ultima perche' e' l'unica che costa <b>due</b> query.
     *
     * <p><b>Il costo non e' pero' l'unico criterio</b>, e conviene dirlo invece
     * di lasciar credere che l'ordine sia una classifica di prezzi: {@code
     * canale()} non tocca il database, eppure sta in fondo insieme
     * all'intestatario e al gestore. Quei tre rispondono alla stessa domanda —
     * di chi e' questa prenotazione e da dove arriva — e tenerli come un blocco
     * solo vale piu' della lettura che si risparmierebbe spezzandoli.
     */
    @Override
    @Transactional
    public ApiBaseResponse crea(PrenotazioneRequest request) {
        AppUserPrincipal chiamante = chiamanteCorrente.autenticato();
        boolean daPersonale = chiamanteCorrente.personale(chiamante);

        verificaDate(request.getDataCheckIn(), request.getDataCheckOut());

        TipologiaCamera tipologia = trovaTipologiaOrElseThrow(request.getTipologiaCameraId());
        verificaCapienza(request.getNumeroOspiti(), tipologia);

        // Chi c'e' dietro la prenotazione: l'intestatario, da dove arriva, e — se non
        // l'ha fatta il cliente — chi l'ha registrata. Si risolvono prima della
        // disponibilita' perche' i loro errori riguardano la richiesta, e una richiesta
        // sbagliata va detta sbagliata anche quando per giunta non c'e' posto.
        Utente utenteIntestatario = intestatario(request, chiamante, daPersonale);
        CanalePrenotazione canaleScelto = canale(request, daPersonale);
        Staff gestore = daPersonale ? staffChiamante(chiamante) : null;

        // Il preventivo prima della disponibilita': dice quanto costa e quante notti
        // quel periodo pretende, e un soggiorno troppo corto va rifiutato per quello che
        // e' — una richiesta fuori dalle regole di vendita — anche quando per giunta non
        // c'e' posto. Stesso criterio con cui l'intestatario e il canale si risolvono
        // prima: gli errori della richiesta vengono prima di quelli del mondo.
        PreventivoTipologia preventivo = periodoTariffarioRepository.preventivoDi(
                tipologia.getId(), request.getDataCheckIn(), request.getDataCheckOut());
        verificaSoggiornoMinimo(preventivo, request.getDataCheckIn(), request.getDataCheckOut());

        verificaDisponibilita(tipologia, request.getDataCheckIn(), request.getDataCheckOut(), null);

        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setUtente(utenteIntestatario);
        prenotazione.setCanale(canaleScelto);
        prenotazione.setGestitaDaStaff(gestore);
        prenotazione.setTipologiaCamera(tipologia);
        prenotazione.setDataCheckIn(request.getDataCheckIn());
        prenotazione.setDataCheckOut(request.getDataCheckOut());
        prenotazione.setNumeroOspiti(request.getNumeroOspiti());
        prenotazione.setNote(request.getNote());
        // Lo stato non si imposta: una Prenotazione nasce IN_ATTESA per
        // inizializzatore di campo, e ripeterlo qui lascerebbe due posti da
        // cambiare il giorno che il carrello non fosse piu' il punto di partenza.
        prenotazione.setImportoTotale(importoFotografato(preventivo));

        Prenotazione salvata = prenotazioneRepository.save(prenotazione);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Prenotazione creata",
                prenotazioneMapper.toResponse(salvata));
    }

    /**
     * Conferma: e' qui che la camera viene riservata davvero.
     *
     * <p><b>Perche' si ricontrolla la disponibilita' anche se era gia' stata
     * verificata.</b> Fra la creazione e adesso puo' essere passato un mese, e
     * chi ha messo nel carrello per primo non ha nessun diritto di precedenza su
     * chi conferma per primo: e' esattamente la situazione che una scadenza sul
     * carrello avrebbe provato a governare, risolta guardando i fatti nel momento
     * in cui contano.
     *
     * <p>Il conteggio <b>esclude questa prenotazione</b>: e' IN_ATTESA e quindi
     * non occupa, ma escluderla comunque rende il metodo indifferente allo stato
     * di partenza — e chi un domani aggiungera' altre transizioni non dovra'
     * accorgersi di questo dettaglio per non contare la riga contro se stessa.
     *
     * <p>Se il posto non c'e' piu', <b>la prenotazione non viene toccata</b>:
     * resta IN_ATTESA, e cambiarle le date e' un'operazione che il cliente puo'
     * ancora fare da solo.
     */
    @Override
    @Transactional
    public ApiBaseResponse conferma(Long id) {
        Prenotazione prenotazione = trovaVisibileOrElseThrow(id);

        if (prenotazione.getStato() != StatoPrenotazione.IN_ATTESA) {
            throw new ConflictException(
                    "Si puo' confermare solo una prenotazione in attesa: questa e' "
                            + prenotazione.getStato().name());
        }

        // Un carrello non scade, quindi puo' restare li' finche' il giorno d'arrivo non
        // e' passato: confermarlo allora vorrebbe dire creare una prenotazione per un
        // soggiorno gia' cominciato — e addebitarne il totale. La creazione rifiuta un
        // arrivo passato, ma la creazione e' avvenuta quando quell'arrivo era futuro:
        // e' l'unico controllo delle date che va rifatto adesso e non basta averlo
        // fatto allora. 409 e non 400 perche' la richiesta e' ben formata: e' passato
        // il tempo.
        if (prenotazione.getDataCheckIn().isBefore(LocalDate.now(clock))) {
            throw new ConflictException(
                    "Il giorno di arrivo e' gia' passato: questa prenotazione non e' piu' confermabile");
        }

        verificaDisponibilita(prenotazione.getTipologiaCamera(), prenotazione.getDataCheckIn(),
                prenotazione.getDataCheckOut(), prenotazione.getId());

        prenotazione.setStato(StatoPrenotazione.CONFERMATA);

        Prenotazione salvata = prenotazioneRepository.save(prenotazione);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Prenotazione confermata",
                prenotazioneMapper.toResponse(salvata));
    }

    /**
     * Check-in: l'ospite e' arrivato, e da adesso ha una stanza.
     *
     * <p><b>E' l'unico punto dell'applicazione che cambia da solo lo stato
     * operativo di una camera.</b> Fin qui quel campo lo muoveva soltanto una
     * persona, con {@code PUT /api/camere/{id}/stato}; qui lo muove un fatto —
     * qualcuno e' entrato in quella stanza — ed e' la ragione per cui vale la
     * pena che lo faccia il codice invece di ricordarselo chi sta al banco.
     *
     * <p><b>Le date si controllano di nuovo, e non e' una ripetizione.</b> La
     * creazione rifiuta un arrivo nel passato e la conferma lo rifiuta un'altra
     * volta perche' nel frattempo il tempo passa; qui la domanda e' rovesciata:
     * non "e' troppo tardi" ma <b>"e' gia' ora"</b>. Registrare oggi l'arrivo di
     * un soggiorno di novembre metterebbe OCCUPATA una stanza per due mesi, e
     * quella stanza sparirebbe dall'assegnazione di tutti gli arrivi veri.
     * Dall'altro capo, il giorno di partenza e' gia' fuori: chi parte il 13 non
     * dorme la notte del 13, quindi il 13 non si entra.
     */
    @Override
    @Transactional
    public ApiBaseResponse checkIn(Long id, PrenotazioneCheckInRequest request) {
        Prenotazione prenotazione = trovaVisibileOrElseThrow(id);

        if (prenotazione.getStato() != StatoPrenotazione.CONFERMATA) {
            throw new ConflictException(
                    "Si puo' registrare l'arrivo solo su una prenotazione confermata: questa e' "
                            + prenotazione.getStato().name());
        }

        verificaSoggiornoInCorso(prenotazione);
        verificaOspitiRegistrati(prenotazione);

        // Il getter si legge una volta e si tiene, come per canale e intestatario in
        // creazione: chiamarlo due volte vuol dire fidarsi che dia la stessa risposta.
        Long idCameraIndicata = request == null ? null : request.getCameraId();

        Camera camera = idCameraIndicata == null
                ? cameraScelta(prenotazione)
                : cameraIndicata(prenotazione, idCameraIndicata);

        camera.setStato(StatoCamera.OCCUPATA);
        cameraRepository.save(camera);

        prenotazione.setCamera(camera);
        prenotazione.setStato(StatoPrenotazione.CHECK_IN);

        Prenotazione salvata = prenotazioneRepository.save(prenotazione);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Check-in registrato",
                prenotazioneMapper.toResponse(salvata));
    }

    /**
     * Check-out: l'ospite e' partito.
     *
     * <p><b>La camera resta scritta sulla prenotazione</b>, e cancellarla
     * sarebbe la cosa sbagliata da fare: in quella stanza ci ha dormito
     * qualcuno, ed e' l'unico posto in cui quel fatto e' registrato. "Chi c'era
     * nella 101 a settembre" e' una domanda che un albergo si fa davvero.
     *
     * <p><b>Lo stato torna a LIBERA solo se era OCCUPATA</b>, ed e' la parte che
     * vale la pena leggere due volte. Se durante il soggiorno qualcuno ha
     * segnato la stanza in MANUTENZIONE — un guasto scoperto dall'ospite —
     * riportarla a LIBERA vorrebbe dire che la partenza di una persona rimette
     * in servizio una stanza rotta, cioe' che un'operazione di reception
     * cancella in silenzio una segnalazione tecnica. Meglio lasciare che se ne
     * occupi chi l'ha segnalata.
     */
    @Override
    @Transactional
    public ApiBaseResponse checkOut(Long id) {
        Prenotazione prenotazione = trovaVisibileOrElseThrow(id);

        if (prenotazione.getStato() != StatoPrenotazione.CHECK_IN) {
            throw new ConflictException(
                    "Si puo' registrare la partenza solo su una prenotazione in corso: questa e' "
                            + prenotazione.getStato().name());
        }

        Camera camera = prenotazione.getCamera();

        // Il null non e' raggiungibile dagli endpoint — l'unico modo di arrivare in
        // CHECK_IN e' il metodo qui sopra, che la camera la assegna sempre — ma una
        // riga scritta a mano nel database ci arriverebbe, e la differenza fra 500 e
        // "il check-out funziona lo stesso" e' abbastanza grande da non affidarla a
        // un'invariante che questo metodo non puo' verificare.
        if (camera != null && camera.getStato() == StatoCamera.OCCUPATA) {
            camera.setStato(StatoCamera.LIBERA);
            cameraRepository.save(camera);
        }

        prenotazione.setStato(StatoPrenotazione.CHECK_OUT);

        Prenotazione salvata = prenotazioneRepository.save(prenotazione);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Check-out registrato",
                prenotazioneMapper.toResponse(salvata));
    }

    /**
     * Che il soggiorno sia cominciato e non ancora finito, cioe' che oggi sia una
     * delle notti prenotate.
     *
     * <p>409 e non 400 su entrambi i lati: la richiesta e' ben formata, e' il
     * calendario che non e' d'accordo. E' la stessa scelta gia' fatta dalla
     * conferma per l'arrivo ormai passato.
     */
    private void verificaSoggiornoInCorso(Prenotazione prenotazione) {
        LocalDate oggi = LocalDate.now(clock);

        if (oggi.isBefore(prenotazione.getDataCheckIn())) {
            throw new ConflictException("Il soggiorno comincia il " + prenotazione.getDataCheckIn()
                    + ": non si registra un arrivo prima del giorno di arrivo");
        }

        if (!oggi.isBefore(prenotazione.getDataCheckOut())) {
            throw new ConflictException("Il soggiorno e' finito il " + prenotazione.getDataCheckOut()
                    + ": non si registra un arrivo su un soggiorno concluso");
        }
    }

    /**
     * Che il documento di <b>ogni</b> persona che dormira' qui sia gia' stato
     * registrato.
     *
     * <p><b>E' un obbligo di legge e non una comodita' gestionale.</b> Il TULPS
     * — l'articolo 109, quello della "schedina alloggiati" — pretende che il
     * documento si acquisisca <i>all'atto dell'arrivo</i>: il gesto del banco e'
     * documenti sul bancone, dati registrati, chiave in mano, e questo controllo
     * e' l'ultimo dei tre. La sequenza per chi usa l'API e' quindi
     * {@code POST /api/prenotazioni/id/ospiti} per ognuno, poi questo check-in.
     *
     * <p><b>Un'uguaglianza e non un "almeno uno"</b>, ed e' il punto che vale la
     * pena capire: la legge vuole il documento di ogni persona che soggiorna,
     * non di una. Il conteggio e' vincolato dai due lati dallo stesso numero —
     * {@code OspiteServiceImpl} rifiuta l'ospite oltre {@code numeroOspiti},
     * questo metodo pretende che ci si arrivi — quindi "almeno" e "esattamente"
     * qui coincidono, e scriverlo come uguaglianza dice cosa si sta chiedendo.
     * Il {@code >} invece del {@code !=} nel primo ramo non e' pedanteria: se il
     * conto fosse in eccesso (due registrazioni simultanee sull'ultimo posto,
     * vedi {@code OspiteServiceImpl.salvaGestendoIlDuplicato}), un messaggio che
     * dice "ne mancano -1" manderebbe chi sta al banco a cercare una persona che
     * non esiste.
     *
     * <p>409 come gli altri controlli di questo metodo: la richiesta e' ben
     * formata, e' lo stato del registro a non essere pronto.
     */
    private void verificaOspitiRegistrati(Prenotazione prenotazione) {
        long registrati = ospiteRepository.countByPrenotazioneId(prenotazione.getId());
        int attesi = prenotazione.getNumeroOspiti();

        if (registrati > attesi) {
            throw new ConflictException("Gli ospiti registrati sono " + registrati
                    + " ma la prenotazione e' per " + attesi
                    + ": vanno tolti quelli di troppo prima di registrare l'arrivo");
        }

        if (registrati < attesi) {
            throw new ConflictException("Registrati " + registrati + " ospiti su " + attesi
                    + ": prima dell'arrivo va preso il documento di ogni persona che soggiorna");
        }
    }

    /**
     * La camera scelta dal service: la prima assegnabile della tipologia
     * prenotata.
     *
     * <p><b>Il 409 qui puo' capitare a prenotazione perfettamente regolare</b>, e
     * non e' una contraddizione col controllo di disponibilita' fatto alla
     * conferma: quello conta <i>tutte</i> le camere della tipologia, comprese
     * quelle che oggi sono in manutenzione, perche' uno stato di oggi non dice
     * niente su un soggiorno di novembre. Quando novembre arriva, pero', la
     * chiave va data su una stanza che esiste davvero. Le due domande sono
     * diverse e possono rispondere diversamente — vedi
     * {@code CameraRepository.trovaAssegnabili}.
     */
    private Camera cameraScelta(Prenotazione prenotazione) {
        return cameraRepository.trovaAssegnabili(
                        prenotazione.getTipologiaCamera().getId(),
                        StatoCamera.LIBERA,
                        StatoPrenotazione.CHECK_IN,
                        prenotazione.getDataCheckIn(),
                        prenotazione.getDataCheckOut(),
                        Limit.of(1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Nessuna camera di questa tipologia e' assegnabile per il periodo del soggiorno"));
    }

    /**
     * La camera che chi sta al banco ha nominato.
     *
     * <p><b>Non si controlla che sia della tipologia prenotata</b>, ed e'
     * deliberato: e' l'upgrade, cioe' il modo in cui un albergo risolve un
     * guasto o un pienone spostando un ospite in una stanza migliore. Quel che
     * <b>non</b> cambia e' l'importo — resta la fotografia presa alla creazione,
     * sulla tipologia comprata — perche' un upgrade che costasse di piu' non
     * sarebbe un upgrade. Per lo stesso motivo la tipologia della prenotazione
     * non viene riscritta: quella dice cosa il cliente ha comprato, la camera
     * dice dove ha dormito, e la risposta le mostra tutte e due.
     *
     * <p>Le due condizioni che restano non sono negoziabili nemmeno per un
     * upgrade: una stanza occupata, in pulizia o in manutenzione non si assegna,
     * e una con dentro un ospite nemmeno. <b>Non sono la stessa condizione detta
     * due volte</b>: la prima legge un campo che scrive anche una persona — e
     * {@code PUT /api/camere/{id}/stato} non ha nessuna macchina a stati che le
     * impedisca di rimettere LIBERA una stanza abitata — mentre la seconda legge
     * le prenotazioni in CHECK_IN, che scrive solo l'applicazione. La seconda si
     * verifica su <b>quella camera</b> e non filtrando l'elenco delle
     * assegnabili, che per un upgrade non la conterrebbe mai.
     *
     * <p>400 per la camera inesistente e 409 per la camera non disponibile: la
     * stessa distinzione gia' usata in creazione fra "questo id non esiste" e
     * "lo stato del mondo non lo permette".
     */
    private Camera cameraIndicata(Prenotazione prenotazione, Long cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new BadRequestException("La camera indicata non esiste: " + cameraId));

        if (camera.getStato() != StatoCamera.LIBERA) {
            throw new ConflictException("La camera " + camera.getNumero() + " non e' assegnabile: e' "
                    + camera.getStato().name());
        }

        if (prenotazioneRepository.esisteSovrapposizioneSuCamera(cameraId,
                StatoPrenotazione.CHECK_IN,
                prenotazione.getDataCheckIn(), prenotazione.getDataCheckOut())) {
            throw new ConflictException("La camera " + camera.getNumero()
                    + " e' gia' occupata da un altro ospite in quei giorni");
        }

        return camera;
    }

    /**
     * Annullamento: la prenotazione smette di valere e restituisce il posto,
     * senza sparire dallo storico.
     *
     * <p>Si esce da IN_ATTESA e da CONFERMATA. Da tutto il resto no, per due
     * ragioni diverse che il codice tratta insieme: una gia' annullata non ha
     * niente da annullare, e un soggiorno gia' cominciato non si annulla — al
     * massimo si interrompe, che e' un'altra operazione e avra' un altro nome.
     */
    @Override
    @Transactional
    public ApiBaseResponse annulla(Long id, PrenotazioneAnnullamentoRequest request) {
        Prenotazione prenotazione = trovaVisibileOrElseThrow(id);

        StatoPrenotazione stato = prenotazione.getStato();
        if (stato != StatoPrenotazione.IN_ATTESA
                && stato != StatoPrenotazione.CONFERMATA) {
            throw new ConflictException(
                    "Si puo' annullare solo una prenotazione in attesa o confermata: questa e' " + stato.name());
        }

        prenotazione.setStato(StatoPrenotazione.ANNULLATA);
        prenotazione.setDataCancellazione(LocalDateTime.now(clock));
        // Il corpo e' facoltativo, quindi qui si arriva anche con request null: chi
        // annulla senza dire perche' non sta sbagliando niente.
        prenotazione.setMotivoCancellazione(request == null ? null : request.getMotivo());

        Prenotazione salvata = prenotazioneRepository.save(prenotazione);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Prenotazione annullata",
                prenotazioneMapper.toResponse(salvata));
    }

    /**
     * Lettura per id che applica anche il permesso, perche' le due cose non sono
     * separabili: "non esiste" e "non e' tua" devono dare la stessa risposta.
     *
     * <p><b>404 e non 403</b>, ed e' deliberato: un 403 direbbe "esiste, ma non e'
     * tua", cioe' permetterebbe di scoprire quali id esistono provandoli uno per
     * uno. Il 403 di questo progetto e' per il ruolo insufficiente, che e' una
     * cosa che si puo' dire senza rivelare niente; questo non lo e'.
     */
    private Prenotazione trovaVisibileOrElseThrow(Long id) {
        Prenotazione prenotazione = prenotazioneRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Prenotazione non trovata"));

        AppUserPrincipal chiamante = chiamanteCorrente.autenticato();
        if (!chiamanteCorrente.personale(chiamante)
                && !prenotazione.getUtente().getId().equals(idClienteChiamante(chiamante))) {
            throw new NotFoundException("Prenotazione non trovata");
        }

        return prenotazione;
    }

    /**
     * Il cliente a cui intestare la prenotazione.
     *
     * <p>I due casi non sono due varianti dello stesso: chi prenota per se' non
     * puo' nemmeno nominare un altro, e chi prenota per un cliente <b>deve</b>
     * dire quale. Mandare {@code utenteId} da un USER e' un 400 e non un valore
     * ignorato: accettarlo in silenzio direbbe a chi ci ha provato che ha
     * funzionato.
     *
     * <p><b>Il cliente indicato dev'essere attivo.</b> Era un buco noto e
     * rimandato: {@code attivo} veniva ricontrollato ad ogni richiesta per chi
     * <i>chiama</i> ({@code AppUserPrincipal.isEnabled()}) e mai per chi viene
     * <i>nominato</i>, cioe' due posti che leggevano lo stesso campo traendone
     * conclusioni diverse. La regola che lo chiude e' quella scelta il
     * 2026-08-27 per gli account disattivati, ed e' la stessa che vale per il
     * personale: <b>quello che c'e' gia' resta, di nuovo non si aggiunge
     * niente</b>. Le prenotazioni passate di un cliente disattivato restano
     * intestate a lui — sono storia — ma non se ne registrano altre.
     *
     * <p>Vale solo per il ramo del personale, e non per una dimenticanza: un
     * cliente disattivato non ha nessun modo di arrivare al ramo che intesta a
     * se' stesso, perche' il suo token viene rifiutato prima, in fondo alla
     * catena dei filtri.
     *
     * <p>E' un 400 e non un 409: il conflitto direbbe che due cose vere
     * insieme non possono stare, mentre qui la richiesta nomina un account a cui
     * non si puo' intestare niente — e' un valore sbagliato nel corpo, come
     * l'utente che non esiste due righe piu' in la'.
     */
    private Utente intestatario(PrenotazioneRequest request, AppUserPrincipal chiamante, boolean daPersonale) {
        // Come per il canale qui sotto: il getter si legge una volta e si tiene, invece
        // di chiamarlo tre volte fidandosi che risponda sempre lo stesso.
        Long idIntestatario = request.getUtenteId();

        if (!daPersonale) {
            if (idIntestatario != null) {
                throw new BadRequestException("Non si puo' intestare una prenotazione a un altro account");
            }
            return clienteChiamante(chiamante);
        }

        if (idIntestatario == null) {
            throw new BadRequestException(
                    "Chi registra la prenotazione di un cliente deve indicare l'utenteId a cui intestarla");
        }

        Utente intestatario = utenteRepository.findById(idIntestatario)
                .orElseThrow(() -> new BadRequestException(
                        "Il cliente indicato non esiste: " + idIntestatario));

        if (!intestatario.isAttivo()) {
            throw new BadRequestException(
                    "Il cliente indicato ha un account disattivato: " + idIntestatario);
        }

        return intestatario;
    }

    /**
     * Da dove arriva la prenotazione. Per un cliente e' ONLINE per costruzione —
     * non e' un dato che gli si chiede — mentre il personale deve dire come l'ha
     * ricevuta, perche' e' l'unica cosa che quel campo serve a sapere.
     */
    private CanalePrenotazione canale(PrenotazioneRequest request, boolean daPersonale) {
        // Il getter si legge una volta sola e si tiene: chiamarlo due volte — una per il
        // controllo e una per l'uso — vuol dire fidarsi che dia la stessa risposta a
        // entrambe. Qui lo farebbe, ma e' una garanzia che nessuno ha scritto, ed e' il
        // rilievo che SpotBugs alza (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE). Lo stesso
        // era gia' successo in CameraServiceImpl.
        com.felixhotel.backend.dto.CanalePrenotazione canaleRichiesto = request.getCanale();

        if (!daPersonale) {
            if (canaleRichiesto != null) {
                throw new BadRequestException(
                        "Il canale lo determina chi registra la prenotazione: una prenotazione fatta"
                                + " dal cliente e' sempre ONLINE");
            }
            return CanalePrenotazione.ONLINE;
        }

        if (canaleRichiesto == null) {
            throw new BadRequestException("Chi registra la prenotazione di un cliente deve indicare il canale");
        }

        return prenotazioneMapper.toCanaleEntity(canaleRichiesto);
    }

    /**
     * L'id del cliente che sta chiamando, <b>senza toccare il database</b>: per
     * un account di tipo CLIENTE l'id del principal e' gia' la chiave di
     * {@code utente}, e finche' serve solo confrontarlo non c'e' niente da
     * leggere. E' il caso di gran lunga piu' frequente — ogni lettura di un
     * cliente passa di qui.
     *
     * <p>Il controllo sul tipo non e' una formalita': un account che sta nella
     * tabella del personale ma porta il ruolo USER arriverebbe fin qui, e usare
     * il suo id come se fosse quello di un cliente vorrebbe dire mostrargli le
     * prenotazioni di un utente che non ha niente a che fare con lui. E' 401 e
     * non 403 perche' non e' una questione di permessi: quel token vale per un
     * account che non e' quello che dice di essere.
     */
    private Long idClienteChiamante(AppUserPrincipal chiamante) {
        if (chiamante.getTipo() != TipoAccount.CLIENTE) {
            throw new UnauthorizedException("L'account autenticato non e' quello di un cliente");
        }

        return chiamante.getUserId();
    }

    /**
     * Il cliente corrispondente a chi chiama, quando serve l'entita' e non il
     * solo id — cioe' solo per intestargli una prenotazione.
     *
     * <p>Non trovarlo non e' un 404 ma un 401: vuol dire che il token e' valido
     * per un account che non c'e' piu'.
     */
    private Utente clienteChiamante(AppUserPrincipal chiamante) {
        return utenteRepository.findById(idClienteChiamante(chiamante))
                .orElseThrow(() -> new UnauthorizedException("L'account autenticato non esiste piu'"));
    }

    /**
     * Il membro del personale corrispondente a chi chiama, da registrare come
     * gestore della prenotazione.
     *
     * <p><b>Non ricontrolla il tipo dell'account</b>, e da qui in poi non ha
     * piu' motivo di farlo: ci si arriva solo dopo un
     * {@link ChiamanteCorrente#personale} vero, che dal 2026-08-27 pretende
     * gia' un account di tipo PERSONALE. Fino a quel giorno il controllo stava
     * qui e serviva davvero, perche' quello era l'unico punto in cui la
     * divergenza fra ruolo e tipo veniva vista; spostarlo a monte l'ha chiusa
     * per tutti i chiamanti invece che per questo solo — e ripeterlo adesso
     * vorrebbe dire tenere in piedi un ramo che nessuna richiesta puo'
     * percorrere, cioe' una promessa che nessun test puo' mantenere.
     *
     * <p>Non trovare la riga resta invece un caso possibile, ed e' un 401 come
     * per il cliente: il token e' valido per un account che non c'e' piu'.
     */
    private Staff staffChiamante(AppUserPrincipal chiamante) {
        return staffRepository.findById(chiamante.getUserId())
                .orElseThrow(() -> new UnauthorizedException("L'account autenticato non esiste piu'"));
    }

    /**
     * Risolve la tipologia prenotata. Il 400 e non il 404 e' la stessa scelta
     * gia' fatta per le camere e per le dotazioni: il 404 di questi endpoint
     * significa "questa prenotazione non esiste".
     *
     * <p>Passa da {@code trovaSenzaCollezioni} perche' qui della tipologia
     * servono prezzo, capienza e nome, non le sue dotazioni: con
     * {@code findById} ogni prenotazione se le tirerebbe dietro per niente.
     */
    private TipologiaCamera trovaTipologiaOrElseThrow(Long tipologiaCameraId) {
        return tipologiaCameraRepository.trovaSenzaCollezioni(tipologiaCameraId)
                .orElseThrow(() -> new BadRequestException(
                        "La tipologia di camera indicata non esiste: " + tipologiaCameraId));
    }

    /**
     * Le due regole sulle date che lo schema non sa esprimere.
     *
     * <p>La prima — partenza dopo l'arrivo — e' anche un CHECK del database, ma
     * lasciarla arrivare fin la' la trasformerebbe in un 500: il vincolo in
     * database e' la rete, non il controllo.
     *
     * <p>La seconda — non si comincia nel passato — dipende da che giorno e'
     * oggi, quindi non e' esprimibile in nessuno schema. Il confronto e' con
     * l'oggi della reception, vedi il {@link Clock} di questa classe.
     *
     * <p>La terza, dal 2026-09-01, e' <b>il tetto alla durata</b>, e sta in
     * {@link DurataSoggiorno} invece che qui perche' e' l'unica delle tre che
     * vale <b>anche per la ricerca</b>: le altre due no — chi cerca puo'
     * guardare il passato. Tenerla scritta in un posto solo e' cio' che impedisce
     * ai due endpoint di dire cose diverse sulla stessa richiesta, che e' proprio
     * il difetto per cui il tetto e' stato deciso. Va dopo il controllo
     * sull'ordine delle date: su un intervallo alla rovescia il conto delle notti
     * sarebbe negativo, cioe' passerebbe.
     */
    private void verificaDate(LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (!dataCheckOut.isAfter(dataCheckIn)) {
            throw new BadRequestException("La data di partenza deve essere successiva a quella di arrivo");
        }

        if (dataCheckIn.isBefore(LocalDate.now(clock))) {
            throw new BadRequestException("Non si puo' prenotare un arrivo gia' passato");
        }

        DurataSoggiorno.verifica(dataCheckIn, dataCheckOut);
    }

    /**
     * Gli ospiti non possono superare la capienza della tipologia scelta.
     *
     * <p>Non e' un vincolo esprimibile nello spec: il massimo dipende da quale
     * tipologia si e' scelta, e nello schema il numero di ospiti e' un intero che
     * non sa niente della camera. Il tetto dichiarato la' e' un'altra cosa — una
     * difesa grossolana contro i due miliardi.
     */
    private void verificaCapienza(Integer numeroOspiti, TipologiaCamera tipologia) {
        if (numeroOspiti > tipologia.getCapienzaMax()) {
            throw new BadRequestException("La tipologia scelta ospita al massimo "
                    + tipologia.getCapienzaMax() + " persone");
        }
    }

    /**
     * Il calcolo della disponibilita', che e' una sottrazione fra due numeri:
     * quante camere di quella tipologia esistono, meno quante ne risultano
     * impegnate <b>nella notte peggiore</b> del periodo.
     *
     * <p><b>La notte peggiore, non il totale delle prenotazioni che toccano il
     * periodo</b>: chi prenota vuole una stanza per tutte le notti che ha
     * chiesto, quindi cio' che gli toglie il posto e' il momento di massimo
     * affollamento. Tre soggiorni brevi messi in fila occupano una camera sola.
     * Il perche' la prima versione sbagliasse, e come si evita di scorrere le
     * notti a una a una, sta nel javadoc di
     * {@code PrenotazioneRepository.occupazioneMassima}.
     *
     * <p><b>Non e' un campo salvato</b>, ed e' il motivo per cui va rifatto ogni
     * volta che serve: dipende da un intervallo di date, e non esiste nessun
     * posto dove scriverlo che non diventi sbagliato al primo annullamento.
     *
     * <p>Zero camere di quella tipologia da' 409 come da' 409 il tutto esaurito:
     * per chi prenota sono la stessa risposta — non c'e' posto — e distinguerle
     * servirebbe solo a far sapere a un estraneo com'e' fatto l'albergo.
     *
     * @param esclusa prenotazione da non contare, o null in creazione, dove non
     *                esiste ancora niente da escludere
     */
    private void verificaDisponibilita(TipologiaCamera tipologia, LocalDate dataCheckIn,
                                       LocalDate dataCheckOut, Long esclusa) {
        long camereEsistenti = cameraRepository.countByTipologiaCameraId(tipologia.getId());
        long occupateNellaNottePeggiore = prenotazioneRepository.occupazioneMassimaDi(tipologia.getId(),
                dataCheckIn, dataCheckOut, StatoPrenotazione.nomiCheOccupano(), esclusa);

        if (camereEsistenti - occupateNellaNottePeggiore <= 0) {
            throw new ConflictException(
                    "Nessuna camera disponibile di questa tipologia per il periodo richiesto");
        }
    }

    /**
     * Un soggiorno piu' corto del minimo che il periodo tariffario impone non si
     * vende.
     *
     * <p><b>Conta il minimo del periodo in cui si arriva</b>, non il piu' severo
     * fra quelli che il soggiorno attraversa: e' l'arrivo che si vende, e
     * rifiutare per una regola del periodo in cui l'ospite entra a meta'
     * vorrebbe dire non vendere nemmeno la parte che era libera. La scelta e'
     * espressa nella query — vedi
     * {@code PeriodoTariffarioRepository.preventivi} — perche' e' li' che si sa
     * quale notte e' la prima.
     *
     * <p><b>400 e non 409</b>, come per la capienza: non c'e' nessuno stato con
     * cui la richiesta confligga, e' fuori da cio' che si puo' chiedere e lo
     * sarebbe stata anche ieri. Il numero minimo chi chiama lo puo' leggere in
     * anticipo da {@code GET /api/disponibilita}, che lo restituisce accanto al
     * preventivo proprio perche' non lo si scopra solo provando.
     */
    private void verificaSoggiornoMinimo(PreventivoTipologia preventivo, LocalDate dataCheckIn,
                                         LocalDate dataCheckOut) {
        long notti = ChronoUnit.DAYS.between(dataCheckIn, dataCheckOut);

        if (notti < preventivo.getSoggiornoMinimo()) {
            throw new BadRequestException("In questo periodo il soggiorno minimo e' di "
                    + preventivo.getSoggiornoMinimo() + " notti");
        }
    }

    /**
     * Il totale che finisce sulla prenotazione: quello del preventivo, se ci sta
     * in colonna.
     *
     * <p><b>La fotografia resta, e' cambiato chi la scatta.</b> Fino al
     * 2026-09-01 il totale era una moltiplicazione fatta qui; adesso e' la somma
     * delle notti calcolata dalla query delle tariffe, dove ogni notte puo'
     * costare diversamente dalle altre. Quel che non cambia e' che il numero
     * venga congelato adesso: se domani il listino cambia, questa prenotazione
     * continua a valere quel che valeva.
     *
     * <p><b>Il tetto va controllato qui e non nello spec</b> perche' questo
     * numero non lo manda il client: nasce da una somma, e un totale che non
     * entra in NUMERIC(10,2) verrebbe rifiutato da Postgres — cioe' arriverebbe
     * a chi chiama come un 500, dando la colpa a noi di un soggiorno che aveva
     * chiesto lui. E' un caso oggi molto piu' difficile da raggiungere di prima,
     * perche' un soggiorno non puo' superare le
     * {@code DurataSoggiorno.MASSIMO_NOTTI} notti, ma non impossibile: novanta
     * notti a un milione l'una ci arrivano.
     */
    private BigDecimal importoFotografato(PreventivoTipologia preventivo) {
        BigDecimal importo = preventivo.getImportoTotale();

        if (importo.compareTo(IMPORTO_MASSIMO) > 0) {
            throw new BadRequestException(
                    "Il totale del soggiorno supera il massimo gestibile: accorciare il periodo");
        }

        return importo;
    }
}
