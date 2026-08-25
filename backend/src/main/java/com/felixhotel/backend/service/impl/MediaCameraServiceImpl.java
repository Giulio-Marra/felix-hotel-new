package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.entity.MediaCamera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.MediaCameraMapper;
import com.felixhotel.backend.repository.MediaCameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.MediaCameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementazione della galleria fotografica di una tipologia di camera.
 *
 * <p>Ricalca gli altri CRUD del progetto dove puo' — controllo preventivo sul
 * duplicato piu' {@code saveAndFlush} dentro il try, per tradurre in 409 anche
 * la richiesta gemella arrivata nel frattempo — ma ha una cosa che nessun altro
 * service qui ha ancora avuto: <b>una sequenza da mantenere</b>. Da li' vengono
 * le due decisioni che spiegano quasi tutto il file.
 *
 * <p><b>La prima: l'ordine si riscrive tutto insieme, mai a pezzi.</b>
 * {@link #riordina} riceve la galleria completa e riassegna le posizioni da
 * zero. L'alternativa — "sposta questa foto in su" — sembra piu' piccola ed e'
 * peggiore: ogni spostamento e' un leggi-modifica-scrivi su piu' righe, cinque
 * di fila per una foto trascinata dal fondo alla testa, e ognuno e' un punto in
 * cui fermarsi lasciando la lista in un ordine che non ha voluto nessuno.
 *
 * <p><b>La seconda: il riordino pretende l'elenco esatto</b>, e rifiuta invece
 * di arrangiarsi. Se fra il momento in cui il client ha letto la galleria e
 * quello in cui manda il nuovo ordine qualcuno ha aggiunto o tolto una foto, la
 * sequenza ricevuta descrive una galleria che non esiste piu': applicarla per la
 * parte che combacia metterebbe le altre foto dove capita. Il 400 e' l'unica
 * risposta che il client puo' davvero rimediare — rilegge e rimanda — e rende
 * l'operazione un controllo di concorrenza a costo zero, senza nessun campo di
 * versione.
 */
@Service
@RequiredArgsConstructor
public class MediaCameraServiceImpl implements MediaCameraService {

    /**
     * Quante foto puo' avere una tipologia.
     *
     * <p>Non e' un'idea di quante ne servano — trenta sono gia' tante per una
     * scheda — ma il motivo per cui {@link #elenca} puo' permettersi di non
     * essere paginato: e' il tetto che rende la galleria una lista di dimensione
     * nota invece che una tabella intera restituita in un colpo. E' lo stesso
     * numero scritto come {@code maxItems} su {@code MediaCameraOrdineRequest}
     * nello spec, dove limita la richiesta di riordino: sono lo stesso limite
     * visto dai due lati, e vanno cambiati insieme.
     *
     * <p>{@code public} perche' il test lo importa invece di riscrivere il
     * numero: due copie di un limite sono due limiti, e la seconda smette di
     * essere vera il giorno che si cambia la prima. E' l'unica costante esposta
     * da un Impl del progetto, e lo e' per quello.
     */
    public static final int MASSIMO_FOTO_PER_TIPOLOGIA = 30;

    private final MediaCameraRepository mediaCameraRepository;

    /** Serve a risolvere la tipologia del percorso, in lettura e in scrittura. */
    private final TipologiaCameraRepository tipologiaCameraRepository;

    private final MediaCameraMapper mediaCameraMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * La galleria, gia' ordinata dalla query.
     *
     * <p>Una tipologia senza foto risponde 200 con una lista vuota, una che non
     * esiste risponde 404: e' il motivo per cui qui c'e' un controllo di
     * esistenza invece di restituire semplicemente quel che la query trova.
     * Senza, chiedere le foto di una tipologia inesistente darebbe una lista
     * vuota — indistinguibile da una scheda senza immagini, che e' proprio la
     * differenza che chi legge il catalogo deve poter vedere.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse elenca(Long tipologiaCameraId) {
        assicuraTipologiaEsistente(tipologiaCameraId);

        List<MediaCamera> galleria =
                mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(tipologiaCameraId);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Foto della tipologia recuperate",
                mediaCameraMapper.toResponseList(galleria));
    }

    /**
     * Aggiunge una foto in coda.
     *
     * <p>La posizione non si sceglie: e' il massimo gia' occupato piu' uno. Una
     * foto nuova va in fondo perche' e' l'unica cosa che si possa dedurre senza
     * chiederlo, e chiederlo qui vorrebbe dire far calcolare al client un numero
     * che poi non puo' nemmeno rileggere — la posizione non compare in nessuna
     * risposta. Chi la vuole altrove usa l'endpoint di riordino.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiungi(Long tipologiaCameraId, MediaCameraRequest request) {
        TipologiaCamera tipologia = trovaTipologiaOrElseThrow(tipologiaCameraId);

        // Il duplicato prima del tetto, non per caso: se la foto c'e' gia', "e' gia'
        // in galleria" e' la risposta utile — dire "galleria piena" a chi sta
        // ricaricando un'immagine che c'e' gia' lo manderebbe a cancellarne un'altra
        // per fare spazio a una che non serve.
        if (mediaCameraRepository.existsByTipologiaCameraIdAndUrl(tipologiaCameraId, request.getUrl())) {
            throw new ConflictException("Questa foto e' gia' nella galleria della tipologia");
        }

        if (mediaCameraRepository.countByTipologiaCameraId(tipologiaCameraId) >= MASSIMO_FOTO_PER_TIPOLOGIA) {
            throw new ConflictException(
                    "La tipologia ha gia' il numero massimo di foto: " + MASSIMO_FOTO_PER_TIPOLOGIA);
        }

        MediaCamera media = new MediaCamera();
        media.setTipologiaCamera(tipologia);
        media.setUrl(request.getUrl());
        media.setOrdine(mediaCameraRepository.massimoOrdine(tipologiaCameraId) + 1);

        MediaCamera salvata = salvaGestendoIlDuplicato(media);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Foto aggiunta alla tipologia",
                mediaCameraMapper.toResponse(salvata));
    }

    /**
     * Elimina una foto dalla galleria.
     *
     * <p>Nessuna rinumerazione dopo: togliere la terza di cinque lascia le altre
     * a 0, 1, 3, 4 e l'ordine in cui si leggono e' esattamente quello di prima.
     * Riscrivere quattro righe per ricompattare dei numeri che non escono da
     * nessuna risposta sarebbe lavoro fatto per un'estetica che nessuno puo'
     * vedere.
     *
     * <p>Nessun {@code flush} dentro un try, a differenza di camere e tipologie,
     * perche' non c'e' nessuna violazione da tradurre in 409: verso
     * {@code media_camera} non punta nessuna chiave esterna. Se un domani ne
     * nascesse una — dei commenti su una foto, delle miniature — questo metodo
     * dovrebbe tornare a somigliare agli altri, ed e' lo stesso debito gia'
     * aperto su {@code DotazioneServiceImpl.elimina}.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long tipologiaCameraId, Long mediaId) {
        // Il controllo sulla tipologia c'e' anche se la query qui sotto fallirebbe
        // comunque: serve al messaggio. Con il solo findByIdAndTipologiaCameraId, un
        // id di tipologia sbagliato direbbe "foto non trovata" a chi ha una foto
        // valida in mano, mandandolo a cercare il problema dalla parte sbagliata.
        assicuraTipologiaEsistente(tipologiaCameraId);

        MediaCamera media = mediaCameraRepository.findByIdAndTipologiaCameraId(mediaId, tipologiaCameraId)
                .orElseThrow(() -> new NotFoundException("Foto non trovata"));

        mediaCameraRepository.delete(media);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Foto eliminata", null);
    }

    /**
     * Riscrive l'intera sequenza: la posizione di ogni foto diventa il suo indice
     * nell'elenco ricevuto.
     *
     * <p>Le posizioni ripartono da zero e restano contigue, quindi il riordino e'
     * anche l'unica operazione che ricompatta i buchi lasciati dalle
     * eliminazioni. E' un effetto collaterale e non un servizio offerto: nessuno
     * puo' osservare la differenza.
     */
    @Override
    @Transactional
    public ApiBaseResponse riordina(Long tipologiaCameraId, MediaCameraOrdineRequest request) {
        assicuraTipologiaEsistente(tipologiaCameraId);

        List<MediaCamera> galleria =
                mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(tipologiaCameraId);
        Map<Long, MediaCamera> perId = galleria.stream()
                .collect(Collectors.toMap(MediaCamera::getId, Function.identity()));

        List<Long> richiesti = request.getMediaIds();
        verificaSequenzaCompleta(richiesti, perId.keySet());

        List<MediaCamera> riordinata = new ArrayList<>(richiesti.size());
        for (int posizione = 0; posizione < richiesti.size(); posizione++) {
            MediaCamera media = perId.get(richiesti.get(posizione));
            media.setOrdine(posizione);
            riordinata.add(media);
        }

        mediaCameraRepository.saveAll(riordinata);

        // La risposta esce nell'ordine appena imposto, che e' quello della richiesta:
        // non si rilegge dal database. Rileggere darebbe la stessa lista passando da
        // una query in piu' — e sarebbe l'unico punto del metodo in cui il client
        // potrebbe vedere qualcosa di diverso da quel che ha chiesto.
        return apiResponseMapper.toResponse(HttpStatus.OK, "Ordine delle foto aggiornato",
                mediaCameraMapper.toResponseList(riordinata));
    }

    /**
     * Verifica che l'elenco ricevuto sia una permutazione esatta della galleria:
     * stessi id, tutti, una volta sola.
     *
     * <p>I due errori sono tenuti distinti perche' si riparano in modo diverso.
     * Un <b>duplicato</b> e' un client che sbaglia a costruire la richiesta: la
     * stessa foto non puo' stare in due posizioni, e non c'e' niente da
     * rileggere. Un elenco che <b>non combacia</b> e' invece quasi sempre una
     * galleria cambiata sotto i piedi, e il rimedio e' rileggerla — quindi il
     * messaggio dice cosa manca e cosa avanza, che e' l'informazione con cui
     * capire cos'e' successo.
     */
    private void verificaSequenzaCompleta(List<Long> richiesti, Set<Long> presenti) {
        Set<Long> senzaRipetizioni = new LinkedHashSet<>(richiesti);
        if (senzaRipetizioni.size() != richiesti.size()) {
            throw new BadRequestException(
                    "L'elenco contiene la stessa foto piu' di una volta: una foto ha una posizione sola");
        }

        Set<Long> mancanti = new LinkedHashSet<>(presenti);
        mancanti.removeAll(senzaRipetizioni);

        Set<Long> estranei = new LinkedHashSet<>(senzaRipetizioni);
        estranei.removeAll(presenti);

        if (!mancanti.isEmpty() || !estranei.isEmpty()) {
            throw new BadRequestException(
                    "L'elenco deve contenere esattamente le foto della tipologia."
                            + " Mancanti: " + mancanti
                            + ", non appartenenti alla galleria: " + estranei);
        }
    }

    /**
     * 404 se la tipologia del percorso non esiste, senza caricarne niente: e' il
     * preambolo di tutti e quattro i metodi.
     *
     * <p>{@code existsById} e non {@code findById}: a questi tre metodi della
     * tipologia non serve nient'altro che sapere che c'e', e {@code findById} si
     * porterebbe dietro le dotazioni per via del suo {@code @EntityGraph}.
     */
    private void assicuraTipologiaEsistente(Long tipologiaCameraId) {
        if (!tipologiaCameraRepository.existsById(tipologiaCameraId)) {
            throw new NotFoundException("Tipologia di camera non trovata");
        }
    }

    /**
     * Come sopra, ma restituendo l'entita': serve solo all'aggiunta, che deve
     * valorizzare la chiave esterna della foto.
     *
     * <p>Passa da {@code trovaSenzaCollezioni} e non da {@code findById} perche'
     * di questa tipologia servira' l'identificativo e basta — le sue dotazioni
     * non finiscono in nessuna risposta di questo service.
     */
    private TipologiaCamera trovaTipologiaOrElseThrow(Long tipologiaCameraId) {
        return tipologiaCameraRepository.trovaSenzaCollezioni(tipologiaCameraId)
                .orElseThrow(() -> new NotFoundException("Tipologia di camera non trovata"));
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico su (tipologia, url). E' la rete sotto al
     * controllo {@code existsBy}: copre la richiesta gemella arrivata nel
     * frattempo, che nessun controllo preventivo puo' vedere.
     *
     * <p>Il tetto sul numero di foto, invece, <b>non</b> ha una rete: nessun
     * vincolo di database sa contare le righe di un gruppo, quindi due aggiunte
     * simultanee sulla trentesima posizione possono portare a trentuno foto. E'
     * accettato: quel limite serve a impedire che una galleria diventi
     * illimitata, non a difendere un invariante — e trentuno foto non rompono
     * niente.
     */
    private MediaCamera salvaGestendoIlDuplicato(MediaCamera media) {
        try {
            return mediaCameraRepository.saveAndFlush(media);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Questa foto e' gia' nella galleria della tipologia", ex);
        }
    }
}
