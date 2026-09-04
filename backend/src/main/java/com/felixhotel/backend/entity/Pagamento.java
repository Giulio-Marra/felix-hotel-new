package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.MetodoPagamento;
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
import java.time.LocalDateTime;

/**
 * Un versamento incassato su una prenotazione.
 *
 * <p><b>Un soggiorno si paga a rate, quindi i pagamenti sono righe e non colonne.</b>
 * Caparra alla conferma, saldo all'arrivo, a volte un acconto in mezzo: due colonne
 * sulla prenotazione reggono il primo versamento e mentono dal secondo in poi.
 *
 * <p><b>Non esiste un campo che dica se questa riga sia "la caparra" o "il saldo"</b>, ed
 * e' la decisione portante dell'entita': la caparra dovuta e' una percentuale
 * dell'importo totale e il residuo e' una sottrazione, quindi sono cose che si
 * <i>ricavano</i>. Scriverle qui vorrebbe dire la stessa verita' in due posti, con la
 * seconda libera di divergere dalla prima — e in un registro di denaro due numeri che non
 * tornano sono peggio di un numero solo.
 *
 * <p><b>Per la stessa ragione la prenotazione non porta uno "stato del pagamento".</b>
 * Confermata-e-pagata contro confermata-e-in-attesa e' una domanda a cui risponde una
 * somma, e una somma non va mai fuori sincrono con i suoi addendi. Non e' nemmeno un
 * sesto valore di {@link com.felixhotel.backend.entity.enums.StatoPrenotazione}: quello
 * dice chi occupa una camera, e una prenotazione confermata occupa la stanza tanto se e'
 * pagata quanto se non lo e'.
 *
 * <p><b>L'importo e' sempre positivo: un rimborso non e' un pagamento negativo.</b>
 * Sommarlo qui renderebbe illeggibili tutti e due i conti — quanto e' entrato e quanto e'
 * uscito — e un rimborso ha comunque una causale e un'autorizzazione che questa tabella
 * non ha. Quando servira' sara' un'entita' sua.
 */
@Entity
@Table(name = "pagamento")
@Getter
@Setter
@NoArgsConstructor
public class Pagamento extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Il soggiorno che si sta pagando. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prenotazione_id", nullable = false)
    private Prenotazione prenotazione;

    /**
     * Quanto e' stato versato, in euro.
     *
     * <p>{@code NUMERIC(10,2)} come ogni altro importo del progetto — l'importo totale
     * della prenotazione, il prezzo di una notte, l'aliquota della tassa di soggiorno —
     * cosi' le somme non passano mai da un tipo all'altro.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal importo;

    /** Come e' arrivato il denaro. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetodoPagamento metodo;

    /**
     * <b>Quando il denaro e' arrivato</b>, che non e' quando qualcuno l'ha scritto qui.
     *
     * <p>Le due date divergono di continuo: un bonifico si vede sul conto il lunedi' e lo
     * si registra il martedi'. Chi riconcilia con l'estratto conto ha bisogno della
     * prima, e {@code createdAt} continua a dire la seconda — sono due informazioni, e
     * tenerne una sola vorrebbe dire perdere quella che serve.
     */
    @Column(name = "incassato_il", nullable = false)
    private LocalDateTime incassatoIl;

    /**
     * Il numero del bonifico, l'identificativo della transazione POS, la ricevuta.
     *
     * <p>Facoltativo perche' i contanti non ne hanno uno, e inventarne uno sarebbe peggio
     * che lasciarlo vuoto.
     */
    @Column(length = 100)
    private String riferimento;

    /**
     * Chi ha registrato l'incasso.
     *
     * <p><b>Un registro di denaro senza il nome di chi ci scrive non serve a niente il
     * giorno in cui i conti non tornano.</b>
     *
     * <p>Nullable, e la ragione vale ora e varra' di piu' dopo: un pagamento che arriva
     * da un fornitore non lo registra nessuno, lo registra un webhook. Oggi ogni riga ce
     * l'ha valorizzato, perche' oggi l'unico modo di incassare e' che qualcuno lo scriva.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registrato_da_staff_id")
    private Staff registratoDa;
}
