package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.StatoCamera;
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

/**
 * Camera fisica della struttura: la stanza 101, con la sua porta e il suo letto.
 *
 * <p><b>Non e' quello che il cliente prenota.</b> Le prenotazioni si legano alla
 * {@link TipologiaCamera} — quale stanza tocchera' si decide dopo, tipicamente
 * al check-in — quindi questa entita' vive nel backoffice: serve a sapere quante
 * stanze ci sono, di che tipo, e come sono messe adesso.
 *
 * <p>Il numero e' unico a meno delle maiuscole: il vincolo lo garantisce un
 * indice su {@code lower(numero)} (vedi V4__unicita_numero_camera.sql), non
 * questa classe.
 */
@Entity
@Table(name = "camera")
@Getter
@Setter
@NoArgsConstructor
public class Camera extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Numero o sigla come sta scritto sulla porta.
     *
     * <p>Testo e non intero, e non e' una svista: "12A" e "S3" sono numeri di
     * camera veri quanto "101", e trattarli come numeri obbligherebbe a
     * inventarsi una regola per gli alberghi che non li usano cosi'. Il prezzo
     * e' che l'ordinamento e' alfabetico — "10" viene prima di "9" — ed e'
     * accettato: e' l'ordine in cui sono scritte le targhette, non un ordine
     * matematico.
     */
    @Column(nullable = false, length = 20)
    private String numero;

    /** Piano su cui si trova. Lo zero e' il piano terra ed e' un valore valido. */
    @Column(nullable = false)
    private Integer piano;

    /**
     * Tipologia a cui la camera appartiene, cioe' cosa offre e quanto costa.
     *
     * <p>{@code LAZY} come tutte le relazioni del progetto, quindi va caricata
     * dalla query che la usera' (regola 15): il repository lo fa con
     * {@code @EntityGraph}. Qui, a differenza delle dotazioni di una tipologia,
     * l'{@code @EntityGraph} va bene <b>anche sull'elenco paginato</b> — e' un
     * {@code ManyToOne}, quindi un join che non moltiplica le righe e non
     * costringe Hibernate a impaginare in memoria.
     *
     * <p>La chiave esterna e' senza cascata di proposito, lato database: una
     * tipologia non si porta via le sue camere, e cancellarla mentre ne ha
     * ancora da' 409.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tipologia_camera_id", nullable = false)
    private TipologiaCamera tipologiaCamera;

    /**
     * Com'e' messa adesso. Vedi {@link StatoCamera} per cosa vuol dire — e
     * soprattutto per cosa <b>non</b> vuol dire.
     *
     * <p>{@code EnumType.STRING} e non ORDINAL: la colonna e' un VARCHAR con un
     * CHECK sui nomi, e salvare l'ordinale vorrebbe dire che riordinare le
     * costanti dell'enum riscrive silenziosamente lo stato di tutte le camere
     * gia' a database.
     *
     * <p><b>Il DEFAULT della colonna non scatta mai</b>: vale per gli INSERT che
     * non nominano la colonna, e Hibernate la nomina sempre. Chi decide lo stato
     * iniziale e' quindi il codice Java, in due punti che dicono la stessa cosa
     * per ragioni diverse — il Service lo imposta a ogni scrittura perche' e'
     * una PUT e un campo omesso torna al suo valore di partenza; questo
     * inizializzatore fa si' che anche un {@code new Camera()} nasca valido,
     * senza dipendere da chi lo costruisce.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatoCamera stato = StatoCamera.LIBERA;
}
