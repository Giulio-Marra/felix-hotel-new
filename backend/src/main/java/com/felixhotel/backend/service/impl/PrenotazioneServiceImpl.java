package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.entity.CanalePrenotazione;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
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
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.service.PrenotazioneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * <p><b>L'account di chi chiama si risolve per email e non per id.</b> Il
 * principal porta un {@code userId} che vale sulla tabella {@code utente} per i
 * clienti e su {@code staff} per il personale, senza dire quale delle due:
 * l'id 3 esiste probabilmente in entrambe. L'email invece e' univoca
 * nell'insieme delle due popolazioni — e' il presupposto su cui
 * {@code CustomUserDetailsService} costruisce il login — quindi e' l'unica
 * chiave che identifica una persona sola.
 */
@Service
public class PrenotazioneServiceImpl implements PrenotazioneService {

    private static final String RUOLO_ADMIN = "ADMIN";
    private static final String RUOLO_STAFF = "STAFF";

    /**
     * Il massimo che entra in {@code importo_totale}, che e' NUMERIC(10,2).
     *
     * <p>Serve perche' il totale non lo scrive il client ma lo calcoliamo noi:
     * su tutti gli altri campi il tetto sta nello spec e lo fa rispettare la
     * validazione, qui il valore nasce da una moltiplicazione e nessuno lo ha
     * validato prima di noi.
     */
    private static final BigDecimal IMPORTO_MASSIMO = new BigDecimal("99999999.99");

    private final PrenotazioneRepository prenotazioneRepository;
    private final TipologiaCameraRepository tipologiaCameraRepository;

    /** Serve alla meta' del calcolo della disponibilita' che conta le camere esistenti. */
    private final CameraRepository cameraRepository;

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;
    private final PrenotazioneMapper prenotazioneMapper;
    private final ApiResponseMapper apiResponseMapper;

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
                                   PrenotazioneMapper prenotazioneMapper,
                                   ApiResponseMapper apiResponseMapper) {
        // Il fuso e' quello di sistema e non UTC, al contrario dei contatori del
        // ritardo progressivo: quelli misurano durate, a cui il fuso non serve, mentre
        // "oggi" per un albergo e' il giorno che si legge sul calendario alla
        // reception. Con UTC, alle due di notte in Italia sarebbe ancora ieri.
        this(prenotazioneRepository, tipologiaCameraRepository, cameraRepository, utenteRepository,
                staffRepository, prenotazioneMapper, apiResponseMapper, Clock.systemDefaultZone());
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
                                   PrenotazioneMapper prenotazioneMapper,
                                   ApiResponseMapper apiResponseMapper,
                                   Clock clock) {
        this.prenotazioneRepository = prenotazioneRepository;
        this.tipologiaCameraRepository = tipologiaCameraRepository;
        this.cameraRepository = cameraRepository;
        this.utenteRepository = utenteRepository;
        this.staffRepository = staffRepository;
        this.prenotazioneMapper = prenotazioneMapper;
        this.apiResponseMapper = apiResponseMapper;
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
        AppUserPrincipal chiamante = chiamante();

        // Null vuol dire "tutte" ed e' un privilegio, non l'assenza di un filtro: lo
        // ottiene solo chi e' del personale. Per un cliente l'id e' sempre il proprio,
        // e non perche' l'abbia chiesto.
        Long utenteId = personale(chiamante) ? null : clienteChiamante(chiamante).getId();

        Page<Prenotazione> pagina = prenotazioneRepository.cerca(
                utenteId,
                stato == null ? null : prenotazioneMapper.toStatoEntity(stato),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dataCheckIn")));

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
     * <p>L'ordine dei controlli non e' casuale: prima chi e' il cliente e chi la
     * registra, poi le date, poi la capienza, e la disponibilita' per ultima.
     * Quest'ultima e' l'unica che costa due query, e sarebbe uno spreco pagarla
     * per una richiesta che ha le date invertite.
     */
    @Override
    @Transactional
    public ApiBaseResponse crea(PrenotazioneRequest request) {
        AppUserPrincipal chiamante = chiamante();
        boolean daPersonale = personale(chiamante);

        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setUtente(intestatario(request, chiamante, daPersonale));
        prenotazione.setCanale(canale(request, daPersonale));
        prenotazione.setGestitaDaStaff(daPersonale ? staffChiamante(chiamante) : null);

        TipologiaCamera tipologia = trovaTipologiaOrElseThrow(request.getTipologiaCameraId());
        verificaDate(request.getDataCheckIn(), request.getDataCheckOut());
        verificaCapienza(request.getNumeroOspiti(), tipologia);
        verificaDisponibilita(tipologia, request.getDataCheckIn(), request.getDataCheckOut(), null);

        prenotazione.setTipologiaCamera(tipologia);
        prenotazione.setDataCheckIn(request.getDataCheckIn());
        prenotazione.setDataCheckOut(request.getDataCheckOut());
        prenotazione.setNumeroOspiti(request.getNumeroOspiti());
        prenotazione.setNote(request.getNote());
        // Lo stato non si imposta: una Prenotazione nasce IN_ATTESA per
        // inizializzatore di campo, e ripeterlo qui lascerebbe due posti da
        // cambiare il giorno che il carrello non fosse piu' il punto di partenza.
        prenotazione.setImportoTotale(
                calcolaImporto(tipologia, request.getDataCheckIn(), request.getDataCheckOut()));

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
     * Chi sta chiamando, preso dal {@code SecurityContextHolder} e non da
     * {@code @AuthenticationPrincipal}: la firma dei metodi la impone
     * l'interfaccia generata dallo spec, che non ha un parametro per il principal
     * (regola 14).
     *
     * <p>Nessuno di questi endpoint e' in {@code permitAll}, quindi qui ci si
     * arriva solo autenticati; il controllo resta perche' un anonimo avrebbe come
     * principal la stringa "anonymousUser", che senza questo {@code instanceof}
     * diventerebbe una ClassCastException — cioe' un 500 al posto di un 401.
     */
    private AppUserPrincipal chiamante() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new UnauthorizedException("Nessun account autenticato");
        }

        return principal;
    }

    /** Se chi chiama appartiene al personale, cioe' se puo' vedere e toccare le prenotazioni altrui. */
    private boolean personale(AppUserPrincipal chiamante) {
        return RUOLO_ADMIN.equals(chiamante.getRuoloNome()) || RUOLO_STAFF.equals(chiamante.getRuoloNome());
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

        AppUserPrincipal chiamante = chiamante();
        if (!personale(chiamante) && !prenotazione.getUtente().getId().equals(clienteChiamante(chiamante).getId())) {
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

        return utenteRepository.findById(idIntestatario)
                .orElseThrow(() -> new BadRequestException(
                        "Il cliente indicato non esiste: " + idIntestatario));
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
     * Il cliente corrispondente a chi chiama. Vedi il javadoc di classe per il
     * perche' si cerchi per email: l'id del principal non dice a quale delle due
     * tabelle appartenga.
     *
     * <p>Non trovarlo non e' un 404 ma un 401: vuol dire che il token e' valido
     * per un account che non c'e' piu'.
     */
    private Utente clienteChiamante(AppUserPrincipal chiamante) {
        return utenteRepository.findByEmail(chiamante.getUsername())
                .orElseThrow(() -> new UnauthorizedException("L'account autenticato non esiste piu'"));
    }

    /**
     * Il membro del personale corrispondente a chi chiama, da registrare come
     * gestore della prenotazione.
     *
     * <p>Non trovarlo e' un caso storto ma possibile: un account con ruolo ADMIN
     * o STAFF che non sta nella tabella {@code staff} — cioe' un cliente a cui
     * qualcuno ha cambiato il ruolo a mano nel database. Rispondere 400 invece di
     * lasciar scrivere una riga senza gestore e' la scelta rumorosa: quella
     * situazione va vista, non aggirata.
     */
    private Staff staffChiamante(AppUserPrincipal chiamante) {
        return staffRepository.findByEmail(chiamante.getUsername())
                .orElseThrow(() -> new BadRequestException(
                        "L'account che sta registrando la prenotazione non appartiene al personale"));
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
     */
    private void verificaDate(LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (!dataCheckOut.isAfter(dataCheckIn)) {
            throw new BadRequestException("La data di partenza deve essere successiva a quella di arrivo");
        }

        if (dataCheckIn.isBefore(LocalDate.now(clock))) {
            throw new BadRequestException("Non si puo' prenotare un arrivo gia' passato");
        }
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
     * Il calcolo della disponibilita', che e' una sottrazione fra due conteggi:
     * quante camere di quella tipologia esistono, meno quante risultano gia'
     * impegnate nel periodo.
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
        long camereImpegnate = prenotazioneRepository.contaSovrapposte(tipologia.getId(), dataCheckIn,
                dataCheckOut, StatoPrenotazione.statiCheOccupano(), esclusa);

        if (camereEsistenti - camereImpegnate <= 0) {
            throw new ConflictException(
                    "Nessuna camera disponibile di questa tipologia per il periodo richiesto");
        }
    }

    /**
     * Totale del soggiorno: prezzo per notte per il numero di notti.
     *
     * <p>Le notti sono i giorni fra arrivo e partenza, non i giorni compresi: chi
     * arriva il 10 e parte il 13 dorme tre notti. E' la stessa aritmetica che
     * rende il giorno di partenza libero per chi arriva quel giorno.
     *
     * <p><b>Il tetto va controllato qui e non nello spec</b> perche' questo
     * numero non lo manda il client: nasce da una moltiplicazione, e un totale
     * che non entra in NUMERIC(10,2) verrebbe rifiutato da Postgres — cioe'
     * arriverebbe a chi chiama come un 500, dando la colpa a noi di un soggiorno
     * che aveva chiesto lui.
     */
    private BigDecimal calcolaImporto(TipologiaCamera tipologia, LocalDate dataCheckIn, LocalDate dataCheckOut) {
        long notti = ChronoUnit.DAYS.between(dataCheckIn, dataCheckOut);
        BigDecimal importo = tipologia.getPrezzoNotte().multiply(BigDecimal.valueOf(notti));

        if (importo.compareTo(IMPORTO_MASSIMO) > 0) {
            throw new BadRequestException(
                    "Il totale del soggiorno supera il massimo gestibile: accorciare il periodo");
        }

        return importo;
    }
}
