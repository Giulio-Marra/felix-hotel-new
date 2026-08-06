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

/**
 * Tipologia di camera offerta dalla struttura (Singola, Doppia Superior,
 * Suite...): e' l'unita' del catalogo e insieme cio' che il cliente prenota.
 * Le camere fisiche ({@code Camera}) appartengono a una tipologia, ma una
 * prenotazione si lega alla tipologia e non alla singola camera — quale
 * stanza tocchera' si decide dopo, tipicamente al check-in.
 *
 * <p>Il nome e' unico a meno delle maiuscole: il vincolo lo garantisce un
 * indice su {@code lower(nome)} (vedi V2__unicita_nome_tipologia_camera.sql),
 * non questa classe.
 */
@Entity
@Table(name = "tipologia_camera")
@Getter
@Setter
@NoArgsConstructor
public class TipologiaCamera extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome commerciale mostrato nel catalogo. */
    @Column(nullable = false, length = 100)
    private String nome;

    /** Testo libero della scheda. La colonna e' un TEXT, non un varchar. */
    @Column(columnDefinition = "text")
    private String descrizione;

    /** Quante persone possono alloggiarci. Il DDL impone che sia > 0. */
    @Column(name = "capienza_max", nullable = false)
    private Integer capienzaMax;

    /**
     * Prezzo per notte, in euro.
     *
     * <p>{@code BigDecimal} e non {@code double}: e' denaro, e i decimali
     * binari non rappresentano esattamente valori come 0.10 — su un importo
     * moltiplicato per il numero di notti l'errore diventa visibile in fattura.
     * Precisione e scala ricalcano la colonna NUMERIC(10,2).
     *
     * <p>E' un prezzo fisso, segnaposto per scelta: il sistema di tariffe
     * stagionali e' deliberatamente rimandato (vedi ANALISI_FUNZIONALE). Le
     * prenotazioni non ne dipendono nel tempo — si portano dietro il proprio
     * {@code importoTotale} calcolato alla creazione — quindi cambiare questo
     * valore non riscrive la storia di quelle gia' fatte.
     */
    @Column(name = "prezzo_notte", nullable = false, precision = 10, scale = 2)
    private BigDecimal prezzoNotte;
}
