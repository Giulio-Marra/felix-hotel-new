package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.StatoCamera;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Accesso ai dati delle camere fisiche.
 *
 * <p>Ogni lettura che finisce in una risposta carica anche la tipologia con
 * {@code @EntityGraph}: la relazione e' LAZY e il progetto ha
 * {@code open-in-view=false} (regola 15). Qui il fetch e' innocuo anche
 * sull'elenco paginato — e' un {@code ManyToOne}, quindi un join che non
 * moltiplica le righe, al contrario di quel che succederebbe con una collezione.
 */
public interface CameraRepository extends JpaRepository<Camera, Long> {

    /**
     * Elenco paginato con i due filtri facoltativi, combinabili.
     *
     * <p><b>Perche' una query sola con i null e non quattro metodi derivati.</b>
     * Due filtri opzionali fanno quattro combinazioni, e
     * {@code findAll}/{@code findByStato}/{@code findByTipologiaCameraId}/
     * {@code findByTipologiaCameraIdAndStato} vorrebbero dire quattro firme piu'
     * un {@code if} nel Service che sceglie quale chiamare — cioe' il posto dove
     * un domani si dimentica di aggiungere il terzo filtro. Il
     * {@code :parametro is null or ...} tiene la decisione dentro la query, dove
     * la si legge tutta insieme.
     *
     * @param tipologiaCameraId se null, non filtra per tipologia
     * @param stato             se null, non filtra per stato
     */
    @EntityGraph(attributePaths = "tipologiaCamera")
    @Query("""
            select c from Camera c
            where (:tipologiaCameraId is null or c.tipologiaCamera.id = :tipologiaCameraId)
              and (:stato is null or c.stato = :stato)
            """)
    Page<Camera> cerca(@Param("tipologiaCameraId") Long tipologiaCameraId,
                       @Param("stato") StatoCamera stato,
                       Pageable pageable);

    /** Lettura per id con la tipologia gia' caricata: serve a tutti i metodi che passano di qui. */
    @Override
    @EntityGraph(attributePaths = "tipologiaCamera")
    Optional<Camera> findById(Long id);

    /**
     * Usato in creazione: il numero non deve appartenere a nessun'altra camera.
     * {@code IgnoreCase} perche' il vincolo in database e' su {@code lower(numero)},
     * e due regole diverse lascerebbero passare di qui un duplicato che si
     * schianterebbe la'.
     */
    boolean existsByNumeroIgnoreCase(String numero);

    /**
     * Usato in aggiornamento: come sopra, ma escludendo la camera che si sta
     * modificando — altrimenti riconfermarle il proprio numero darebbe 409.
     */
    boolean existsByNumeroIgnoreCaseAndIdNot(String numero, Long id);
}
