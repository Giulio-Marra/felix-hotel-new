package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipoDocumento;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.OspiteMapper;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.service.OspiteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Implementazione del registro degli ospiti.
 *
 * <p>Nella forma e' il gemello di {@code MediaCameraServiceImpl} — sottorisorsa,
 * controllo preventivo sul duplicato piu' {@code saveAndFlush} dentro il try per
 * tradurre in 409 anche la richiesta gemella arrivata nel frattempo — ma ha tre
 * cose che quello non aveva, e sono le sole che vale la pena leggere due volte.
 *
 * <p><b>La prima: il permesso non finisce sul Controller.</b> Ogni metodo
 * comincia da {@link #assicuraPersonale()}, che pretende un account di tipo
 * PERSONALE oltre al ruolo che {@code @PreAuthorize} ha gia' verificato. Le due
 * domande sono diverse — il ruolo dice cosa un account puo' fare, il tipo dice
 * dove vive — e qui servono entrambe le risposte, perche' il contenuto di questa
 * risorsa sono documenti d'identita' di persone che non sono nemmeno clienti
 * dell'albergo: sono i loro accompagnatori. E' 401 e non 403 per la stessa
 * ragione gia' scritta su {@code PrenotazioneServiceImpl.idClienteChiamante}:
 * non e' una questione di permessi, e' un token che vale per un account che non
 * e' quello che dice di essere.
 *
 * <p><b>La seconda: si scrive solo dentro una finestra.</b> CONFERMATA o
 * CHECK_IN, mai fuori. Non e' una precauzione generica ma la regola che il
 * progetto applica a tutto cio' che e' concluso — <i>quello che c'e' gia' resta,
 * di nuovo non si aggiunge niente</i>, scelta il 2026-08-27 per gli account
 * disattivati. Su IN_ATTESA il motivo e' un altro e vale la pena distinguerlo:
 * li' non c'e' ancora nessun soggiorno, perche' un carrello non confermato non
 * impegna niente e nessuno.
 *
 * <p><b>La terza: il documento non lo si pretende da chi non ce l'ha.</b> Dal V10
 * {@code tipoDocumento} e {@code numeroDocumento} sono facoltativi nello schema, e
 * a decidere se in questa richiesta debbano esserci e'
 * {@link #verificaDocumento(OspiteRequest, Prenotazione)}. La regola sta qui e non
 * nel contratto per una ragione precisa: dipende dalla <b>data di arrivo della
 * prenotazione</b>, che sta nell'URL e non nel corpo, quindi nessuna validazione di
 * bordo puo' vederla. E l'obbligo di legge non si allenta di un millimetro — un
 * minorenne un documento proprio non ce l'ha, e pretenderlo lo farebbe inventare al
 * banco, riempiendo di numeri falsi il registro che esiste apposta per essere vero.
 *
 * <p><b>Perche' il conteggio e' esatto e non un massimo.</b> Il tetto qui e' il
 * pavimento del check-in: si rifiuta l'ospite oltre {@code numeroOspiti} perche'
 * non si vendono piu' posti letto di quanti ne siano stati comprati, e
 * {@code PrenotazioneServiceImpl.checkIn} pretende lo stesso numero perche' il
 * TULPS vuole il documento di <i>ogni</i> persona che dorme li'. I due controlli
 * guardano lo stesso conteggio da due lati, ed e' il motivo per cui la
 * condizione del check-in e' un'uguaglianza invece di un "almeno uno".
 */
@Service
public class OspiteServiceImpl implements OspiteService {

    /**
     * Gli stati in cui il registro si puo' ancora scrivere.
     *
     * <p>Un {@code List} e non un {@code EnumSet} perche' sono due e l'unica
     * operazione e' un {@code contains}: il costo non si misura e la lista si
     * legge meglio di una costruzione.
     */
    private static final List<StatoPrenotazione> STATI_MODIFICABILI =
            List.of(StatoPrenotazione.CONFERMATA, StatoPrenotazione.CHECK_IN);

    /**
     * Gli anni sotto i quali non si pretende un documento.
     *
     * <p><b>Una costante e non una configurazione</b>, ed e' la regola 24 applicata
     * a un numero: la maggiore eta' e' la stessa per ogni albergo d'Italia, quindi
     * non e' qualcosa che due alberghi vorrebbero diverso. La soglia dell'esenzione
     * dalla tassa di soggiorno, che assomiglia a questa e non e' questa, sara'
     * invece configurabile — la decide il comune, e ogni comune la scrive a modo
     * suo. Tenerle separate e' cio' che evita che cambiarne una muova l'altra.
     */
    private static final int MAGGIORE_ETA = 18;

    private final OspiteRepository ospiteRepository;

    /** Serve a risolvere la prenotazione del percorso, e a leggerne stato e numero di ospiti. */
    private final PrenotazioneRepository prenotazioneRepository;

    private final OspiteMapper ospiteMapper;
    private final ApiResponseMapper apiResponseMapper;
    private final ChiamanteCorrente chiamanteCorrente;

    /**
     * Da cosa dipende "oggi", che qui serve a una cosa sola: rifiutare una data
     * di nascita nel futuro.
     */
    private final Clock clock;

    /**
     * Costruttore usato da Spring. L'annotazione e' necessaria perche' i
     * costruttori pubblici sono due — stessa forma gia' usata da
     * {@code PrenotazioneServiceImpl} e {@code LoginAttemptServiceImpl}.
     */
    @Autowired
    public OspiteServiceImpl(OspiteRepository ospiteRepository,
                             PrenotazioneRepository prenotazioneRepository,
                             OspiteMapper ospiteMapper,
                             ApiResponseMapper apiResponseMapper,
                             ChiamanteCorrente chiamanteCorrente) {
        // Fuso di sistema e non UTC, come per le prenotazioni: "oggi" e' il giorno
        // che si legge sul calendario alla reception.
        this(ospiteRepository, prenotazioneRepository, ospiteMapper, apiResponseMapper,
                chiamanteCorrente, Clock.systemDefaultZone());
    }

    /**
     * Costruttore per i test, che passando un {@link Clock} pilotabile possono
     * decidere che giorno e' senza dipendere dalla data di esecuzione — vedi
     * {@code OrologioPilotato}.
     */
    public OspiteServiceImpl(OspiteRepository ospiteRepository,
                             PrenotazioneRepository prenotazioneRepository,
                             OspiteMapper ospiteMapper,
                             ApiResponseMapper apiResponseMapper,
                             ChiamanteCorrente chiamanteCorrente,
                             Clock clock) {
        this.ospiteRepository = ospiteRepository;
        this.prenotazioneRepository = prenotazioneRepository;
        this.ospiteMapper = ospiteMapper;
        this.apiResponseMapper = apiResponseMapper;
        this.chiamanteCorrente = chiamanteCorrente;
        this.clock = clock;
    }

    /**
     * L'elenco, gia' ordinato dalla query.
     *
     * <p>Il controllo di esistenza sulla prenotazione c'e' anche se la query
     * restituirebbe comunque una lista vuota, ed e' la stessa scelta gia' fatta
     * per la galleria: senza, chiedere gli ospiti di una prenotazione
     * inesistente darebbe una lista vuota, indistinguibile da un soggiorno su cui
     * non e' stato ancora registrato nessuno.
     *
     * <p><b>Nessuna finestra di stato in lettura</b>, al contrario dei tre metodi
     * che scrivono: un registro esiste per essere riletto dopo, e su una
     * prenotazione in CHECK_OUT e' precisamente quando serve.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse elenca(Long prenotazioneId) {
        assicuraPersonale();
        assicuraPrenotazioneEsistente(prenotazioneId);

        List<Ospite> ospiti = ospiteRepository.findByPrenotazioneIdOrderByIdAsc(prenotazioneId);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Ospiti della prenotazione recuperati",
                ospiteMapper.toResponseList(ospiti));
    }

    /**
     * Registra un ospite.
     *
     * <p>L'ordine dei tre 409 non e' casuale, come non lo era sulla galleria.
     * Prima la <b>finestra</b>, perche' su una prenotazione annullata nessuno
     * degli altri due messaggi vorrebbe dire niente. Poi il <b>duplicato</b>,
     * perche' a chi sta reinviando lo stesso modulo "questa persona c'e' gia'" e'
     * la risposta utile — dirgli "sono gia' tutti" lo manderebbe a cercare chi
     * togliere. Il <b>tetto</b> per ultimo.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiungi(Long prenotazioneId, OspiteRequest request) {
        assicuraPersonale();
        Prenotazione prenotazione = trovaPrenotazioneOrElseThrow(prenotazioneId);
        assicuraFinestraDiScrittura(prenotazione);

        verificaDataNascita(request.getDataNascita());
        TipoDocumento tipoDocumento = verificaDocumento(request, prenotazione);

        // Il controllo del duplicato si salta per chi non ha un documento: dove non
        // c'e' un documento non c'e' niente da confrontare, e due minorenni sulla
        // stessa prenotazione sono il caso normale di una famiglia. Lo stesso vale
        // per l'indice unico del V7, che sui NULL non scatta.
        if (tipoDocumento != null
                && ospiteRepository.existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumento(
                prenotazioneId, tipoDocumento, request.getNumeroDocumento())) {
            throw new ConflictException("Questo documento e' gia' registrato su questa prenotazione");
        }

        long registrati = ospiteRepository.countByPrenotazioneId(prenotazioneId);
        if (registrati >= prenotazione.getNumeroOspiti()) {
            // Il messaggio non suggerisce di "modificare la prenotazione", che sarebbe
            // la cosa naturale da dire e sarebbe una bugia: numeroOspiti si fissa alla
            // creazione e nessun endpoint lo cambia. Vedi il gap aperto il 2026-08-28.
            throw new ConflictException("La prenotazione e' per " + prenotazione.getNumeroOspiti()
                    + " ospiti e sono gia' tutti registrati");
        }

        Ospite ospite = new Ospite();
        ospite.setPrenotazione(prenotazione);
        applica(ospite, request, tipoDocumento);

        Ospite salvato = salvaGestendoIlDuplicato(ospite);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Ospite registrato",
                ospiteMapper.toResponse(salvato));
    }

    /**
     * Corregge un ospite gia' registrato.
     *
     * <p><b>Niente controllo sul tetto</b>, e non e' una dimenticanza: la
     * correzione non aggiunge una riga, quindi il conto non puo' superare
     * {@code numeroOspiti} per colpa sua. Ripeterlo qui vorrebbe dire un ramo che
     * nessuna richiesta puo' percorrere — e una prenotazione che si trovasse
     * sopra il tetto per altre vie resterebbe bloccata anche nel correggere gli
     * errori di battitura, che e' l'opposto di quel che serve.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long prenotazioneId, Long ospiteId, OspiteRequest request) {
        assicuraPersonale();
        Prenotazione prenotazione = trovaPrenotazioneOrElseThrow(prenotazioneId);
        assicuraFinestraDiScrittura(prenotazione);

        Ospite ospite = trovaOspiteOrElseThrow(prenotazioneId, ospiteId);

        verificaDataNascita(request.getDataNascita());
        TipoDocumento tipoDocumento = verificaDocumento(request, prenotazione);

        // Saltato per chi non ha documento, per la stessa ragione scritta in aggiungi().
        if (tipoDocumento != null
                && ospiteRepository.existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumentoAndIdNot(
                prenotazioneId, tipoDocumento, request.getNumeroDocumento(), ospiteId)) {
            throw new ConflictException(
                    "Questo documento e' gia' registrato su un altro ospite di questa prenotazione");
        }

        applica(ospite, request, tipoDocumento);

        Ospite salvato = salvaGestendoIlDuplicato(ospite);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Ospite aggiornato",
                ospiteMapper.toResponse(salvato));
    }

    /**
     * Cancella la registrazione di un ospite.
     *
     * <p>Nessun {@code flush} dentro un try, come per le foto: verso
     * {@code ospite} non punta nessuna chiave esterna, quindi non c'e' nessuna
     * violazione da tradurre in 409.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long prenotazioneId, Long ospiteId) {
        assicuraPersonale();
        Prenotazione prenotazione = trovaPrenotazioneOrElseThrow(prenotazioneId);
        assicuraFinestraDiScrittura(prenotazione);

        Ospite ospite = trovaOspiteOrElseThrow(prenotazioneId, ospiteId);

        ospiteRepository.delete(ospite);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Ospite eliminato", null);
    }

    /**
     * Che chi chiama sia davvero del personale, e non solo che ne porti il ruolo.
     *
     * <p><b>Non e' un doppione di {@code @PreAuthorize}</b>: quello verifica il
     * ruolo, questo il tipo dell'account, e sono due domande diverse a cui dal
     * 2026-08-27 bisogna rispondere di si' tutte e due. Un account ibrido — una
     * riga di {@code utente} a cui una {@code UPDATE} a mano ha dato ruolo STAFF
     * — passa l'annotazione e si ferma qui.
     *
     * <p>401 e non 403, come gia' scritto sopra: il ruolo che ha e' sufficiente,
     * e' l'account a non essere quello che dice di essere.
     */
    private void assicuraPersonale() {
        if (!chiamanteCorrente.personale(chiamanteCorrente.autenticato())) {
            throw new UnauthorizedException("L'account autenticato non e' quello di un membro del personale");
        }
    }

    /**
     * 404 se la prenotazione del percorso non esiste, senza caricarne niente:
     * serve alla sola lettura, che della prenotazione non usa nessun campo.
     */
    private void assicuraPrenotazioneEsistente(Long prenotazioneId) {
        if (!prenotazioneRepository.existsById(prenotazioneId)) {
            throw new NotFoundException("Prenotazione non trovata");
        }
    }

    /**
     * Come sopra, ma restituendo l'entita': i tre metodi che scrivono hanno
     * bisogno dello stato e di {@code numeroOspiti}.
     *
     * <p><b>Non applica il permesso "e' tua?"</b> come fa
     * {@code PrenotazioneServiceImpl.trovaVisibileOrElseThrow}, e la ragione e'
     * che qui quella domanda non esiste: a questa risorsa arriva solo il
     * personale, che vede tutte le prenotazioni per definizione. Il 404 qui vuol
     * dire davvero "non c'e'".
     */
    private Prenotazione trovaPrenotazioneOrElseThrow(Long prenotazioneId) {
        return prenotazioneRepository.findById(prenotazioneId)
                .orElseThrow(() -> new NotFoundException("Prenotazione non trovata"));
    }

    /**
     * L'ospite, ma solo se e' di questa prenotazione. Il controllo sulla
     * prenotazione e' gia' stato fatto da chi chiama, e serve al messaggio: con
     * la sola {@code findByIdAndPrenotazioneId}, un id di prenotazione sbagliato
     * direbbe "ospite non trovato" a chi ha in mano un ospite valido, mandandolo
     * a cercare il problema dalla parte sbagliata.
     */
    private Ospite trovaOspiteOrElseThrow(Long prenotazioneId, Long ospiteId) {
        return ospiteRepository.findByIdAndPrenotazioneId(ospiteId, prenotazioneId)
                .orElseThrow(() -> new NotFoundException("Ospite non trovato"));
    }

    /**
     * Che la prenotazione sia in una fase in cui il registro si scrive ancora.
     *
     * <p>409 e non 400: la richiesta e' ben formata, e' lo stato della
     * prenotazione a non essere d'accordo. E' la stessa scelta gia' fatta da
     * conferma, annullamento e check-in.
     */
    private void assicuraFinestraDiScrittura(Prenotazione prenotazione) {
        if (!STATI_MODIFICABILI.contains(prenotazione.getStato())) {
            throw new ConflictException(
                    "Gli ospiti si registrano su una prenotazione confermata o in corso: questa e' "
                            + prenotazione.getStato().name());
        }
    }

    /**
     * Decide se questa richiesta debba portare un documento, e in caso lo traduce
     * nell'enum dell'entita'. Restituisce {@code null} per chi un documento non lo
     * deve dare, cioe' per un minorenne che non l'ha allegato.
     *
     * <p><b>Due regole in un metodo solo</b>, perche' sono la stessa domanda —
     * <i>questo documento e' in regola?</i> — guardata dai due lati:
     * <ol>
     *   <li><b>tipo e numero viaggiano in coppia.</b> Uno senza l'altro e' mezzo
     *       dato: chi rilegge il registro non saprebbe se il numero manchi perche'
     *       non esiste o perche' qualcuno si e' fermato a meta' del modulo. Non lo
     *       impedisce nessun vincolo di database, di proposito — un {@code CHECK}
     *       sulle due colonne si potrebbe scrivere, ma direbbe la meta' della regola
     *       lasciando fuori quella che conta.</li>
     *   <li><b>senza documento si passa solo se si e' minorenni all'arrivo.</b> Un
     *       maggiorenne che non lo allega e' un 400 e non un caso da accogliere: e'
     *       l'obbligo del TULPS, ed e' esattamente quel che questa risorsa esiste
     *       per far rispettare.</li>
     * </ol>
     *
     * <p><b>Perche' e' 400 e non 409.</b> Non c'e' nessun conflitto con qualcosa
     * che esiste gia': e' il corpo della richiesta a non stare in piedi da solo,
     * come una data di nascita nel futuro.
     *
     * <p>La conversione fra i due {@code TipoDocumento} — quello del contratto e
     * quello dell'entita' — resta qui, ed e' logica e non copia: se il contratto
     * guadagnasse un valore che l'entita' non ha, e' questa riga a rompersi. Non
     * puo' fallire sui valori attuali, perche' la validazione del DTO ha gia'
     * rifiutato tutto cio' che non e' nell'enum generato, ma i due elenchi vanno
     * tenuti allineati a mano e questo e' l'unico punto in cui si toccano.
     */
    private TipoDocumento verificaDocumento(OspiteRequest request, Prenotazione prenotazione) {
        // Letti una volta in due variabili, e non richiesti al DTO ad ogni riga: sono
        // campi facoltativi, quindi il tipo dichiara di poter essere null e un secondo
        // accesso e' un secondo valore per chi legge il flusso — SpotBugs compreso, che
        // sulla versione con due getter separati segnalava un possibile dereferenziamento.
        com.felixhotel.backend.dto.TipoDocumento tipoRichiesto = request.getTipoDocumento();
        String numeroRichiesto = request.getNumeroDocumento();

        if ((tipoRichiesto == null) != (numeroRichiesto == null)) {
            throw new BadRequestException(
                    "Tipo e numero del documento vanno indicati insieme, oppure omessi tutti e due");
        }

        if (tipoRichiesto == null) {
            if (maggiorenneAllArrivo(request.getDataNascita(), prenotazione.getDataCheckIn())) {
                throw new BadRequestException(
                        "Il documento e' obbligatorio: alla data di arrivo questo ospite e' maggiorenne");
            }
            // Minorenne senza documento: e' il caso che il V10 esiste per permettere.
            return null;
        }

        return TipoDocumento.valueOf(tipoRichiesto.getValue());
    }

    /**
     * Se il giorno dell'arrivo questa persona avra' gia' compiuto diciotto anni.
     *
     * <p><b>La data che conta e' quella di arrivo e non oggi</b>, e la differenza si
     * vede su una prenotazione fatta con mesi di anticipo per qualcuno che nel
     * frattempo diventa maggiorenne. E' l'arrivo perche' e' li' che il TULPS chiede
     * il documento — <i>all'atto dell'arrivo</i> — quindi e' quello il giorno in cui
     * la domanda "questa persona un documento ce l'ha?" ha una risposta.
     *
     * <p>Il confronto e' fra date e non fra numeri di anni: {@code dataNascita} piu'
     * diciotto anni cade il giorno del compleanno, e chi arriva quel giorno e' gia'
     * maggiorenne. Scritto cosi' invece che con un {@code Period} perche' e' la
     * stessa frase della legge, senza un'aritmetica in mezzo da rileggere.
     */
    private boolean maggiorenneAllArrivo(LocalDate dataNascita, LocalDate dataArrivo) {
        return !dataNascita.plusYears(MAGGIORE_ETA).isAfter(dataArrivo);
    }

    /**
     * Che la data di nascita non sia nel futuro. Che ci sia lo ha gia' preteso la
     * validazione del bordo: dal V10 e' un campo obbligatorio dello schema.
     *
     * <p>E' l'unico controllo possibile su questo campo senza inventare una
     * regola che nessuno ha deciso: quanti anni debba avere un ospite, o quanto
     * vecchio possa essere, non lo dice nessun documento del progetto. Una data
     * nel futuro invece non e' un'opinione, e' un errore di battitura — quasi
     * sempre l'anno.
     *
     * <p><b>Non e' una difesa contro chi mentisse sull'eta' per non dare il
     * documento</b>, e vale la pena dirlo adesso che da questo campo dipende quello
     * obbligo: una data di nascita finta la scrive il personale, cioe' proprio chi
     * il registro ha l'obbligo di tenere vero, e nessun controllo di formato
     * distingue un bambino inventato da uno reale. Cio' che il codice puo' fare e'
     * non rendere l'inganno necessario, ed e' quel che il V10 ha fatto. Da notare
     * che una data molto <i>indietro</i> nel tempo sbaglia dalla parte severa: rende
     * l'ospite maggiorenne, quindi il documento diventa obbligatorio.
     *
     * <p>400 e non 409, al contrario dei controlli sullo stato: qui e' un valore
     * sbagliato nel corpo, non un conflitto con qualcosa che gia' esiste.
     */
    private void verificaDataNascita(LocalDate dataNascita) {
        if (dataNascita.isAfter(LocalDate.now(clock))) {
            throw new BadRequestException("La data di nascita non puo' essere nel futuro");
        }
    }

    /**
     * Copia i campi della richiesta sull'entita'. Uno solo per creazione e
     * correzione, perche' i campi modificabili sono tutti: un ospite e' fatto di
     * dati che si possono aver digitati storti, e nessuno di essi si sceglie una
     * volta sola.
     */
    private void applica(Ospite ospite, OspiteRequest request, TipoDocumento tipoDocumento) {
        ospite.setNome(request.getNome());
        ospite.setCognome(request.getCognome());
        // Tutti e due null per un minorenne senza documento, mai uno solo dei due:
        // verificaDocumento() ha gia' rifiutato la coppia a meta'.
        ospite.setTipoDocumento(tipoDocumento);
        ospite.setNumeroDocumento(request.getNumeroDocumento());
        ospite.setDataNascita(request.getDataNascita());
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico su (prenotazione, tipo, numero). E' la rete
     * sotto ai controlli {@code existsBy}: copre la richiesta gemella arrivata
     * nel frattempo, che nessun controllo preventivo puo' vedere.
     *
     * <p>Il tetto su {@code numeroOspiti}, invece, <b>non</b> ha una rete —
     * nessun vincolo di database sa contare le righe di un gruppo — quindi due
     * registrazioni simultanee sull'ultimo posto possono portare a un ospite di
     * troppo. E' la stessa forma gia' accettata sul tetto delle foto, ma qui la
     * conseguenza e' diversa e va scritta: un ospite in piu' non rompe niente
     * subito, pero' il check-in pretende un'uguaglianza, quindi da quel momento
     * non passerebbe piu'. Si ripara togliendo la riga di troppo.
     */
    private Ospite salvaGestendoIlDuplicato(Ospite ospite) {
        try {
            return ospiteRepository.saveAndFlush(ospite);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Questo documento e' gia' registrato su questa prenotazione", ex);
        }
    }
}
