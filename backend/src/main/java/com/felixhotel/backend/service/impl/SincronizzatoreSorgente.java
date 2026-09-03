package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.entity.BloccoDisponibilita;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.SorgenteCalendario;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.EsitoSincronizzazione;
import com.felixhotel.backend.entity.enums.OrigineBlocco;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.repository.BloccoDisponibilitaRepository;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.SorgenteCalendarioRepository;
import com.felixhotel.backend.service.impl.LetturaIcs.OccupazioneEsterna;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rilegge <b>una</b> sorgente e riscrive i blocchi che ne derivano.
 *
 * <p><b>E' un bean separato dall'orchestratore per una ragione di transazioni e non di
 * ordine.</b> Ogni sorgente si sincronizza per conto suo, e i suoi due passi — riscrivere
 * i blocchi, annotare com'e' andata — devono stare in <b>due transazioni diverse</b>:
 * quando la scrittura fallisce, la sua transazione e' gia' segnata per il rollback e
 * qualunque altra cosa scritta dentro sparirebbe con lei. L'annotazione dell'esito e'
 * proprio la cosa che deve sopravvivere al fallimento — altrimenti un canale rotto resta
 * rotto in silenzio — quindi deve arrivare da fuori, cioe' attraverso il proxy di Spring,
 * cioe' da un altro bean. Chiamando {@code this.registraEsito(...)} il proxy non ci sarebbe
 * e la separazione, pur scritta, non esisterebbe.
 *
 * <p><b>Il giro, in ordine.</b> Si scarica, si legge, si buttano via gli echi del nostro
 * stesso feed e le notti gia' passate; si cancellano i blocchi che <i>questa</i> sorgente
 * aveva scritto l'ultima volta; si riscrive quel che il calendario dice adesso, saltando
 * cio' che urta un blocco di qualcun altro; e infine si guarda se quel che si e' scritto
 * abbia portato l'albergo oltre le camere che ha.
 *
 * <p><b>Cancella e riscrive invece di confrontare</b>, ed e' la decisione che tiene questa
 * classe corta: il calendario di un canale dice quel che vale adesso, e riscriverlo da
 * capo non puo' sbagliarsi, mentre confrontare gli UID uno per uno vorrebbe dire tre casi
 * — nuovo, cambiato, sparito — e la possibilita' di sbagliarne uno. Il prezzo sono
 * qualche INSERT in piu' ogni quarto d'ora su una tabella di poche righe.
 *
 * <p><b>Nessun orizzonte in avanti</b>, al contrario del feed in uscita che si ferma a
 * dodici mesi, e l'asimmetria e' voluta: quello decide quanto <i>raccontiamo</i>, questo
 * quanto <i>crediamo</i>. Se un canale ha venduto quella camera fra diciotto mesi, quella
 * camera e' venduta — ignorarlo perche' e' lontano vorrebbe dire venderla una seconda
 * volta.
 */
@Component
public class SincronizzatoreSorgente {

    /**
     * Quanti conflitti finiscono nel messaggio salvato.
     *
     * <p>Tre, e non tutti: il messaggio serve a far capire <i>cosa</i> sta succedendo, non
     * a elencarlo. Chi deve vedere l'elenco completo ha {@code GET /api/blocchi}, che e'
     * fatto per quello.
     */
    private static final int CONFLITTI_NEL_MESSAGGIO = 3;

    /**
     * Quanto puo' essere lungo un messaggio salvato, e quanto un riferimento esterno: sono
     * le due colonne del V15 e del V17 che ricevono testo <b>scritto da qualcun altro</b>.
     *
     * <p><b>Si taglia invece di lasciar fallire</b>, ed e' una correzione fatta rileggendo.
     * Un UID e' lungo quanto vuole il canale e il messaggio di un'eccezione quanto vuole la
     * libreria che l'ha sollevata: senza un tetto, un valore troppo lungo diventerebbe una
     * violazione di vincolo, e con essa un blocco non scritto — cioe' <i>una camera venduta
     * che torna in vendita</i>. Nessuno dei due valori serve a decidere qualcosa: l'UID
     * serve a ritrovare la riga del canale, il messaggio a raccontare. Tagliarli costa la
     * coda di un testo, non scriverli costa un overbooking.
     */
    private static final int LUNGHEZZA_MESSAGGIO = 1000;

    private static final int LUNGHEZZA_RIFERIMENTO = 255;

    private final SorgenteCalendarioRepository sorgenteRepository;
    private final BloccoDisponibilitaRepository bloccoRepository;
    private final PrenotazioneRepository prenotazioneRepository;
    private final CameraRepository cameraRepository;
    private final LettoreFeedRemoto lettore;
    private final Clock clock;

    @Autowired
    public SincronizzatoreSorgente(SorgenteCalendarioRepository sorgenteRepository,
                                   BloccoDisponibilitaRepository bloccoRepository,
                                   PrenotazioneRepository prenotazioneRepository,
                                   CameraRepository cameraRepository,
                                   LettoreFeedRemoto lettore) {
        this(sorgenteRepository, bloccoRepository, prenotazioneRepository, cameraRepository,
                lettore, Clock.systemDefaultZone());
    }

    /**
     * Costruttore per i test, che passando un {@link Clock} pilotabile possono decidere
     * che giorno e' senza dipendere dalla data di esecuzione — vedi
     * {@code OrologioPilotato}. Serve al filtro sulle notti passate, che e' l'unica cosa
     * qui dentro a dipendere dall'oggi: con l'orologio di sistema, un test scritto su date
     * fisse comincerebbe a fallire da solo quando quelle date diventano passato.
     */
    public SincronizzatoreSorgente(SorgenteCalendarioRepository sorgenteRepository,
                                   BloccoDisponibilitaRepository bloccoRepository,
                                   PrenotazioneRepository prenotazioneRepository,
                                   CameraRepository cameraRepository,
                                   LettoreFeedRemoto lettore,
                                   Clock clock) {
        this.sorgenteRepository = sorgenteRepository;
        this.bloccoRepository = bloccoRepository;
        this.prenotazioneRepository = prenotazioneRepository;
        this.cameraRepository = cameraRepository;
        this.lettore = lettore;
        this.clock = clock;
    }

    /**
     * Rilegge la sorgente e riscrive i suoi blocchi.
     *
     * <p><b>Solleva</b> quando il calendario non si e' potuto leggere: e' l'orchestratore
     * a tradurlo in un esito {@code ERRORE}, perche' a quel punto questa transazione non
     * puo' piu' scrivere niente.
     */
    @Transactional
    public EsitoSorgente sincronizza(Long sorgenteId) {
        SorgenteCalendario sorgente = sorgenteRepository.trovaConCamera(sorgenteId)
                .orElseThrow(() -> new NotFoundException("Sorgente non trovata"));

        Camera camera = sorgente.getCamera();
        TipologiaCamera tipologia = camera.getTipologiaCamera();

        List<OccupazioneEsterna> occupazioni = daImportare(lettore.scarica(sorgente.getUrl()));

        bloccoRepository.cancellaDellaSorgente(sorgenteId);

        List<String> conflitti = new ArrayList<>();
        List<OccupazioneEsterna> scritte = new ArrayList<>();

        for (OccupazioneEsterna occupazione : occupazioni) {
            if (bloccoRepository.esisteSovrapposizioneSuCamera(
                    camera.getId(), occupazione.inizio(), occupazione.fine(), null)) {
                conflitti.add("la camera " + camera.getNumero() + " risulta venduta dal "
                        + occupazione.inizio() + " al " + occupazione.fine()
                        + ", ma in quelle notti ha gia' un altro blocco");
                continue;
            }
            bloccoRepository.saveAndFlush(blocco(sorgente, camera, tipologia, occupazione));
            scritte.add(occupazione);
        }

        conflitti.addAll(sovravendite(tipologia, camera, scritte));

        return new EsitoSorgente(
                conflitti.isEmpty() ? EsitoSincronizzazione.OK : EsitoSincronizzazione.CONFLITTI,
                messaggio(conflitti),
                scritte.size());
    }

    /**
     * Annota com'e' andato il giro sulla riga della sorgente.
     *
     * <p><b>Transazione propria, sempre</b>, anche quando il giro e' andato bene: la
     * regola vale piu' dell'eccezione, e un'annotazione che si scrive in due modi diversi
     * a seconda dell'esito e' un'annotazione che prima o poi non si scrive.
     */
    @Transactional
    public void registraEsito(Long sorgenteId, EsitoSincronizzazione esito, String messaggio) {
        sorgenteRepository.findById(sorgenteId).ifPresent(sorgente -> {
            sorgente.setUltimaSincronizzazione(LocalDateTime.now(clock));
            sorgente.setUltimoEsito(esito);
            sorgente.setUltimoMessaggio(tagliato(messaggio, LUNGHEZZA_MESSAGGIO));
            sorgenteRepository.save(sorgente);
        });
    }

    /**
     * Le occupazioni del calendario che ci riguardano davvero.
     *
     * <p><b>Due filtri, e il primo e' quello che evita il guasto peggiore di questo
     * branch.</b> Diversi canali ripubblicano nel proprio calendario in uscita anche le
     * occupazioni lette dal nostro, conservandone l'UID come vuole la specifica: senza
     * riconoscerle, una nostra prenotazione tornerebbe indietro come blocco, la camera
     * risulterebbe occupata due volte e ogni giro segnalerebbe un overbooking inventato
     * da noi. E' un anello che si chiude in silenzio, quindi si spezza qui.
     *
     * <p>Il secondo butta via le notti gia' passate: non si possono vendere comunque, e
     * tenerle vorrebbe dire una tabella che cresce per sempre. Un periodo cominciato ieri
     * e non ancora finito resta intero — accorciarlo non servirebbe a niente e renderebbe
     * il blocco diverso da quel che il canale dice.
     */
    private List<OccupazioneEsterna> daImportare(String testo) {
        LocalDate oggi = LocalDate.now(clock);

        return LetturaIcs.occupazioni(testo).stream()
                .filter(occupazione -> occupazione.uid() == null
                        || !occupazione.uid().endsWith(CalendarioIcs.DOMINIO_UID))
                .filter(occupazione -> occupazione.fine().isAfter(oggi))
                .toList();
    }

    private BloccoDisponibilita blocco(SorgenteCalendario sorgente, Camera camera,
                                       TipologiaCamera tipologia, OccupazioneEsterna occupazione) {
        BloccoDisponibilita blocco = new BloccoDisponibilita();
        blocco.setTipologiaCamera(tipologia);
        blocco.setCamera(camera);
        blocco.setDataInizio(occupazione.inizio());
        blocco.setDataFine(occupazione.fine());
        blocco.setOrigine(OrigineBlocco.CANALE_ESTERNO);
        blocco.setSorgenteCalendario(sorgente);
        blocco.setRiferimentoEsterno(tagliato(occupazione.uid(), LUNGHEZZA_RIFERIMENTO));
        blocco.setNote("Venduta su " + sorgente.getNome());
        return blocco;
    }

    /** Vedi {@link #LUNGHEZZA_MESSAGGIO}: si taglia invece di lasciar fallire la scrittura. */
    private static String tagliato(String testo, int lunghezza) {
        if (testo == null || testo.length() <= lunghezza) {
            return testo;
        }
        return testo.substring(0, lunghezza);
    }

    /**
     * Dove quel che si e' appena scritto porta l'albergo oltre le camere che ha.
     *
     * <p><b>Si guarda dopo aver scritto e non prima</b>, ed e' la decisione presa aprendo
     * il branch: quando un canale ci dice di aver venduto, l'overbooking <i>e' gia'
     * avvenuto</i> — rifiutare il blocco non lo annulla, nasconde solo che sia successo, e
     * lascia l'albergo a rivendere una camera che qualcuno ha gia' pagato. Il sistema puo'
     * solo dirlo, e per dirlo deve prima crederci.
     *
     * <p>Una domanda per periodo scritto, e non una sola su tutto l'orizzonte: sapere
     * <i>quando</i> l'albergo e' pieno oltre misura e' l'unica cosa che renda il messaggio
     * utile a chi deve rimediare.
     */
    private List<String> sovravendite(TipologiaCamera tipologia, Camera camera,
                                      List<OccupazioneEsterna> scritte) {
        if (scritte.isEmpty()) {
            return List.of();
        }

        long camereDellaTipologia = cameraRepository.countByTipologiaCameraId(tipologia.getId());
        List<String> conflitti = new ArrayList<>();

        for (OccupazioneEsterna occupazione : scritte) {
            long occupate = prenotazioneRepository.occupazioneMassimaDi(
                    tipologia.getId(), occupazione.inizio(), occupazione.fine(),
                    StatoPrenotazione.nomiCheOccupano(), null);

            if (occupate > camereDellaTipologia) {
                conflitti.add("la camera " + camera.getNumero() + " risulta venduta dal "
                        + occupazione.inizio() + " al " + occupazione.fine()
                        + ", ma in quelle notti la tipologia " + tipologia.getNome()
                        + " e' occupata per " + occupate + " unita' su " + camereDellaTipologia);
            }
        }
        return conflitti;
    }

    /** I primi conflitti, piu' il conto di quelli che non ci stanno. Null se non ce n'e'. */
    private String messaggio(List<String> conflitti) {
        if (conflitti.isEmpty()) {
            return null;
        }

        String testo = String.join("; ", conflitti.stream().limit(CONFLITTI_NEL_MESSAGGIO).toList());
        int restanti = conflitti.size() - CONFLITTI_NEL_MESSAGGIO;

        return restanti > 0 ? testo + "; e altri " + restanti : testo;
    }

    /**
     * Com'e' andato il giro su una sorgente.
     *
     * @param esito           cosa deve fare chi legge
     * @param messaggio       i conflitti in parole, o null se non ce n'e'
     * @param blocchiScritti  quante occupazioni sono finite in blocchi
     */
    public record EsitoSorgente(EsitoSincronizzazione esito, String messaggio, int blocchiScritti) {
    }
}
