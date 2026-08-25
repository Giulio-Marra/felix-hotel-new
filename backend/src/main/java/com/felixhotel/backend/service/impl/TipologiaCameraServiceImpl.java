package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.TipologiaCameraDotazioniRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;
import com.felixhotel.backend.entity.Dotazione;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.repository.DotazioneRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.TipologiaCameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementazione del catalogo delle tipologie di camera.
 *
 * <p>Due cose ricorrono e vale la pena leggerle una volta sola.
 *
 * <p><b>Il duplicato si controlla due volte, non per distrazione.</b> Prima
 * con un {@code existsBy}, che permette di rispondere 409 con un messaggio
 * comprensibile; poi lasciando parlare l'indice unico del database, perche' fra
 * il controllo e la scrittura ci sta un'altra richiesta identica. Il primo
 * controllo e' cortesia, il secondo e' la garanzia: togliere quello e tenere
 * solo questo darebbe messaggi incomprensibili, togliere questo e tenere solo
 * quello lascerebbe passare i duplicati sotto carico.
 *
 * <p><b>La violazione di vincolo si intercetta dov'e' generata.</b> Le scritture
 * passano da {@code saveAndFlush}/{@code flush} dentro il try: senza il flush
 * esplicito, Hibernate rimanderebbe la SQL al commit della transazione, cioe'
 * <b>fuori</b> da questo metodo e dal catch — e la violazione tornerebbe al
 * client come 500 invece che come 409.
 */
@Service
@RequiredArgsConstructor
public class TipologiaCameraServiceImpl implements TipologiaCameraService {

    private final TipologiaCameraRepository tipologiaCameraRepository;

    /**
     * Serve solo a risolvere gli id ricevuti da {@link #impostaDotazioni}. Le
     * dotazioni hanno un Service proprio, ma chiamarlo da qui vorrebbe dire
     * farsi restituire delle buste HTTP da spacchettare per leggere delle
     * righe: fra due Service che si parlano e un repository usato per quello
     * che e', il repository e' la dipendenza onesta.
     */
    private final DotazioneRepository dotazioneRepository;

    private final TipologiaCameraMapper tipologiaCameraMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Elenco paginato, ordinato per nome. L'ordine e' esplicito e non lasciato
     * al database: senza {@code ORDER BY}, Postgres puo' restituire le righe in
     * ordine diverso fra una pagina e l'altra, e un elemento finirebbe per
     * comparire due volte o mai — un difetto che si manifesta solo quando i dati
     * crescono abbastanza da avere piu' di una pagina.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size) {
        Page<TipologiaCamera> pagina = tipologiaCameraRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "nome")));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Tipologie di camera recuperate",
                tipologiaCameraMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse dettaglio(Long id) {
        TipologiaCamera tipologia = trovaOrElseThrow(id);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Tipologia di camera recuperata",
                tipologiaCameraMapper.toResponse(tipologia));
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(TipologiaCameraRequest request) {
        verificaPrezzo(request);

        if (tipologiaCameraRepository.existsByNomeIgnoreCase(request.getNome())) {
            throw new ConflictException("Esiste gia' una tipologia di camera con questo nome");
        }

        TipologiaCamera tipologia = new TipologiaCamera();
        applicaCampi(tipologia, request);

        TipologiaCamera salvata = salvaGestendoIlDuplicato(tipologia);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Tipologia di camera creata",
                tipologiaCameraMapper.toResponse(salvata));
    }

    /**
     * Aggiornamento completo: e' una PUT, quindi i campi assenti dalla richiesta
     * vengono azzerati e non lasciati al valore precedente. Non e' un effetto
     * collaterale ma il significato del verbo — chi vuole toccare un campo solo
     * manda comunque tutti gli altri.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long id, TipologiaCameraRequest request) {
        verificaPrezzo(request);

        TipologiaCamera tipologia = trovaOrElseThrow(id);

        // Escludendo se stessa: senza IdNot, salvare una tipologia senza cambiarle il
        // nome darebbe 409 contro il proprio nome.
        if (tipologiaCameraRepository.existsByNomeIgnoreCaseAndIdNot(request.getNome(), id)) {
            throw new ConflictException("Esiste gia' un'altra tipologia di camera con questo nome");
        }

        applicaCampi(tipologia, request);

        TipologiaCamera salvata = salvaGestendoIlDuplicato(tipologia);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Tipologia di camera aggiornata",
                tipologiaCameraMapper.toResponse(salvata));
    }

    /**
     * Eliminazione. Le camere e le prenotazioni puntano alla tipologia con una
     * chiave esterna senza cascata, di proposito: cancellare una tipologia usata
     * porterebbe via con se' lo storico delle prenotazioni, che serve anche
     * quando quella tipologia non e' piu' in listino. Chi ci prova riceve un 409.
     *
     * <p>Il conteggio dei riferimenti non si fa a mano perche' non ci sarebbe
     * modo di farlo bene: le entity {@code Camera} e {@code Prenotazione} non
     * esistono ancora in Java, e anche quando esisteranno un controllo
     * preventivo lascerebbe aperta la finestra fra il controllo e la
     * cancellazione. A dire com'e' andata e' il database, che e' l'unico a
     * saperlo con certezza.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long id) {
        TipologiaCamera tipologia = trovaOrElseThrow(id);

        try {
            tipologiaCameraRepository.delete(tipologia);
            tipologiaCameraRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "Impossibile eliminare la tipologia: e' ancora usata da camere o prenotazioni", ex);
        }

        // 'data' null: dopo un'eliminazione non c'e' niente da restituire. Lo status e'
        // 200 e non 204 perche' la busta standard vale per ogni endpoint del progetto,
        // e un 204 per definizione non ha corpo.
        return apiResponseMapper.toResponse(HttpStatus.OK, "Tipologia di camera eliminata", null);
    }

    /**
     * Sostituisce l'insieme delle dotazioni assegnate alla tipologia.
     *
     * <p><b>Perche' un endpoint suo e non un campo di {@code TipologiaCameraRequest}.</b>
     * Le dotazioni sono righe di un'altra tabella, non un attributo di questa:
     * infilarle nella PUT della tipologia vorrebbe dire che chi cambia un prezzo
     * deve rimandare anche l'elenco delle dotazioni, e che dimenticarselo le
     * cancella tutte. Tenerle separate rende ogni chiamata responsabile di una
     * cosa sola.
     *
     * <p><b>Gli id sconosciuti danno 400, non 404.</b> Il 404 di questo endpoint
     * ha gia' un significato — la tipologia nell'URL non esiste — e riusarlo per
     * gli id nel corpo renderebbe indistinguibili due situazioni che si
     * riparano in modo diverso. Il messaggio elenca quali id non sono buoni:
     * dire "una delle dotazioni non esiste" lascerebbe al client il compito di
     * indovinare quale.
     */
    @Override
    @Transactional
    public ApiBaseResponse impostaDotazioni(Long id, TipologiaCameraDotazioniRequest request) {
        TipologiaCamera tipologia = trovaOrElseThrow(id);

        // Il DTO generato espone un Set (uniqueItems nello spec), quindi gli id ripetuti
        // sono gia' collassati qui: mandare due volte la stessa dotazione non e' un
        // errore, e' solo un modo ridondante di chiedere la stessa cosa.
        Set<Long> idsRichiesti = request.getDotazioniIds();
        List<Dotazione> dotazioni = dotazioneRepository.findAllById(idsRichiesti);

        if (dotazioni.size() != idsRichiesti.size()) {
            throw new BadRequestException(
                    "Dotazioni inesistenti: " + elencaIdMancanti(idsRichiesti, dotazioni));
        }

        // Si svuota e si riempie la collezione esistente invece di assegnarne una nuova:
        // e' quella che Hibernate sta osservando, e sostituirla gli farebbe perdere di
        // vista le modifiche.
        tipologia.getDotazioni().clear();
        tipologia.getDotazioni().addAll(dotazioni);

        TipologiaCamera salvata = salvaGestendoLaDotazioneSparita(tipologia);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Dotazioni della tipologia aggiornate",
                tipologiaCameraMapper.toResponse(salvata));
    }

    /** Gli id richiesti che non hanno trovato riscontro, in chiaro e in ordine. */
    private String elencaIdMancanti(Set<Long> idsRichiesti, List<Dotazione> trovate) {
        Set<Long> idsTrovati = trovate.stream().map(Dotazione::getId).collect(Collectors.toSet());

        return idsRichiesti.stream()
                .filter(idRichiesto -> !idsTrovati.contains(idRichiesto))
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    /**
     * Scrive subito per poter tradurre in 400 la violazione della chiave esterna
     * verso {@code dotazione}. E' la stessa rete del controllo sui duplicati: fra
     * il {@code findAllById} qui sopra e la scrittura ci sta una richiesta che
     * cancella una di quelle dotazioni, e senza il flush esplicito l'errore
     * arriverebbe al commit — cioe' fuori da questo metodo — e tornerebbe al
     * client come 500 invece che come "quegli id non vanno piu' bene".
     */
    private TipologiaCamera salvaGestendoLaDotazioneSparita(TipologiaCamera tipologia) {
        try {
            return tipologiaCameraRepository.saveAndFlush(tipologia);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException(
                    "Una delle dotazioni indicate non esiste piu': ricarica l'elenco e riprova", ex);
        }
    }

    /**
     * Rifiuta un prezzo con piu' di due decimali.
     *
     * <p>Sembra un capriccio e non lo e': la colonna e' NUMERIC(10,2), quindi
     * Postgres arrotonderebbe 120.999 a 121.00 <b>senza dirlo a nessuno</b>. Il
     * guaio non e' l'arrotondamento in se', e' che la risposta al POST rimanda
     * l'entity che abbiamo in memoria, dove il valore e' ancora quello scritto
     * da chi chiama: la stessa risorsa direbbe 120.999 appena creata e 121.00
     * alla lettura successiva. Su un prezzo, "dipende da quando lo chiedi" non
     * e' una risposta accettabile.
     *
     * <p>Si rifiuta invece di arrotondare di nascosto: chi ha scritto tre
     * decimali ha in mente un numero, ed e' meglio dirgli che non e'
     * rappresentabile che cambiarglielo alle spalle. Come per il consenso
     * privacy, il vincolo non e' esprimibile nello schema OpenAPI — che ha
     * multipleOf, ma il generatore Java non lo traduce in nessuna annotazione —
     * quindi vive qui ed e' dichiarato nella descrizione del campo.
     */
    private void verificaPrezzo(TipologiaCameraRequest request) {
        // stripTrailingZeros prima del confronto: 120.100 vale 120.1 ed e'
        // rappresentabile, anche se scritto con tre cifre dopo la virgola.
        if (request.getPrezzoNotte().stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Il prezzo per notte non puo' avere piu' di due decimali");
        }
    }

    /** Lettura per id, con il 404 gia' pronto: e' il preambolo di tre metodi su cinque. */
    private TipologiaCamera trovaOrElseThrow(Long id) {
        return tipologiaCameraRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Tipologia di camera non trovata"));
    }

    /**
     * Copia nell'entity i campi che il client puo' decidere. Sta qui e non nel
     * mapper perche' non e' una conversione: e' l'elenco di cosa e' modificabile
     * da fuori — id e date di audit non compaiono, e non e' una dimenticanza.
     */
    private void applicaCampi(TipologiaCamera tipologia, TipologiaCameraRequest request) {
        tipologia.setNome(request.getNome());
        tipologia.setDescrizione(request.getDescrizione());
        tipologia.setCapienzaMax(request.getCapienzaMax());
        tipologia.setPrezzoNotte(request.getPrezzoNotte());
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico sul nome (vedi la nota in testa alla classe).
     * E' la rete sotto al controllo {@code existsBy}: copre la richiesta gemella
     * arrivata nel frattempo, che nessun controllo preventivo puo' vedere.
     */
    private TipologiaCamera salvaGestendoIlDuplicato(TipologiaCamera tipologia) {
        try {
            return tipologiaCameraRepository.saveAndFlush(tipologia);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Esiste gia' una tipologia di camera con questo nome", ex);
        }
    }
}
