package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Accesso ai dati delle prenotazioni.
 *
 * <p>Ogni lettura che finisce in una risposta carica anche cliente, tipologia,
 * personale e camera assegnata con {@code @EntityGraph}: sono relazioni LAZY e
 * il progetto ha {@code open-in-view=false} (regola 15). Sono tutte
 * {@code ManyToOne}, quindi il fetch resta innocuo anche sull'elenco paginato —
 * sono join che non moltiplicano le righe, al contrario di quel che
 * succederebbe con una collezione.
 *
 * <p>Della camera serve anche <b>la sua</b> tipologia e non solo il numero:
 * puo' non essere quella prenotata, ed e' proprio quando le due differiscono che
 * mostrarle entrambe conta. Il percorso annidato resta un secondo
 * {@code ManyToOne} sulla stessa riga, quindi non cambia la natura del join. La
 * camera e' nullable e l'{@code @EntityGraph} produce una left join: una
 * prenotazione senza camera continua a comparire.
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
     *
     * <p><b>I flag accanto ai filtri seguono la regola 25</b>, dal 2026-09-03: un
     * parametro facoltativo compare <b>solo accanto alla sua colonna</b>, che gli da' il
     * tipo, e a dire "questo filtro non si applica" ci pensa un booleano, che un tipo ce
     * l'ha sempre. In {@code :filtro is null} non c'e' niente da cui Postgres possa
     * dedurre un tipo — <i>qualunque cosa</i> puo' essere null — e quando non lo deduce
     * appoggia al testo. Qui funzionava proprio per quello, e la forma e' stata comunque
     * uniformata: la scappatoia del testo non esiste per le <b>date</b>, quindi il primo
     * filtro di quel tipo aggiunto a questa query avrebbe dato un 500 che nei test non si
     * vede (vedi il javadoc di {@code BloccoDisponibilitaRepository.cerca}, dove c'e'
     * l'intera diagnosi).
     */
    @EntityGraph(attributePaths = {"utente", "tipologiaCamera", "gestitaDaStaff",
            "camera.tipologiaCamera"})
    @Query("""
            select p from Prenotazione p
            where (:filtraUtente = false or p.utente.id = :utenteId)
              and (:filtraStato  = false or p.stato     = :stato)
            """)
    Page<Prenotazione> cerca(@Param("filtraUtente") boolean filtraUtente,
                             @Param("utenteId") Long utenteId,
                             @Param("filtraStato") boolean filtraStato,
                             @Param("stato") StatoPrenotazione stato,
                             Pageable pageable);

    /**
     * La forma comoda per chi chiama: i due booleani si ricavano dai parametri invece di
     * essere passati a mano.
     *
     * <p><b>Un {@code default} e non due firme distinte</b>: cosi' il Service continua a
     * chiedere quel che gli interessa — "questo cliente, questo stato" — e la meccanica
     * dei flag resta un fatto del repository, che e' l'unico posto in cui ha un senso.
     */
    default Page<Prenotazione> cerca(Long utenteId, StatoPrenotazione stato, Pageable pageable) {
        return cerca(utenteId != null, utenteId, stato != null, stato, pageable);
    }

    /** Lettura per id con le relazioni gia' caricate: e' il preambolo di ogni metodo che risponde. */
    @Override
    @EntityGraph(attributePaths = {"utente", "tipologiaCamera", "gestitaDaStaff",
            "camera.tipologiaCamera"})
    Optional<Prenotazione> findById(Long id);

    /**
     * Se una camera <b>precisa</b> risulti gia' impegnata in un periodo.
     *
     * <p>Serve al check-in quando la stanza la nomina chi sta al banco invece di
     * lasciarla scegliere: li' non si cerca fra le assegnabili, se ne verifica
     * una sola. Filtrare l'elenco delle assegnabili darebbe la stessa risposta
     * caricando tutte le camere di una tipologia per guardarne una — e
     * soprattutto <b>non funzionerebbe per l'upgrade</b>, dove la stanza scelta
     * e' di un'altra tipologia e in quell'elenco non comparirebbe mai.
     *
     * <p><b>Solo CHECK_IN</b>, per la stessa ragione spiegata per esteso in
     * {@code CameraRepository.trovaAssegnabili}: la domanda e' "c'e' qualcuno
     * dentro adesso", non "questa prenotazione consuma disponibilita'". Un
     * ospite partito in anticipo lascia una CHECK_OUT che copre ancora le notti
     * successive, e quella stanza va potuta ridare subito.
     *
     * <p>Le disuguaglianze sono strette come ovunque: chi parte il 13 libera la
     * stanza per chi arriva il 13.
     */
    @Query("""
            select count(p) > 0 from Prenotazione p
            where p.camera.id = :cameraId
              and p.stato = :statoOccupante
              and p.dataCheckIn  <  :dataCheckOut
              and p.dataCheckOut >  :dataCheckIn
            """)
    boolean esisteSovrapposizioneSuCamera(
            @Param("cameraId") Long cameraId,
            @Param("statoOccupante") StatoPrenotazione statoOccupante,
            @Param("dataCheckIn") LocalDate dataCheckIn,
            @Param("dataCheckOut") LocalDate dataCheckOut);

    /**
     * Gli arrivi di un giorno, per l'export delle schedine alloggiati.
     *
     * <p><b>La data e' quella prevista dalla prenotazione</b> e non l'istante in cui
     * il check-in e' stato battuto, per la ragione semplice che il secondo il
     * progetto non lo registra: {@code BaseAuditableEntity} tiene un
     * {@code updatedAt} che qualunque altra modifica sposta. La conseguenza va
     * saputa — chi registra alle otto di mattina un ospite arrivato la sera prima lo
     * trova nel file del giorno prenotato — ed e' scritta fra i limiti noti in
     * CLAUDE.md.
     *
     * <p><b>Lo stato lo passa chi chiama</b> invece di essere scritto qui dentro: la
     * domanda "quali stati vogliono dire che questa persona e' arrivata davvero" e'
     * una decisione di dominio, e le decisioni di dominio non stanno nei repository.
     * E' la stessa forma gia' usata da {@code cerca}.
     *
     * <p>L'ordine per id e' quello di creazione, cioe' l'ordine in cui le
     * prenotazioni sono nate: sul file non conta a nessuno, ma un export che
     * cambiasse ordine fra due chiamate renderebbe impossibile confrontare due
     * versioni dello stesso giorno.
     *
     * <p><b>Una query scritta e non un nome derivato</b>, e la ragione vale la pena
     * saperla perche' e' una trappola che ricapitera': il nome
     * {@code findByDataCheckInAndStatoIn...} non si puo' scrivere, perche' chi
     * interpreta i nomi dei metodi lo spezza in {@code dataCheck} piu' la parola
     * chiave {@code In} e cerca una proprieta' che non esiste. Con un campo che
     * <i>finisce</i> come una parola chiave il nome derivato e' ambiguo per
     * costruzione, e nessuna convenzione di scrittura lo salva.
     */
    @Query("""
            select p from Prenotazione p
            where p.dataCheckIn = :dataCheckIn
              and p.stato in :stati
            order by p.id
            """)
    List<Prenotazione> arriviDelGiorno(@Param("dataCheckIn") LocalDate dataCheckIn,
                                       @Param("stati") Collection<StatoPrenotazione> stati);

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
     * @param tipologiaCameraIds le tipologie su cui calcolare. <b>Mai vuoto</b>:
     *                           un {@code in ()} non e' SQL valido, e chi chiama
     *                           deve saltare la query quando non ha niente da
     *                           chiedere — che e' anche l'unica risposta sensata
     *                           in quel caso. Ne basta uno per creazione e
     *                           conferma, sono quelli di una pagina per la
     *                           ricerca del cliente
     * @param esclusa            prenotazione da non contare, o null per contarle
     *                           tutte. Serve alle verifiche fatte su una
     *                           prenotazione che gia' esiste: senza, una
     *                           CONFERMATA riesaminata conterebbe se stessa fra
     *                           quelle che le tolgono il posto
     *
     * <p><b>Dal 2026-09-02 conta anche i blocchi</b> ({@code BloccoDisponibilita}), cioe'
     * le camere non vendibili senza che nessuno le abbia prenotate: una manutenzione,
     * oppure — col branch dell'iCal — una unita' che un canale esterno ha gia' venduto.
     * Entrano in tutte e due le meta' del calcolo: fra le <b>notti candidate</b>, perche'
     * un blocco che comincia dentro il periodo crea una notte peggiore che prima non
     * c'era, e nel <b>conteggio</b>, perche' tolgono una unita' esattamente come una
     * prenotazione.
     *
     * <p><b>I due conteggi sono due sottoquery e non due join</b>, ed e' l'unica cosa che
     * e' cambiata nella forma della query. Con due {@code left join} sulla stessa riga di
     * {@code giorni} le righe si moltiplicherebbero fra loro — tre prenotazioni e due
     * blocchi darebbero sei righe — e il {@code count} conterebbe il prodotto invece
     * della somma. E' un errore che non si vede sui casi piccoli: con zero blocchi, o con
     * zero prenotazioni, il risultato resta giusto.
     *
     * <p><b>I blocchi non guardano lo stato ne' l'esclusione</b>: uno stato non ce l'hanno,
     * e {@code esclusa} serve a non far competere una prenotazione con se stessa quando si
     * riconferma — un blocco con quella domanda non c'entra niente.
     */
    @Query(nativeQuery = true, value = """
            with giorni as (
                select t.id as tipologia, cast(:dataCheckIn as date) as giorno
                  from tipologia_camera t
                 where t.id in (:tipologiaCameraIds)
                union
                select p.tipologia_camera_id, p.data_check_in
                  from prenotazione p
                 where p.tipologia_camera_id in (:tipologiaCameraIds)
                   and p.stato in (:statiCheOccupano)
                   and p.data_check_in > cast(:dataCheckIn as date)
                   and p.data_check_in < cast(:dataCheckOut as date)
                   and (cast(:esclusa as bigint) is null or p.id <> cast(:esclusa as bigint))
                union
                select b.tipologia_camera_id, b.data_inizio
                  from blocco_disponibilita b
                 where b.tipologia_camera_id in (:tipologiaCameraIds)
                   and b.data_inizio > cast(:dataCheckIn as date)
                   and b.data_inizio < cast(:dataCheckOut as date)
            ),
            occupazione as (
                select g.tipologia as tipologia,
                       g.giorno    as giorno,
                       (select count(*)
                          from prenotazione p
                         where p.tipologia_camera_id = g.tipologia
                           and p.stato in (:statiCheOccupano)
                           and p.data_check_in  <= g.giorno
                           and p.data_check_out >  g.giorno
                           and (cast(:esclusa as bigint) is null
                                or p.id <> cast(:esclusa as bigint)))
                       +
                       (select count(*)
                          from blocco_disponibilita b
                         where b.tipologia_camera_id = g.tipologia
                           and b.data_inizio <= g.giorno
                           and b.data_fine   >  g.giorno) as occupate
                  from giorni g
            )
            select o.tipologia     as "tipologiaCameraId",
                   max(o.occupate) as "occupate"
              from occupazione o
             group by o.tipologia
            """)
    List<OccupazioneTipologia> occupazioneMassima(
            @Param("tipologiaCameraIds") Collection<Long> tipologiaCameraIds,
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
    /**
     * Le prenotazioni di una tipologia che toccano un orizzonte, per il calendario.
     *
     * <p><b>Restituisce le entita' e non un conteggio</b>, al contrario di
     * {@code occupazioneMassima}: qui non serve sapere <i>quante</i> unita' sono occupate
     * in una notte, ma <i>quali periodi</i> e — dove c'e' — <i>su quale camera</i>. Una
     * prenotazione a cui il check-in ha gia' assegnato una stanza va sul calendario di
     * quella stanza, non su una qualunque.
     *
     * <p>Gli stati li passa chi chiama, come per {@code cerca} e per gli arrivi del
     * giorno: quali stati occupino una camera e' una decisione di dominio, e le decisioni
     * di dominio non stanno nei repository.
     */
    @Query("""
            select p from Prenotazione p
            where p.tipologiaCamera.id = :tipologiaCameraId
              and p.stato in :stati
              and p.dataCheckIn  < :a
              and p.dataCheckOut > :da
            """)
    List<Prenotazione> occupazioniNellOrizzonte(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                                @Param("stati") Collection<StatoPrenotazione> stati,
                                                @Param("da") LocalDate da,
                                                @Param("a") LocalDate a);

    default long occupazioneMassimaDi(Long tipologiaCameraId, LocalDate dataCheckIn,
                                      LocalDate dataCheckOut, Collection<String> statiCheOccupano,
                                      Long esclusa) {
        return occupazioneMassima(List.of(tipologiaCameraId), dataCheckIn, dataCheckOut,
                statiCheOccupano, esclusa)
                .stream()
                .findFirst()
                .map(OccupazioneTipologia::getOccupate)
                .orElse(0L);
    }

    /**
     * Blocca la riga della prenotazione per chi ci sta registrando un incasso.
     *
     * <p><b>Serializza chi incassa sulla stessa prenotazione.</b> Leggere quanto e' gia'
     * stato versato e scrivere il versamento nuovo sono due gesti, e fra i due
     * un'altra transazione puo' fare lo stesso conto: due saldi registrati insieme
     * passerebbero tutti e due, e il totale incassato supererebbe il dovuto senza che
     * nessuno abbia sbagliato a digitare niente. Con questo lock la seconda aspetta la
     * prima e poi rifa' la somma, trovandola cambiata.
     *
     * <p><b>Sulla prenotazione e non sui pagamenti</b>, per la stessa ragione per cui il
     * lock della vendita sta sulla tipologia: la riga da bloccare dev'essere una sola e
     * sempre la stessa per tutti i concorrenti, e qui l'oggetto di cui si conta il
     * residuo e' la prenotazione. Bloccare i pagamenti non servirebbe — il problema e'
     * quello che <b>non c'e' ancora</b>.
     *
     * <p><b>Restituisce l'id e non l'entita'</b>: chi chiama la prenotazione ce l'ha
     * gia', qui serve solo il {@code SELECT ... FOR UPDATE} sulla riga.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p.id from Prenotazione p where p.id = :id")
    Optional<Long> bloccaPerIncasso(@Param("id") Long id);
}
