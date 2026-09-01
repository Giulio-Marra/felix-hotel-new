package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.PeriodoTariffario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Accesso ai dati dei periodi tariffari, e — soprattutto — <b>l'unico posto in
 * cui vive il calcolo del prezzo</b>.
 *
 * <p>Il metodo {@link #preventivi} risponde alla domanda "quanto costa questo
 * soggiorno", e ci rispondono attraverso di lui tutti e due i suoi consumatori:
 * la ricerca di disponibilita', che mostra un preventivo, e la creazione della
 * prenotazione, che ne fotografa uno. Non ci sono due implementazioni della
 * stessa regola, ed e' deliberato: due formule dei prezzi che divergono
 * significano una pagina di ricerca che dice un numero e una fattura che ne dice
 * un altro, cioe' il difetto peggiore che questo pezzo di dominio possa avere.
 */
public interface PeriodoTariffarioRepository extends JpaRepository<PeriodoTariffario, Long> {

    /**
     * I periodi di una tipologia, dal piu' vecchio al piu' recente.
     *
     * <p>Ordinati per data di inizio e non per nome: e' un calendario, e chi lo
     * guarda vuole vedere l'anno scorrere. Le date non possono accavallarsi
     * (vincolo di esclusione del V9), quindi questo ordine e' totale e non ha
     * bisogno di un secondo criterio — al contrario dell'elenco delle
     * prenotazioni, dove la data di arrivo si ripete.
     *
     * <p><b>Paginato</b>, al contrario della galleria fotografica e del registro
     * degli ospiti, che sono elenchi di dimensione nota per costruzione. Questo
     * no: i periodi si accumulano di anno in anno e niente li limita, ed e' la
     * condizione in cui la regola 21 vuole un tetto su {@code size} — senza,
     * l'endpoint diventa il modo di farsi restituire la tabella intera con una
     * richiesta sola.
     *
     * <p><b>Nessun {@code @EntityGraph} sui prezzi per giorno</b>, proprio
     * perche' e' paginato: un fetch di collezione dentro una query paginata
     * costringe Hibernate a caricare tutte le righe e a impaginare in memoria.
     * Li carica il {@code @BatchSize} dichiarato sulla collezione, con una
     * seconda query sola per l'intera pagina. E' la stessa scelta gia' fatta per
     * le dotazioni del catalogo, e per la stessa ragione.
     */
    Page<PeriodoTariffario> findByTipologiaCameraIdOrderByDataInizioAsc(Long tipologiaCameraId,
                                                                       Pageable pageable);

    /**
     * Un periodo preciso, ma solo se appartiene alla tipologia dell'URL.
     *
     * <p><b>La tipologia fa parte della chiave di ricerca e non e' un controllo
     * in piu'</b>: {@code /api/tipologie-camera/3/tariffe/7} deve rispondere 404
     * se il periodo 7 e' della tipologia 5, non modificarlo. Cercare per solo id
     * e confrontare dopo funzionerebbe uguale, ma lascerebbe il confronto a chi
     * chiama — e basta un metodo nuovo che se lo dimentica.
     */
    @EntityGraph(attributePaths = "prezziGiorno")
    Optional<PeriodoTariffario> findByIdAndTipologiaCameraId(Long id, Long tipologiaCameraId);

    /**
     * I periodi della stessa tipologia che si accavallano con l'intervallo dato.
     *
     * <p>Serve a rispondere <b>409 con un messaggio che dice con chi</b> prima
     * di arrivare al vincolo di esclusione del database, che invece darebbe un
     * errore di violazione di vincolo. E' la stessa divisione dei compiti fra
     * gli {@code existsBy} e gli indici unici gia' in uso: qui la cortesia, la'
     * la garanzia — l'unica che regge quando due richieste arrivano nello stesso
     * istante.
     *
     * <p>La condizione di sovrapposizione e' con <b>disuguaglianze non
     * strette</b>, al contrario di quella delle prenotazioni: li' gli estremi
     * sono arrivo e partenza e il giorno di partenza e' libero, qui sono la
     * prima e l'ultima notte e sono comprese tutte e due. Due periodi che
     * finiscono e cominciano lo stesso giorno si contendono quella notte.
     *
     * @param esclusa periodo da non considerare, o null per considerarli tutti.
     *                Serve all'aggiornamento: senza, un periodo a cui si
     *                riconfermano le proprie date si troverebbe sovrapposto a se
     *                stesso
     */
    @Query("""
            select p from PeriodoTariffario p
            where p.tipologiaCamera.id = :tipologiaCameraId
              and p.dataInizio <= :dataFine
              and p.dataFine >= :dataInizio
              and (:esclusa is null or p.id <> :esclusa)
            """)
    List<PeriodoTariffario> trovaSovrapposti(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                             @Param("dataInizio") LocalDate dataInizio,
                                             @Param("dataFine") LocalDate dataFine,
                                             @Param("esclusa") Long esclusa);

    /**
     * Quanto costa il soggiorno per ognuna delle tipologie richieste, e qual e'
     * il soggiorno minimo che ognuna impone.
     *
     * <p><b>E' l'unica definizione del prezzo che esiste nel progetto.</b> La
     * usano la ricerca di disponibilita' e la creazione della prenotazione, e il
     * motivo per cui non ce n'e' una seconda scritta in Java sta nel javadoc
     * della classe.
     *
     * <p><b>Tre livelli, provati in quest'ordine</b> — e' tutto nel
     * {@code coalesce}: il prezzo del giorno della settimana, se il periodo che
     * copre quella notte ne ha uno; altrimenti il prezzo base del periodo;
     * altrimenti il prezzo di listino della tipologia, che e' cio' che vale per
     * le notti che nessun periodo copre. L'ultimo livello e' la ragione per cui
     * {@code tipologia_camera.prezzo_notte} non e' stato tolto: un albergo che
     * non configura nessun periodo continua a vendere come prima.
     *
     * <p><b>Il {@code left join} non moltiplica le righe, e non e' fortuna.</b>
     * Il conto e' esatto solo se ogni notte produce <b>una</b> riga per
     * tipologia, e a garantirlo sono i due vincoli del V9: il vincolo di
     * esclusione impedisce due periodi sovrapposti (quindi al massimo un periodo
     * per notte) e l'indice unico impedisce due prezzi per lo stesso giorno
     * della settimana. Se un giorno uno dei due venisse tolto, questa somma
     * comincerebbe a contare due volte le stesse notti senza dirlo a nessuno. E'
     * anche il motivo per cui {@code count(*)} qui e' esattamente il numero di
     * notti, e quindi la media per notte del filtro non ha bisogno di un
     * parametro suo.
     *
     * <p><b>{@code generate_series} qui si', mentre la query dell'occupazione lo
     * evita.</b> La differenza non e' un ripensamento: quella query poteva
     * saltare le notti perche' l'occupazione cambia solo dove una prenotazione
     * comincia, mentre il prezzo cambia dove comincia un periodo <i>e</i> ad ogni
     * cambio di giorno della settimana — cioe' potenzialmente ogni notte. Il
     * motivo per cui e' accettabile lo stesso e' che dal 2026-09-01 un soggiorno
     * non puo' superare le notti di {@code DurataSoggiorno.MASSIMO_NOTTI}: il
     * costo non lo decide piu' il client, che era l'unica vera obiezione. Quel
     * tetto e' un prerequisito di questa query, non un dettaglio a parte.
     *
     * <p><b>Il giorno della settimana si ricava con {@code isodow} e non con
     * {@code to_char}</b>: {@code to_char(giorno, 'DAY')} restituisce il nome
     * nella lingua della sessione e con lo spazio in coda, quindi darebbe
     * 'LUNEDI' su un database configurato in italiano e non troverebbe mai una
     * riga. {@code isodow} e' un numero da 1 (lunedi') a 7 (domenica) e non
     * dipende da nessuna impostazione; l'array traduce quel numero nel nome
     * dell'enum {@code java.time.DayOfWeek}, che e' com'e' scritto in colonna.
     *
     * <p><b>Il soggiorno minimo e' quello della notte di arrivo</b>, non il
     * massimo di quelli attraversati. Un soggiorno che comincia in bassa
     * stagione e sconfina in alta non deve essere rifiutato per una regola del
     * periodo in cui l'ospite entra a meta': quel che si vende e' l'arrivo, ed e'
     * anche la regola che i gestionali applicano davvero. Il {@code filter}
     * isola quella notte sola; il {@code coalesce} vale per la notte di arrivo
     * che nessun periodo copre, dove il minimo e' 1 cioe' nessun vincolo.
     *
     * <p><b>Perche' filtra e impagina invece di prendere degli id.</b> La
     * ricerca di disponibilita' ha un filtro per fascia di prezzo, e da oggi
     * quel filtro riguarda il prezzo <i>effettivo</i> del periodo cercato: se
     * restasse sul prezzo di listino, chi cerca sotto i 100 euro per Ferragosto
     * si vedrebbe offrire una camera che a Ferragosto ne costa 200. Ma un filtro
     * sul prezzo puo' escludere delle righe, quindi deve agire <b>prima</b> della
     * paginazione — altrimenti si tolgono elementi da una pagina gia' formata e
     * le pagine escono di dimensione variabile, che e' impaginare in memoria con
     * un altro nome. Da qui la forma: chi calcola il prezzo e' anche chi decide
     * quali righe entrano e in che pagina. L'alternativa — una seconda query che
     * filtra sul prezzo — avrebbe scritto la formula dei prezzi una seconda
     * volta, che e' esattamente cio' che questa classe esiste per evitare.
     *
     * <p><b>La {@code countQuery} ripete le stesse giunzioni</b>, ed e' l'unica
     * duplicazione rimasta: un {@code Page} da query nativa pretende il proprio
     * conteggio, e il conteggio dipende dal {@code having}, cioe' dal prezzo. A
     * toglierla basterebbe una funzione SQL che dia il prezzo di una notte, ma
     * vorrebbe dire spostare una regola di business dentro il database, dove
     * nessun test unitario la vede: costa piu' di quel che risparmia.
     *
     * <p>L'ordinamento e' <b>scritto nella query</b> e non passato col
     * {@code Pageable}: e' un {@code group by} e chi chiama non ha nessun
     * criterio da scegliere. L'ordine e' alfabetico come il catalogo, e come li'
     * un criterio solo basta perche' il nome e' unico (indice del V2). Postgres
     * accetta {@code t.nome} fuori dal {@code group by} perche' il
     * raggruppamento e' sulla chiave primaria, da cui il nome dipende.
     *
     * @param tipologiaCameraId se valorizzato restringe a una sola tipologia,
     *                          che e' la forma di cui ha bisogno la creazione di
     *                          una prenotazione; null le valuta tutte, che e'
     *                          quella della ricerca. Stesso schema del parametro
     *                          {@code esclusa} di
     *                          {@code PrenotazioneRepository.occupazioneMassima}
     * @param capienzaMinima    se null, non restringe per capienza
     * @param prezzoMinimo      se null non pone un minimo; il confronto e' sulla
     *                          <b>media per notte</b> del periodo cercato, cioe'
     *                          il totale diviso le notti
     * @param prezzoMassimo     se null, non pone un massimo
     * @param dataCheckIn       giorno di arrivo, prima notte compresa
     * @param dataCheckOut      giorno di partenza, <b>notte esclusa</b>: chi
     *                          arriva il 10 e parte il 13 paga le notti 10, 11
     *                          e 12
     */
    @Query(nativeQuery = true, value = """
            with notti as (
                select cast(g.istante as date) as giorno
                  from generate_series(cast(:dataCheckIn as date),
                                       cast(:dataCheckOut as date) - 1,
                                       interval '1 day') as g(istante)
            )
            select t.id as "tipologiaCameraId",
                   sum(coalesce(pg.prezzo, pt.prezzo_notte, t.prezzo_notte)) as "importoTotale",
                   coalesce(max(pt.soggiorno_minimo)
                            filter (where n.giorno = cast(:dataCheckIn as date)), 1) as "soggiornoMinimo"
              from tipologia_camera t
             cross join notti n
              left join periodo_tariffario pt
                     on pt.tipologia_camera_id = t.id
                    and n.giorno between pt.data_inizio and pt.data_fine
              left join prezzo_giorno_settimana pg
                     on pg.periodo_tariffario_id = pt.id
                    and pg.giorno = (array['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                           'FRIDAY', 'SATURDAY', 'SUNDAY'])
                                    [cast(extract(isodow from n.giorno) as int)]
             where (cast(:tipologiaCameraId as bigint) is null
                    or t.id = cast(:tipologiaCameraId as bigint))
               and (cast(:capienzaMinima as int) is null
                    or t.capienza_max >= cast(:capienzaMinima as int))
             group by t.id, t.nome
            having (cast(:prezzoMinimo as numeric) is null
                    or sum(coalesce(pg.prezzo, pt.prezzo_notte, t.prezzo_notte)) / count(*)
                       >= cast(:prezzoMinimo as numeric))
               and (cast(:prezzoMassimo as numeric) is null
                    or sum(coalesce(pg.prezzo, pt.prezzo_notte, t.prezzo_notte)) / count(*)
                       <= cast(:prezzoMassimo as numeric))
             order by t.nome
            """,
            countQuery = """
            with notti as (
                select cast(g.istante as date) as giorno
                  from generate_series(cast(:dataCheckIn as date),
                                       cast(:dataCheckOut as date) - 1,
                                       interval '1 day') as g(istante)
            )
            select count(*) from (
                select t.id
                  from tipologia_camera t
                 cross join notti n
                  left join periodo_tariffario pt
                         on pt.tipologia_camera_id = t.id
                        and n.giorno between pt.data_inizio and pt.data_fine
                  left join prezzo_giorno_settimana pg
                         on pg.periodo_tariffario_id = pt.id
                        and pg.giorno = (array['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                               'FRIDAY', 'SATURDAY', 'SUNDAY'])
                                        [cast(extract(isodow from n.giorno) as int)]
                 where (cast(:tipologiaCameraId as bigint) is null
                        or t.id = cast(:tipologiaCameraId as bigint))
                   and (cast(:capienzaMinima as int) is null
                        or t.capienza_max >= cast(:capienzaMinima as int))
                 group by t.id
                having (cast(:prezzoMinimo as numeric) is null
                        or sum(coalesce(pg.prezzo, pt.prezzo_notte, t.prezzo_notte)) / count(*)
                           >= cast(:prezzoMinimo as numeric))
                   and (cast(:prezzoMassimo as numeric) is null
                        or sum(coalesce(pg.prezzo, pt.prezzo_notte, t.prezzo_notte)) / count(*)
                           <= cast(:prezzoMassimo as numeric))
            ) as righe
            """)
    Page<PreventivoTipologia> preventivi(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                         @Param("capienzaMinima") Integer capienzaMinima,
                                         @Param("prezzoMinimo") BigDecimal prezzoMinimo,
                                         @Param("prezzoMassimo") BigDecimal prezzoMassimo,
                                         @Param("dataCheckIn") LocalDate dataCheckIn,
                                         @Param("dataCheckOut") LocalDate dataCheckOut,
                                         Pageable pageable);

    /**
     * Il preventivo di una tipologia sola, che e' la forma di cui ha bisogno la
     * creazione di una prenotazione.
     *
     * <p><b>Non e' una seconda query, e' un adattatore</b>, come
     * {@code PrenotazioneRepository.occupazioneMassimaDi}: chiama quella qui
     * sopra restringendola a un id e senza filtri. E' il modo in cui la formula
     * dei prezzi resta scritta una volta.
     *
     * <p><b>{@code Pageable.unpaged()} e non {@code PageRequest.of(0, 1)}</b>, e
     * la differenza non e' estetica: con una pagina vera Spring Data esegue
     * <b>anche la count query</b> — la salta solo quando il contenuto e' piu'
     * corto della pagina, e una pagina da uno riempita da una riga non lo e'. La
     * creazione di ogni prenotazione avrebbe pagato due volte lo stesso
     * {@code generate_series} per avere un numero che nessuno guarda. Senza
     * paginazione il limite lo mette gia' il filtro sull'id, che di righe ne
     * lascia passare al massimo una.
     *
     * <p>Il ramo vuoto non e' raggiungibile passando da un service, che la
     * tipologia l'ha gia' cercata e non trovata avrebbe risposto 404: se
     * scattasse vorrebbe dire che la riga e' sparita fra le due letture, che non
     * e' un errore di chi chiama — da qui l'{@code IllegalStateException}, che
     * diventa un 500, invece di una {@code NotFoundException}.
     */
    default PreventivoTipologia preventivoDi(Long tipologiaCameraId, LocalDate dataCheckIn,
                                             LocalDate dataCheckOut) {
        return preventivi(tipologiaCameraId, null, null, null, dataCheckIn, dataCheckOut,
                Pageable.unpaged()).getContent().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Nessun preventivo per la tipologia " + tipologiaCameraId));
    }
}
