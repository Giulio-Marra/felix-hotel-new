package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.DayOfWeek;

/**
 * Il prezzo di un giorno preciso della settimana dentro un
 * {@link PeriodoTariffario}: "in alta stagione il sabato costa 180 invece di
 * 150".
 *
 * <p><b>E' uno scavalcamento, non un listino.</b> Non c'e' nessun obbligo di
 * riempire tutti e sette i giorni: le notti che non hanno una riga qui costano
 * il prezzo base del periodo. Il caso normale sono una o due righe, ed e' il
 * motivo per cui questa e' una tabella e non sette colonne.
 *
 * <p><b>Perche' non due colonne {@code prezzoFeriale}/{@code prezzoWeekend}</b>,
 * che sarebbero state molto meno codice: quella forma pretende che "quali giorni
 * sono weekend" stia scritto nel programma, e due alberghi lo vogliono diverso —
 * un rifugio di montagna ha il picco venerdi' e sabato, un hotel di citta' che
 * vive di trasferte ce l'ha dal lunedi' al giovedi'. Per la regola 24 cio' che
 * due alberghi vogliono diverso non e' codice, e' configurazione.
 */
@Entity
@Table(name = "prezzo_giorno_settimana")
@Getter
@Setter
@NoArgsConstructor
public class PrezzoGiornoSettimana extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Il periodo di cui questa riga scavalca il prezzo.
     *
     * <p>E' il <b>lato proprietario</b> della relazione: la collezione su
     * {@link PeriodoTariffario} e' {@code mappedBy} e non scrive niente da sola.
     * Chi aggiunge una riga deve valorizzare questo campo, ed e' esattamente il
     * passo che {@code PeriodoTariffario.sostituisciPrezziGiorno} esiste per non
     * lasciare a chi chiama.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "periodo_tariffario_id", nullable = false)
    private PeriodoTariffario periodoTariffario;

    /**
     * Quale giorno della settimana.
     *
     * <p>{@link DayOfWeek} della libreria standard e non un enum di progetto:
     * il giorno di una notte si ricava da {@code LocalDate.getDayOfWeek()}, e
     * un enum nostro vorrebbe dire una tabella di conversione fra due elenchi
     * identici — cioe' un posto in piu' dove sbagliare senza guadagnarci niente.
     *
     * <p>{@code EnumType.STRING} come ovunque nel progetto: l'ordinale
     * renderebbe il significato delle righe dipendente dall'ordine in cui i
     * valori stanno scritti, e per {@link DayOfWeek} quell'ordine non lo
     * decidiamo nemmeno noi.
     *
     * <p>Unico dentro il periodo — lo garantisce l'indice
     * {@code uq_prezzo_giorno_settimana_periodo_giorno} del V9 — perche' due
     * prezzi per lo stesso sabato sono la stessa ambiguita' di due periodi
     * sovrapposti, in piccolo.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DayOfWeek giorno;

    /**
     * Quanto costa una notte di quel giorno, in euro. Sostituisce del tutto il
     * prezzo base del periodo, non ci si somma: e' un prezzo, non un
     * supplemento.
     *
     * <p>La differenza conta per chi configura — scrivere 30 volendo dire
     * "trenta euro in piu'" farebbe costare il sabato meno degli altri giorni —
     * ed e' scritta anche nella descrizione del campo nello spec, che e' dove la
     * legge chi usa l'API.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal prezzo;
}
