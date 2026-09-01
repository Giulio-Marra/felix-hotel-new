package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Accesso ai dati delle aliquote della tassa di soggiorno.
 *
 * <p><b>Qui dentro non c'e' il calcolo</b>, ed e' la differenza piu' importante
 * fra questa interfaccia e {@link PeriodoTariffarioRepository}, dove invece il
 * calcolo del prezzo <i>e'</i> il repository. Le due ragioni che avevano portato
 * il prezzo dentro una query nativa qui non esistono:
 * <ul>
 *   <li><b>il prezzo ha due consumatori</b> — la ricerca e la creazione — e due
 *       formule che divergono vogliono dire una pagina di ricerca che dice un
 *       numero e una fattura che ne dice un altro. La tassa ne ha <b>uno</b>:
 *       l'endpoint che la mostra;</li>
 *   <li><b>il prezzo doveva impaginare</b>: il filtro sul prezzo esclude righe,
 *       quindi deve agire prima della paginazione, e questo si puo' fare solo in
 *       database. La tassa si calcola su <i>una</i> prenotazione per volta.</li>
 * </ul>
 * In cambio, la tassa ha qualcosa che il prezzo non ha: <b>rami veri</b> — tre
 * specie di esenzione, il tetto di notti, l'aliquota che manca — cioe' esattamente
 * cio' che i test unitari sanno guardare e una query nativa no. Il calcolo sta
 * quindi in {@code TassaSoggiornoServiceImpl}, in Java. Se un giorno servisse un
 * secondo consumatore — una fattura, un riepilogo mensile per il comune — e' questa
 * la decisione da riaprire per prima.
 */
public interface AliquotaTassaSoggiornoRepository extends JpaRepository<AliquotaTassaSoggiorno, Long> {

    /**
     * Tutte le aliquote, dalla piu' vecchia alla piu' recente.
     *
     * <p>Ordinate per data di inizio: e' un calendario, e chi lo guarda vuole
     * vederlo scorrere. Le date non possono accavallarsi (vincolo di esclusione
     * del V11), quindi questo ordine e' totale e non serve un secondo criterio.
     *
     * <p><b>Paginato</b> come i periodi tariffari e per la stessa ragione: le
     * aliquote si accumulano di anno in anno e niente le limita, che e' la
     * condizione in cui la regola 21 vuole un tetto su {@code size}.
     */
    Page<AliquotaTassaSoggiorno> findAllByOrderByDataInizioAsc(Pageable pageable);

    /**
     * Le aliquote che si accavallano con l'intervallo dato.
     *
     * <p>Serve a rispondere <b>409 con un messaggio che dice con quale</b> prima
     * di arrivare al vincolo di esclusione del database, che darebbe un errore di
     * violazione di vincolo e nient'altro. E' la stessa divisione dei compiti gia'
     * in uso fra gli {@code existsBy} e gli indici unici: qui la cortesia, la' la
     * garanzia — l'unica che regge quando due richieste arrivano nello stesso
     * istante.
     *
     * <p>Le disuguaglianze sono <b>non strette</b>, come per i periodi tariffari:
     * gli estremi sono la prima e l'ultima notte e sono comprese tutte e due, quindi
     * due aliquote che finiscono e cominciano lo stesso giorno si contendono quella
     * notte.
     *
     * @param esclusa aliquota da non considerare, o null per considerarle tutte.
     *                Serve all'aggiornamento: senza, un'aliquota a cui si
     *                riconfermano le proprie date si troverebbe sovrapposta a se
     *                stessa
     */
    @Query("""
            select a from AliquotaTassaSoggiorno a
            where a.dataInizio <= :dataFine
              and a.dataFine >= :dataInizio
              and (:esclusa is null or a.id <> :esclusa)
            order by a.dataInizio
            """)
    List<AliquotaTassaSoggiorno> trovaSovrapposte(@Param("dataInizio") LocalDate dataInizio,
                                                  @Param("dataFine") LocalDate dataFine,
                                                  @Param("esclusa") Long esclusa);

    /**
     * Le aliquote che coprono almeno una notte del soggiorno, in ordine di data.
     *
     * <p>E' la stessa condizione di {@link #trovaSovrapposte}, ma il nome dice
     * un'altra cosa perche' serve a un'altra cosa: quella cerca un conflitto da
     * rifiutare, questa raccoglie il materiale del calcolo. Tenerle separate costa
     * un metodo e evita che un domani qualcuno cambi la semantica dell'una
     * rompendo l'altra.
     *
     * <p><b>Puo' restituire zero righe, ed e' un caso normale</b>: un comune senza
     * tassa di soggiorno esiste, e un'installazione appena fatta non ha ancora
     * configurato niente. Chi chiama non deve trattarlo come un errore — vedi
     * {@code TassaSoggiornoServiceImpl} per il perche' la risposta e' un totale a
     * zero e non un 409.
     *
     * <p><b>Ne puo' restituire piu' di una</b> quando il soggiorno attraversa un
     * cambio di aliquota, che e' il motivo per cui il calcolo va fatto notte per
     * notte e non moltiplicando: un soggiorno a cavallo del primo luglio paga le
     * notti di giugno a un prezzo e quelle di luglio a un altro.
     */
    @Query("""
            select a from AliquotaTassaSoggiorno a
            where a.dataInizio <= :ultimaNotte
              and a.dataFine >= :primaNotte
            order by a.dataInizio
            """)
    List<AliquotaTassaSoggiorno> trovaPerSoggiorno(@Param("primaNotte") LocalDate primaNotte,
                                                   @Param("ultimaNotte") LocalDate ultimaNotte);
}
