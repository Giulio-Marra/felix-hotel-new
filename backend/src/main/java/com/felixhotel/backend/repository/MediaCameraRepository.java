package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.MediaCamera;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Accesso ai dati delle foto delle tipologie di camera.
 *
 * <p><b>Nessun {@code @EntityGraph} qui</b>, a differenza degli altri
 * repository del progetto: la risposta non contiene la tipologia — chi chiama
 * l'ha appena scritta nell'URL — quindi la relazione LAZY di
 * {@link MediaCamera#getTipologiaCamera()} non viene mai letta e non c'e'
 * niente da precaricare. La regola 15 dice di caricare cio' che la risposta
 * usera': qui la risposta non usa niente.
 *
 * <p>Ogni metodo prende l'id della tipologia, e non e' una comodita': una foto
 * si indirizza sempre dentro la sua galleria, quindi <b>non esiste</b> una
 * lettura per solo id — averla vorrebbe dire poter cancellare la foto di una
 * tipologia sbagliando l'URL di un'altra.
 */
public interface MediaCameraRepository extends JpaRepository<MediaCamera, Long> {

    /**
     * La galleria di una tipologia, nell'ordine in cui va mostrata.
     *
     * <p>Lo spareggio sull'id non e' decorativo: {@code ordine} non ha un indice
     * unico (vedi {@link MediaCamera#getOrdine()}), quindi due foto potrebbero
     * finire sullo stesso numero. Ordinare per il solo {@code ordine}
     * lascerebbe in quel caso la loro posizione reciproca decisa dal database,
     * cioe' potenzialmente diversa da una chiamata all'altra — una galleria che
     * cambia da sola ogni volta che si ricarica la pagina.
     */
    List<MediaCamera> findByTipologiaCameraIdOrderByOrdineAscIdAsc(Long tipologiaCameraId);

    /**
     * Una singola foto, ma solo se appartiene alla tipologia indicata.
     *
     * <p>Il secondo parametro non e' una comodita' del chiamante: e' <b>la</b>
     * regola di questa sottorisorsa espressa dove non si puo' dimenticare di
     * applicarla. Con un {@code findById} normale, il controllo
     * "e' davvero di questa tipologia?" resterebbe un {@code if} nel Service,
     * cioe' una riga che qualcuno un domani puo' non scrivere; e per farlo
     * dovrebbe leggere {@code media.getTipologiaCamera().getId()}, che su una
     * relazione LAZY funziona solo grazie a un dettaglio di come Hibernate
     * costruisce i proxy. Cosi' invece la condizione sta nella query, e una foto
     * di un'altra galleria semplicemente non si trova.
     */
    Optional<MediaCamera> findByIdAndTipologiaCameraId(Long id, Long tipologiaCameraId);

    /**
     * Usato in aggiunta: la stessa immagine non deve essere gia' in questa
     * galleria. Il confronto e' esatto — niente {@code IgnoreCase} — perche' il
     * vincolo in database e' su {@code (tipologia_camera_id, url)} senza
     * {@code lower()}, e due regole diverse lascerebbero passare di qui un
     * duplicato che si schianterebbe la' (vedi V5).
     */
    boolean existsByTipologiaCameraIdAndUrl(Long tipologiaCameraId, String url);

    /** Usato in aggiunta per far rispettare il tetto di foto per tipologia. */
    long countByTipologiaCameraId(Long tipologiaCameraId);

    /**
     * La posizione piu' alta gia' occupata nella galleria, o {@code -1} se la
     * galleria e' vuota: chi aggiunge una foto la mette a questo valore piu' uno,
     * cosi' la prima nasce a zero.
     *
     * <p>Il massimo e non il conteggio: eliminare una foto lascia dei buchi, e
     * con {@code count} la successiva andrebbe a una posizione gia' occupata —
     * finendo a pari merito con una che c'e' gia' invece che in fondo.
     */
    @Query("""
            select coalesce(max(m.ordine), -1) from MediaCamera m
            where m.tipologiaCamera.id = :tipologiaCameraId
            """)
    int massimoOrdine(@Param("tipologiaCameraId") Long tipologiaCameraId);
}
