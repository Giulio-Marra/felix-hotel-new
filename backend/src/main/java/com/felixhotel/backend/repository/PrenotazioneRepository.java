package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.StatoPrenotazione;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/**
 * Accesso ai dati delle prenotazioni.
 *
 * <p>Ogni lettura che finisce in una risposta carica anche cliente, tipologia e
 * personale con {@code @EntityGraph}: sono relazioni LAZY e il progetto ha
 * {@code open-in-view=false} (regola 15). Tutte e tre sono {@code ManyToOne},
 * quindi il fetch resta innocuo anche sull'elenco paginato — sono join che non
 * moltiplicano le righe, al contrario di quel che succederebbe con una
 * collezione.
 */
public interface PrenotazioneRepository extends JpaRepository<Prenotazione, Long> {

    /**
     * Elenco paginato, con l'ambito e il filtro come parametri facoltativi.
     *
     * <p><b>{@code utenteId} non e' un filtro di ricerca, e' il recinto.</b> Chi
     * chiama passa null solo se ha diritto di vedere tutto (STAFF o ADMIN); per
     * un cliente arriva sempre valorizzato col suo id, e non perche' lo abbia
     * chiesto lui. E' la stessa forma del filtro facoltativo usata per le
     * camere, ma con un significato diverso: qui il null e' un privilegio, non
     * l'assenza di una preferenza.
     *
     * @param utenteId se null, non restringe a nessun cliente
     * @param stato    se null, non filtra per stato
     */
    @EntityGraph(attributePaths = {"utente", "tipologiaCamera", "gestitaDaStaff"})
    @Query("""
            select p from Prenotazione p
            where (:utenteId is null or p.utente.id = :utenteId)
              and (:stato is null or p.stato = :stato)
            """)
    Page<Prenotazione> cerca(@Param("utenteId") Long utenteId,
                             @Param("stato") StatoPrenotazione stato,
                             Pageable pageable);

    /** Lettura per id con le relazioni gia' caricate: e' il preambolo di ogni metodo che risponde. */
    @Override
    @EntityGraph(attributePaths = {"utente", "tipologiaCamera", "gestitaDaStaff"})
    Optional<Prenotazione> findById(Long id);

    /**
     * Quante camere di una tipologia risultano gia' impegnate in un periodo.
     *
     * <p><b>E' meta' del calcolo della disponibilita'</b>: l'altra meta' e'
     * quante camere di quella tipologia esistono, che la sa
     * {@code CameraRepository}. La sottrazione la fa il service, perche' e' li'
     * che le due meta' si incontrano.
     *
     * <p><b>La sovrapposizione si scrive con disuguaglianze strette</b>, e non e'
     * un dettaglio: due periodi si accavallano quando ciascuno comincia prima
     * che l'altro finisca. Con {@code <=} il giorno di partenza risulterebbe
     * occupato, e chi arriva il 13 non potrebbe prendere la camera che qualcuno
     * libera proprio il 13 — cioe' si perderebbe una notte vendibile ad ogni
     * cambio.
     *
     * <p><b>Gli stati arrivano da fuori invece di essere scritti qui.</b> Una
     * query non puo' chiamare {@code StatoPrenotazione.occupaCamera()}, ma
     * ricopiarne l'elenco dentro la JPQL vorrebbe dire che il giorno in cui
     * nasce un sesto stato ci sono due posti da aggiornare e uno solo che se ne
     * accorge. Vedi {@code StatoPrenotazione.statiCheOccupano()}.
     *
     * @param esclusa prenotazione da non contare, o null per contarle tutte.
     *                Serve alle verifiche fatte su una prenotazione che gia'
     *                esiste: senza, una CONFERMATA riesaminata conterebbe se
     *                stessa fra quelle che le tolgono il posto
     */
    @Query("""
            select count(p) from Prenotazione p
            where p.tipologiaCamera.id = :tipologiaCameraId
              and p.stato in :statiCheOccupano
              and p.dataCheckIn < :dataCheckOut
              and p.dataCheckOut > :dataCheckIn
              and (:esclusa is null or p.id <> :esclusa)
            """)
    long contaSovrapposte(@Param("tipologiaCameraId") Long tipologiaCameraId,
                          @Param("dataCheckIn") LocalDate dataCheckIn,
                          @Param("dataCheckOut") LocalDate dataCheckOut,
                          @Param("statiCheOccupano") Collection<StatoPrenotazione> statiCheOccupano,
                          @Param("esclusa") Long esclusa);
}
