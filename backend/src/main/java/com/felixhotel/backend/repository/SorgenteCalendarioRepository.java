package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.SorgenteCalendario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Accesso ai calendari esterni che leggiamo. */
public interface SorgenteCalendarioRepository extends JpaRepository<SorgenteCalendario, Long> {

    /**
     * L'elenco per il backoffice, con l'unico filtro che serve: la camera.
     *
     * <p>L'ordine e' per camera e poi per nome, cioe' come lo si legge: tutte le sorgenti
     * di una stanza vicine. L'id in coda perche' due canali omonimi sulla stessa camera
     * sono legittimi — sono due schede diverse dello stesso portale — e senza un
     * discriminante finale l'ordine fra loro cambierebbe di pagina in pagina.
     *
     * <p><b>La camera e la sua tipologia si caricano qui</b>: il mapper le tocca tutte e
     * due per la sintesi, e senza {@code EntityGraph} sarebbero due query per riga.
     */
    @EntityGraph(attributePaths = {"camera", "camera.tipologiaCamera"})
    @Query("""
            select s from SorgenteCalendario s
            where (:cameraId is null or s.camera.id = :cameraId)
            order by s.camera.id, s.nome, s.id
            """)
    Page<SorgenteCalendario> cerca(@Param("cameraId") Long cameraId, Pageable pageable);

    /**
     * Tutte le sorgenti, nell'ordine in cui il giro periodico le legge.
     *
     * <p><b>Restituisce solo gli id</b>, e non e' un risparmio ma una necessita': ogni
     * sorgente si sincronizza in una transazione sua, quindi le entita' caricate qui
     * sarebbero comunque staccate quando quella transazione le rilegge. Passare l'id
     * rende esplicito che a caricarla e' chi la usa.
     */
    @Query("select s.id from SorgenteCalendario s order by s.id")
    List<Long> tuttiGliId();

    /**
     * La sorgente con la camera e la tipologia gia' dentro, che e' la forma di cui ha
     * bisogno la sincronizzazione: le serve la camera per nominare i blocchi e la
     * tipologia per contare l'occupazione.
     */
    @EntityGraph(attributePaths = {"camera", "camera.tipologiaCamera"})
    @Query("select s from SorgenteCalendario s where s.id = :id")
    Optional<SorgenteCalendario> trovaConCamera(@Param("id") Long id);

    /**
     * Se quell'indirizzo e' gia' registrato su quella camera.
     *
     * <p>Controllo preventivo, non la difesa: la difesa e' l'indice unico del V17. Serve a
     * rispondere 409 con una frase invece di far tradurre al Service una violazione di
     * vincolo — la stessa divisione dei compiti dei blocchi e delle aliquote.
     */
    boolean existsByCameraIdAndUrl(Long cameraId, String url);
}
