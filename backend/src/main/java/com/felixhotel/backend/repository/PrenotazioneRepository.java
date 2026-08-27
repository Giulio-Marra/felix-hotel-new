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
import java.util.List;
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
     * Quante camere di ogni tipologia risultano impegnate <b>nella notte
     * peggiore</b> del periodo richiesto.
     *
     * <p><b>E' meta' del calcolo della disponibilita'</b>: l'altra meta' e'
     * quante camere di quella tipologia esistono, che la sa
     * {@code CameraRepository}. La sottrazione la fa il service, perche' e' li'
     * che le due meta' si incontrano.
     *
     * <p><b>Perche' non basta contare le prenotazioni sovrapposte.</b> La prima
     * versione di questo calcolo sottraeva alle camere il numero di prenotazioni
     * che si sovrappongono al periodo. E' esatto solo quando quelle prenotazioni
     * si sovrappongono <b>anche fra loro</b>: se non lo fanno, la stessa stanza
     * viene sottratta piu' volte. Con due camere e tre confermate consecutive —
     * 1->2, 2->3, 3->4 settembre — una richiesta 1->5 ne contava tre, concludeva
     * {@code 2 - 3 <= 0} e rispondeva "non c'e' posto": ma le tre stanno tutte in
     * una camera sola, e la seconda era libera tutte le notti. L'errore andava
     * nella direzione sicura — non si vendeva due volte la stessa stanza — ma
     * rifiutava soggiorni che si potevano servire, e sulla pagina di ricerca
     * sarebbe diventato "non c'e' disponibilita'". La domanda giusta e' <b>notte
     * per notte</b>: chi prenota vuole una stanza per tutte le notti che ha
     * chiesto, quindi cio' che gli toglie il posto e' il momento di massimo
     * affollamento.
     *
     * <p><b>Perche' non si scorrono le notti una per una.</b> La strada ovvia
     * sarebbe {@code generate_series} sulle notti del periodo. E' stata scartata
     * perche' il suo costo cresce con <b>la durata che chiede il client</b>, e
     * oggi nessuna regola la limita (vedi i gap: "nessun tetto alla durata di un
     * soggiorno"). Una query il cui peso lo decide chi chiama e' una query che
     * aspetta solo la richiesta giusta. Non serve pero' guardarle tutte:
     * l'occupazione cambia <b>solo dove una prenotazione comincia</b>, quindi il
     * massimo cade sul primo giorno del periodo oppure su uno degli inizi che
     * cadono dentro. Il CTE {@code giorni} raccoglie esattamente quei candidati,
     * e il costo torna a dipendere da quante prenotazioni ci sono invece che da
     * quanto e' lungo il soggiorno chiesto.
     *
     * <p><b>E' la prima query nativa del progetto.</b> Fin qui e' bastata la
     * JPQL; qui non basta, perche' servono un CTE e una {@code left join} su una
     * colonna calcolata. Il prezzo e' che questo SQL e' legato a PostgreSQL —
     * accettabile, perche' lo sono gia' le migration di Flyway e i Testcontainers
     * su cui gira l'integrazione: non si perde una portabilita' che il progetto
     * avesse davvero. Ne discende anche che gli stati arrivano come
     * <b>stringhe</b> e non come enum: in SQL puro non c'e' nessuno che traduca
     * una costante Java nel nome scritto in colonna, mentre in JPQL lo faceva
     * Hibernate. Vedi {@code StatoPrenotazione.nomiCheOccupano()}, che li deriva
     * dall'unica definizione che esiste invece di riscriverli.
     *
     * <p><b>La sovrapposizione resta con disuguaglianze strette</b>, ed e' la
     * stessa regola di prima scritta da un altro lato: una prenotazione occupa la
     * notte di {@code giorno} se e' cominciata entro quel giorno e riparte dopo.
     * Chi arriva il 13 prende cosi' la camera che qualcuno libera il 13.
     *
     * @param tipologiaCameraId se null, calcola per tutte le tipologie: e' la
     *                          forma che serve alla ricerca del cliente, mentre
     *                          creazione e conferma ne guardano una sola
     * @param esclusa           prenotazione da non contare, o null per contarle
     *                          tutte. Serve alle verifiche fatte su una
     *                          prenotazione che gia' esiste: senza, una
     *                          CONFERMATA riesaminata conterebbe se stessa fra
     *                          quelle che le tolgono il posto
     */
    @Query(nativeQuery = true, value = """
            with giorni as (
                select t.id as tipologia, cast(:dataCheckIn as date) as giorno
                  from tipologia_camera t
                 where cast(:tipologiaCameraId as bigint) is null
                    or t.id = cast(:tipologiaCameraId as bigint)
                union
                select p.tipologia_camera_id, p.data_check_in
                  from prenotazione p
                 where p.stato in (:statiCheOccupano)
                   and p.data_check_in > cast(:dataCheckIn as date)
                   and p.data_check_in < cast(:dataCheckOut as date)
                   and (cast(:tipologiaCameraId as bigint) is null
                        or p.tipologia_camera_id = cast(:tipologiaCameraId as bigint))
                   and (cast(:esclusa as bigint) is null or p.id <> cast(:esclusa as bigint))
            ),
            occupazione as (
                select g.tipologia as tipologia,
                       g.giorno    as giorno,
                       count(p.id) as occupate
                  from giorni g
                  left join prenotazione p
                         on p.tipologia_camera_id = g.tipologia
                        and p.stato in (:statiCheOccupano)
                        and p.data_check_in  <= g.giorno
                        and p.data_check_out >  g.giorno
                        and (cast(:esclusa as bigint) is null or p.id <> cast(:esclusa as bigint))
                 group by g.tipologia, g.giorno
            )
            select o.tipologia     as "tipologiaCameraId",
                   max(o.occupate) as "occupate"
              from occupazione o
             group by o.tipologia
            """)
    List<OccupazioneTipologia> occupazioneMassima(
            @Param("tipologiaCameraId") Long tipologiaCameraId,
            @Param("dataCheckIn") LocalDate dataCheckIn,
            @Param("dataCheckOut") LocalDate dataCheckOut,
            @Param("statiCheOccupano") Collection<String> statiCheOccupano,
            @Param("esclusa") Long esclusa);

    /**
     * La stessa cosa per una tipologia sola, che e' la forma di cui hanno bisogno
     * la creazione e la conferma.
     *
     * <p><b>Non e' una seconda query, e' un adattatore</b>: chiama quella qui
     * sopra col filtro valorizzato e prende l'unica riga che puo' tornare. Il CTE
     * {@code giorni} include sempre il primo giorno del periodo per ogni
     * tipologia richiesta, quindi la riga c'e' anche quando quella tipologia non
     * ha nessuna prenotazione — e vale zero. Il ramo vuoto resta per la tipologia
     * che non esiste, che pero' il service ha gia' risolto prima di arrivare qui.
     */
    default long occupazioneMassimaDi(Long tipologiaCameraId, LocalDate dataCheckIn,
                                      LocalDate dataCheckOut, Collection<String> statiCheOccupano,
                                      Long esclusa) {
        return occupazioneMassima(tipologiaCameraId, dataCheckIn, dataCheckOut, statiCheOccupano, esclusa)
                .stream()
                .findFirst()
                .map(OccupazioneTipologia::getOccupate)
                .orElse(0L);
    }
}
