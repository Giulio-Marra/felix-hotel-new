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

/**
 * Servizio o comodita' che una tipologia di camera puo' offrire (Wi-Fi, aria
 * condizionata, minibar, vista mare). E' un elenco chiuso gestito dagli ADMIN:
 * non si scrive a mano sulla singola camera, si sceglie fra quelle esistenti —
 * altrimenti "Wi-Fi", "WiFi" e "wi fi" convivrebbero nel catalogo e nessun
 * filtro potrebbe piu' metterle insieme.
 *
 * <p>Il legame con le tipologie e' many-to-many e vive sull'altro lato: e'
 * {@link TipologiaCamera} a dichiarare la {@code @JoinTable}. Qui non c'e'
 * nessuna collezione inversa, di proposito — non serve a nessun caso d'uso di
 * oggi ("quali camere hanno il minibar?" non e' una domanda che il sito fa), e
 * una relazione bidirezionale che nessuno legge e' solo un secondo posto da
 * tenere allineato.
 *
 * <p>Il nome e' unico a meno delle maiuscole: il vincolo lo garantisce un
 * indice su {@code lower(nome)} (vedi V3__unicita_nome_dotazione.sql), non
 * questa classe.
 */
@Entity
@Table(name = "dotazione")
@Getter
@Setter
@NoArgsConstructor
public class Dotazione extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome mostrato nella scheda della camera. */
    @Column(nullable = false, length = 100)
    private String nome;

    /** Dettaglio facoltativo di cosa comprende. La colonna e' un VARCHAR(255). */
    @Column(length = 255)
    private String descrizione;
}
