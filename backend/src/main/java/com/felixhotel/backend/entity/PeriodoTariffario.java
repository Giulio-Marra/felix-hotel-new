package com.felixhotel.backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Un intervallo di date in cui una tipologia costa un certo prezzo a notte, con
 * l'eventuale numero minimo di notti.
 *
 * <p><b>Perche' esiste.</b> Fino al 2026-09-01 il prezzo era una colonna sola su
 * {@link TipologiaCamera} e valeva per sempre: la doppia costava uguale a
 * Ferragosto e in novembre. Non e' una semplificazione accettabile in un
 * gestionale d'albergo — e' la prima cosa che un albergatore guarda — ed e' il
 * motivo per cui questa entita' apre la fase 2.
 *
 * <p><b>Il periodo appartiene alla tipologia e non all'albergo.</b> "Alta
 * stagione" non esiste come riga sua: se tre tipologie hanno la stessa alta
 * stagione, sono tre periodi con le stesse date. Il modello piu' fedele —
 * una stagione dell'albergo, incrociata con la tipologia da una tabella di
 * tariffe — e' stato scartato, e il perche' sta nel commento della migration
 * V9__tariffe_per_periodo.sql insieme alla condizione che lo farebbe riaprire.
 *
 * <p><b>Non copre per forza tutto l'anno</b>, e non deve: le notti che nessun
 * periodo tocca costano il prezzo di listino della tipologia
 * ({@link TipologiaCamera#getPrezzoNotte()}). Un albergo che non configura
 * nessun periodo continua a funzionare come prima di questa entita'.
 *
 * <p><b>Due periodi della stessa tipologia non si sovrappongono</b>: lo
 * garantisce il vincolo di esclusione {@code ex_periodo_tariffario_no_
 * sovrapposizioni} del V9, non questa classe. Senza, "quanto costa il 15
 * agosto" avrebbe due risposte e nessun criterio per sceglierne una.
 */
@Entity
@Table(name = "periodo_tariffario")
@Getter
@Setter
@NoArgsConstructor
public class PeriodoTariffario extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La tipologia a cui questo periodo mette il prezzo.
     *
     * <p>{@code LAZY} come tutte le relazioni del progetto (regola 15), e come
     * per gli ospiti il fetch non serve mai: la risposta non contiene la
     * tipologia — sta gia' nell'URL di chi ha chiesto — quindi nessuna query di
     * questo repository ha bisogno di un {@code @EntityGraph}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tipologia_camera_id", nullable = false)
    private TipologiaCamera tipologiaCamera;

    /**
     * Come lo chiama l'albergatore: "Alta stagione", "Ponte del 2 giugno",
     * "Settimana di Capodanno".
     *
     * <p>Niente unicita', nemmeno dentro la stessa tipologia: "Alta stagione"
     * puo' legittimamente essere due periodi staccati dello stesso anno — luglio
     * e agosto da una parte, Natale dall'altra — e a distinguerli sono le date,
     * che non possono accavallarsi. E' un'etichetta per gli occhi di chi
     * configura, non una chiave.
     */
    @Column(nullable = false, length = 100)
    private String nome;

    /** Prima notte compresa nel periodo. */
    @Column(name = "data_inizio", nullable = false)
    private LocalDate dataInizio;

    /**
     * Ultima notte compresa nel periodo, <b>inclusa</b>.
     *
     * <p>Il periodo si misura in notti e non in giorni di presenza: un periodo
     * che va dal primo al primo agosto e' la notte del primo agosto, cioe' chi
     * arriva il primo e parte il due. E' la stessa aritmetica per cui un
     * soggiorno 10&rarr;13 sono tre notti e il giorno di partenza resta libero
     * per qualcun altro.
     */
    @Column(name = "data_fine", nullable = false)
    private LocalDate dataFine;

    /**
     * Quanto costa una notte di questo periodo, in euro.
     *
     * <p>{@code BigDecimal} per la stessa ragione di
     * {@link TipologiaCamera#getPrezzoNotte()}: e' denaro, e un {@code double}
     * non rappresenta esattamente 0.10 — su un totale moltiplicato per le notti
     * l'errore si vede in fattura.
     *
     * <p>E' il prezzo <b>base</b> del periodo: i singoli giorni della settimana
     * possono scavalcarlo, vedi {@link #prezziGiorno}.
     */
    @Column(name = "prezzo_notte", nullable = false, precision = 10, scale = 2)
    private BigDecimal prezzoNotte;

    /**
     * Quante notti bisogna fermarsi come minimo per prenotare in questo periodo.
     *
     * <p>Uno vuol dire nessun vincolo, ed e' il default della colonna: e' il
     * caso normale fuori stagione. In alta stagione un albergo vende il ponte
     * intero e non la singola notte, ed e' precisamente cio' che questo numero
     * esprime.
     *
     * <p><b>Vale il minimo del periodo della notte di arrivo</b> quando un
     * soggiorno ne attraversa piu' d'uno — la regola sta scritta dove viene
     * applicata, in {@code PeriodoTariffarioRepository.preventivo}.
     */
    @Column(name = "soggiorno_minimo", nullable = false)
    private Integer soggiornoMinimo = 1;

    /**
     * Prezzi che scavalcano {@link #prezzoNotte} per un giorno preciso della
     * settimana. Vuoto vuol dire che tutte le notti del periodo costano uguale.
     *
     * <p><b>E' l'unica collezione mappata del progetto</b>, e la differenza
     * dalle altre sottorisorse — foto e ospiti, che si leggono solo dal loro
     * repository — non e' un ripensamento. Quelle sono elenchi che si guardano
     * di rado e che negli elenchi paginati non devono comparire affatto; questi
     * sono parte di quel che il periodo <i>e'</i>: non esiste un momento in cui
     * si voglia sapere quanto costa un periodo senza sapere anche quanto costa
     * il suo sabato, e la PUT li riscrive insieme al resto in un colpo solo.
     *
     * <p>{@code cascade = ALL} e {@code orphanRemoval} sono cio' che rende
     * possibile quella PUT: si sostituisce il contenuto della lista e Hibernate
     * cancella le righe che non ci sono piu'. Senza {@code orphanRemoval} un
     * giorno tolto dalla richiesta resterebbe in tabella a far prezzo.
     *
     * <p>{@code @BatchSize} e non {@code @EntityGraph} sull'elenco, per la
     * ragione gia' scritta su {@code TipologiaCamera#dotazioni}: un fetch di
     * collezione dentro una query paginata costringe Hibernate a impaginare in
     * memoria. Qui i periodi di una tipologia non sono nemmeno paginati, ma il
     * batch resta la scelta giusta lo stesso — sette righe per periodo, una
     * seconda query per l'intero elenco.
     *
     * <p><b>Nessun {@code @OrderBy}</b>, di proposito. L'enum e' persistito come
     * stringa, quindi ordinare in database vorrebbe dire ordinare i nomi in
     * alfabetico — FRIDAY, MONDAY, SATURDAY... — che non e' una settimana:
     * sarebbe un ordine sbagliato, reso per giunta stabile. L'ordine da lunedi'
     * a domenica lo mette il mapper, dove c'e' l'enum vero col suo ordinale.
     */
    @BatchSize(size = 50)
    @OneToMany(mappedBy = "periodoTariffario", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PrezzoGiornoSettimana> prezziGiorno = new ArrayList<>();

    /**
     * Sostituisce i prezzi per giorno della settimana tenendo i due lati della
     * relazione allineati.
     *
     * <p>Non e' un setter travestito: {@code orphanRemoval} funziona solo se la
     * <b>stessa</b> istanza di lista viene svuotata e riempita — assegnarne una
     * nuova fa perdere a Hibernate le righe da cancellare, ed e' l'errore che
     * questo metodo esiste per rendere impossibile. Valorizza anche il lato
     * proprietario di ogni riga, che e' la colonna che finisce davvero in
     * database.
     */
    public void sostituisciPrezziGiorno(List<PrezzoGiornoSettimana> nuovi) {
        this.prezziGiorno.clear();
        nuovi.forEach(prezzo -> {
            prezzo.setPeriodoTariffario(this);
            this.prezziGiorno.add(prezzo);
        });
    }
}
