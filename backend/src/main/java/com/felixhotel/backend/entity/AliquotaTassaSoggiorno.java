package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Quanto si paga di tassa di soggiorno, per persona e per notte, in un intervallo
 * di date — e chi non la paga.
 *
 * <p><b>Perche' e' un'entita' e non due colonne di {@link ImpostazioniHotel}.</b>
 * Il 2026-08-28, scrivendo le impostazioni della struttura, sembrava che
 * bastassero un importo e un'eta'. E' bastato chiedersi <i>chi li cambia</i>
 * (regola 24) per vedere che no: l'importo cambia nel tempo e spesso si
 * stagionalizza, il tetto di notti e' una terza cosa, e le esenzioni sono un
 * elenco. Un'aliquota valida in un periodo e' il modello piu' piccolo che regge
 * tutto questo senza mentire.
 *
 * <p><b>E' dell'albergo e non della tipologia</b>, al contrario di
 * {@link PeriodoTariffario}: il prezzo della camera lo decide chi vende,
 * l'aliquota la decide il comune e vale per chiunque dorma li'. Alcuni
 * regolamenti graduano l'importo per categoria della struttura — le stelle — ma
 * questa installazione ne gestisce <b>una sola</b> (il {@code CHECK (id = 1)} del
 * V8), quindi quella graduazione qui e' gia' risolta.
 *
 * <p><b>Non copre per forza tutto il calendario, e le notti scoperte non si
 * pagano.</b> E' l'opposto di come si comportano le tariffe, dove una notte senza
 * periodo cade sul listino della tipologia, e la differenza ha una ragione: un
 * prezzo mancante e' una configurazione incompleta, una tassa mancante e' il caso
 * legittimo di un comune che non la applica. Non esiste una "tassa di listino" a
 * cui ricadere, e inventarne una vorrebbe dire far pagare qualcosa che nessuno ha
 * deliberato.
 *
 * <p><b>Due aliquote non si sovrappongono</b>: lo garantisce il vincolo di
 * esclusione {@code ex_aliquota_tassa_soggiorno_no_sovrapposizioni} del V11, non
 * questa classe. Senza, quanto si deve per la notte del 15 agosto dipenderebbe
 * dall'ordine in cui il planner restituisce le righe — e su un importo che si
 * versa al comune quella non e' una risposta.
 */
@Entity
@Table(name = "aliquota_tassa_soggiorno")
@Getter
@Setter
@NoArgsConstructor
public class AliquotaTassaSoggiorno extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Prima notte coperta.
     *
     * <p>Gli estremi sono <b>inclusi tutti e due</b> e si misurano in notti, come
     * nel V9: un'aliquota dal primo al 31 agosto copre anche chi arriva il 31 e
     * parte il primo settembre. Ripetere quella scelta invece di cambiarla e' cio'
     * che permette di scrivere i due calcoli — il prezzo e la tassa — con la
     * stessa aritmetica.
     */
    @Column(name = "data_inizio", nullable = false)
    private LocalDate dataInizio;

    /** Ultima notte coperta, compresa. */
    @Column(name = "data_fine", nullable = false)
    private LocalDate dataFine;

    /**
     * L'importo dovuto da <b>una persona per una notte</b>.
     *
     * <p>Zero e' un valore legittimo e non un modo di disattivare l'aliquota: un
     * comune puo' deliberare una sospensione per un periodo, e scriverla come
     * aliquota a zero e' piu' onesto che cancellare la riga — resta agli atti che
     * quel periodo e' stato deciso e non dimenticato.
     */
    @Column(name = "importo_per_persona_notte", nullable = false, precision = 10, scale = 2)
    private BigDecimal importoPerPersonaNotte;

    /**
     * Quante notti di un soggiorno si pagano al massimo; oltre non si paga piu'.
     *
     * <p>E' l'esenzione piu' diffusa dopo quella per eta' — quasi ogni regolamento
     * ne ha una, di solito fra le tre e le sette notti — e serve a non tassare chi
     * in albergo ci vive.
     *
     * <p><b>{@code null} vuol dire nessun tetto, e non zero.</b> Zero direbbe
     * "nessuna notte si paga", che e' una frase diversa e che si scrive gia'
     * mettendo {@link #importoPerPersonaNotte} a zero. Il {@code CHECK} del V11
     * pretende almeno una notte proprio per tenere separate le due cose.
     */
    @Column(name = "notti_massime_tassate")
    private Integer nottiMassimeTassate;

    /**
     * L'eta' sotto la quale non si paga. {@code null} vuol dire che pagano tutti,
     * che e' un caso che esiste davvero.
     *
     * <p><b>Non e' la maggiore eta' del V10, e non va confusa con lei.</b> Quella
     * dice se serve un documento, vale 18 per legge ed e' uguale per ogni albergo
     * d'Italia, quindi sta nel codice
     * ({@code OspiteServiceImpl.MAGGIORE_ETA}); questa la decide il regolamento
     * comunale — dieci anni, dodici, quattordici — quindi sta in una riga di
     * tabella. Somigliarsi non le rende la stessa cosa, e legarle vorrebbe dire
     * che cambiare l'una muove l'altra.
     */
    @Column(name = "eta_esenzione")
    private Integer etaEsenzione;
}
