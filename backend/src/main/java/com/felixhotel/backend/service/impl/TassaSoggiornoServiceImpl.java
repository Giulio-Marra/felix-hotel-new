package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.TassaSoggiornoOspite;
import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TassaSoggiornoMapper;
import com.felixhotel.backend.repository.AliquotaTassaSoggiornoRepository;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.security.AccessoPrenotazioni;
import com.felixhotel.backend.service.TassaSoggiornoService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Il calcolo della tassa di soggiorno.
 *
 * <p><b>Sta in Java e non in una query nativa</b>, al contrario del calcolo del
 * prezzo, e la differenza e' deliberata. Il prezzo e' finito in SQL per due
 * ragioni che qui non valgono: aveva <i>due</i> consumatori — la ricerca e la
 * creazione — e due formule divergenti avrebbero fatto dire un numero alla pagina
 * di ricerca e un altro alla fattura; e doveva impaginare, perche' il filtro di
 * prezzo esclude righe. La tassa ha un consumatore solo e si calcola su una
 * prenotazione per volta. In compenso ha quello che al prezzo mancava: <b>rami
 * veri</b> — tre specie di esenzione, il tetto di notti, l'aliquota che manca —
 * cioe' esattamente cio' che un test unitario sa guardare e una query nativa no.
 *
 * <p><b>Il calcolo e' notte per notte, e non una moltiplicazione.</b> Verrebbe
 * spontaneo scrivere {@code notti * importo * persone}, e sarebbe sbagliato in due
 * casi che capitano davvero: un soggiorno a cavallo di un cambio di aliquota — chi
 * arriva il 30 giugno e parte il 3 luglio paga due tariffe diverse — e un soggiorno
 * che entra ed esce da un periodo coperto, dove alcune notti non si pagano affatto.
 * Scorrere le notti costa poco perche' un soggiorno non puo' superare
 * {@code DurataSoggiorno.MASSIMO_NOTTI}: quel tetto e' un prerequisito di questo
 * calcolo come lo era della query dei prezzi.
 *
 * <p><b>Le tre esenzioni non sono la stessa cosa e non si trattano allo stesso
 * modo</b>:
 * <ul>
 *   <li><b>il motivo dichiarato</b> — residente, disabile, in servizio — esenta la
 *       persona per l'intero soggiorno, e si legge sull'ospite. Il sistema non lo
 *       puo' dedurre da niente;</li>
 *   <li><b>l'eta'</b> esenta anche lei la persona per intero, ma il sistema la
 *       calcola, e la calcola <b>notte per notte</b> perche' l'eta' di esenzione e'
 *       un campo dell'aliquota: due aliquote che si susseguono possono avere due
 *       soglie diverse;</li>
 *   <li><b>il tetto di notti</b> non esenta la persona ma <b>alcune delle sue
 *       notti</b>, ed e' il motivo per cui non ha un campo suo nella risposta: si
 *       legge confrontando le notti tassate con quelle del soggiorno.</li>
 * </ul>
 *
 * <p><b>Il tetto si conta sulle notti tassate e non su quelle passate.</b> Con un
 * tetto di cinque notti, chi ne fa otto di cui tre non coperte da nessuna aliquota
 * ne paga cinque e non due: le notti scoperte non consumano il tetto, perche' il
 * tetto e' un limite a quanto si paga e non un calendario. E' la lettura che
 * favorisce il comune quando le due sono in disaccordo, ed e' anche quella che i
 * regolamenti scrivono ("per un massimo di cinque pernottamenti soggetti a
 * imposta").
 */
@Service
public class TassaSoggiornoServiceImpl implements TassaSoggiornoService {

    private final OspiteRepository ospiteRepository;
    private final AliquotaTassaSoggiornoRepository aliquotaRepository;
    private final TassaSoggiornoMapper tassaSoggiornoMapper;
    private final ApiResponseMapper apiResponseMapper;
    private final AccessoPrenotazioni accessoPrenotazioni;

    public TassaSoggiornoServiceImpl(OspiteRepository ospiteRepository,
                                     AliquotaTassaSoggiornoRepository aliquotaRepository,
                                     TassaSoggiornoMapper tassaSoggiornoMapper,
                                     ApiResponseMapper apiResponseMapper,
                                     AccessoPrenotazioni accessoPrenotazioni) {
        this.ospiteRepository = ospiteRepository;
        this.aliquotaRepository = aliquotaRepository;
        this.tassaSoggiornoMapper = tassaSoggiornoMapper;
        this.apiResponseMapper = apiResponseMapper;
        this.accessoPrenotazioni = accessoPrenotazioni;
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse calcola(Long prenotazioneId) {
        Prenotazione prenotazione = accessoPrenotazioni.visibileOrElseThrow(prenotazioneId);

        List<LocalDate> notti = nottiDel(prenotazione);
        List<AliquotaTassaSoggiorno> aliquote = notti.isEmpty()
                ? List.of()
                : aliquotaRepository.trovaPerSoggiorno(notti.getFirst(), notti.getLast());

        List<Ospite> ospiti = ospiteRepository.findByPrenotazioneIdOrderByIdAsc(prenotazioneId);

        List<TassaSoggiornoOspite> righe = ospiti.stream()
                .map(ospite -> riga(ospite, notti, aliquote, prenotazione.getDataCheckIn()))
                .toList();

        return apiResponseMapper.toResponse(HttpStatus.OK, "Tassa di soggiorno calcolata",
                tassaSoggiornoMapper.toResponse(righe, notti.size(),
                        nottiNonCoperte(notti, aliquote)));
    }

    /**
     * Le notti del soggiorno: dall'arrivo alla vigilia della partenza.
     *
     * <p><b>Il giorno di partenza non e' una notte</b>, ed e' la stessa aritmetica
     * con cui il progetto conta la disponibilita' e i prezzi: chi arriva il 10 e
     * parte il 13 dorme le notti del 10, dell'11 e del 12. Ripeterla invece di
     * inventarne un'altra e' cio' che permette di leggere insieme il conto della
     * camera e quello della tassa.
     */
    private List<LocalDate> nottiDel(Prenotazione prenotazione) {
        List<LocalDate> notti = new ArrayList<>();
        for (LocalDate notte = prenotazione.getDataCheckIn();
             notte.isBefore(prenotazione.getDataCheckOut());
             notte = notte.plusDays(1)) {
            notti.add(notte);
        }
        return notti;
    }

    /**
     * Il conto di una persona.
     *
     * <p><b>Il motivo dichiarato si guarda per primo e chiude il discorso</b>: chi
     * e' esente per residenza non paga nemmeno le notti che un'aliquota copre, e
     * scorrerle sarebbe lavoro per arrivare a zero. E' anche l'ordine giusto per
     * chi legge il codice: la domanda "questa persona paga?" viene prima di "quanto
     * paga?".
     */
    private TassaSoggiornoOspite riga(Ospite ospite,
                                      List<LocalDate> notti,
                                      List<AliquotaTassaSoggiorno> aliquote,
                                      LocalDate dataArrivo) {
        if (ospite.getMotivoEsenzione() != null) {
            return tassaSoggiornoMapper.toOspite(ospite, 0, BigDecimal.ZERO, false);
        }

        BigDecimal dovuto = BigDecimal.ZERO;
        int nottiTassate = 0;
        boolean esentePerEta = false;

        for (LocalDate notte : notti) {
            AliquotaTassaSoggiorno aliquota = aliquotaDi(notte, aliquote);
            if (aliquota == null) {
                // Notte che nessuna aliquota copre: non si paga, e non consuma il
                // tetto. Non esiste nessuna "tassa di listino" a cui ricadere.
                continue;
            }

            if (esentePerEta(ospite, aliquota, dataArrivo)) {
                // Vero per tutto il soggiorno se l'eta' di esenzione non cambia, ma
                // si valuta comunque per notte: due aliquote che si susseguono
                // possono avere due soglie diverse, e chi e' esente sotto la prima
                // puo' non esserlo sotto la seconda.
                esentePerEta = true;
                continue;
            }

            if (tettoRaggiunto(aliquota, nottiTassate)) {
                continue;
            }

            dovuto = dovuto.add(aliquota.getImportoPerPersonaNotte());
            nottiTassate++;
        }

        return tassaSoggiornoMapper.toOspite(ospite, nottiTassate, dovuto, esentePerEta);
    }

    /**
     * L'aliquota che copre questa notte, o {@code null} se nessuna la copre.
     *
     * <p>Una ricerca lineare su una lista invece di una mappa per data, e non e'
     * una svista: le aliquote che toccano un soggiorno sono <b>pochissime</b> —
     * spesso una, al massimo due o tre se il soggiorno attraversa un cambio — e una
     * mappa costruita per l'occasione costerebbe piu' di quel che risparmia. Se un
     * giorno le aliquote diventassero molte per soggiorno, vorrebbe dire che il
     * comune le cambia ogni settimana, e allora il problema sarebbe un altro.
     *
     * <p><b>Al massimo una puo' corrispondere</b>, e non e' fortuna: lo garantisce
     * il vincolo di esclusione del V11. Se un giorno venisse tolto, questo metodo
     * comincerebbe a restituire la prima delle due senza dirlo a nessuno.
     */
    private AliquotaTassaSoggiorno aliquotaDi(LocalDate notte, List<AliquotaTassaSoggiorno> aliquote) {
        return aliquote.stream()
                .filter(a -> !notte.isBefore(a.getDataInizio()) && !notte.isAfter(a.getDataFine()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Se questa persona e' sotto l'eta' di esenzione dell'aliquota.
     *
     * <p><b>L'eta' si valuta alla data di arrivo e non alla notte</b>, ed e' una
     * scelta e non una semplificazione: e' la stessa data su cui si decide se serve
     * il documento (il V10), e usarne due diverse vorrebbe dire che un bambino che
     * compie gli anni durante il soggiorno cambia stato a meta' — con un conto che
     * nessuno al banco saprebbe rifare a mente. Chi arriva minorenne passa il
     * soggiorno da minorenne.
     *
     * <p>Il confronto e' fra date e non fra numeri di anni, come in
     * {@code OspiteServiceImpl.maggiorenneAllArrivo}: la data di nascita piu' l'eta'
     * di esenzione cade il giorno del compleanno, e chi arriva quel giorno l'eta' ce
     * l'ha gia' — quindi paga.
     */
    private boolean esentePerEta(Ospite ospite, AliquotaTassaSoggiorno aliquota, LocalDate dataArrivo) {
        Integer etaEsenzione = aliquota.getEtaEsenzione();
        if (etaEsenzione == null) {
            return false;
        }

        return ospite.getDataNascita().plusYears(etaEsenzione).isAfter(dataArrivo);
    }

    /**
     * Se questa persona ha gia' pagato tutte le notti che l'aliquota tassa.
     *
     * <p>Si conta sulle <b>notti tassate</b> e non su quelle trascorse: le notti
     * scoperte e quelle esenti non consumano il tetto. Vedi il javadoc della classe
     * per il perche' e' questa la lettura giusta.
     */
    private boolean tettoRaggiunto(AliquotaTassaSoggiorno aliquota, int nottiTassate) {
        Integer tetto = aliquota.getNottiMassimeTassate();
        return tetto != null && nottiTassate >= tetto;
    }

    /**
     * Quante notti del soggiorno nessuna aliquota copriva.
     *
     * <p>Non serve al calcolo — quelle notti sono gia' state saltate — ma a chi
     * legge la risposta: un totale a zero puo' voler dire "qui non si paga" oppure
     * "nessuno ha ancora configurato le aliquote", e senza questo numero i due casi
     * sarebbero indistinguibili. E' la stessa ragione per cui la ricerca di
     * disponibilita' restituisce anche le tipologie esaurite con zero camere.
     */
    private int nottiNonCoperte(List<LocalDate> notti, List<AliquotaTassaSoggiorno> aliquote) {
        return (int) notti.stream().filter(notte -> aliquotaDi(notte, aliquote) == null).count();
    }
}
