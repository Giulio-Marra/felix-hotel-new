package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

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
     * <p><b>E' il prezzo di listino, non l'unico prezzo</b>, e dal 2026-09-01
     * non e' piu' un segnaposto. I {@link PeriodoTariffario} lo scavalcano nelle
     * date che dichiarano, e dentro un periodo i singoli giorni della settimana
     * possono scavalcare a loro volta: questo vale per le notti che <b>nessun
     * periodo copre</b>. Non e' rimasto per compatibilita' — e' il livello che
     * garantisce che ogni notte abbia un prezzo, cosi' che un albergo col
     * calendario configurato a meta' venda lo stesso invece di smettere di
     * vendere il primo gennaio.
     *
     * <p>Il prezzo di una notte si guarda quindi in due posti, e l'ordine in cui
     * si provano sta scritto una volta sola: nel {@code coalesce} della query di
     * {@code PeriodoTariffarioRepository.preventivi}.
     *
     * <p>Le prenotazioni non ne dipendono nel tempo — si portano dietro il
     * proprio {@code importoTotale} fotografato alla creazione — quindi cambiare
     * questo valore non riscrive la storia di quelle gia' fatte.
     */
    @Column(name = "prezzo_notte", nullable = false, precision = 10, scale = 2)
    private BigDecimal prezzoNotte;

    /**
     * Dotazioni offerte da questa tipologia. La relazione e' many-to-many e la
     * tabella di legame esiste dal V1.
     *
     * <p><b>Il lato proprietario e' questo</b>, ed e' l'unico che c'e':
     * {@link Dotazione} non ha la collezione inversa perche' nessun caso d'uso
     * la chiede, e una relazione bidirezionale che nessuno legge sarebbe solo
     * un secondo posto da tenere allineato.
     *
     * <p><b>{@code @BatchSize} e non {@code @EntityGraph} sull'elenco.</b> La
     * differenza conta e non e' un dettaglio di ottimizzazione: chiedere il
     * fetch di una collezione dentro una query paginata costringe Hibernate a
     * caricare <b>tutte</b> le righe e a impaginare in memoria (avvisa con
     * HHH90003004), cioe' a fare l'esatto contrario di quel che la paginazione
     * serve a evitare. Col batch la pagina resta decisa dal database e le
     * dotazioni arrivano con una seconda query sola per l'intera pagina, invece
     * di una per riga.
     *
     * <p>Il 50 non e' un numero a caso: la pagina piu' grande che il contratto
     * permette e' di 100 elementi (il tetto su {@code size}), quindi con questo
     * valore l'elenco piu' pesante possibile costa <b>due</b> query in tutto.
     * Se un domani quel tetto cambia, va rivisto anche questo — sono lo stesso
     * numero visto da due punti diversi.
     *
     * <p>Sul <b>dettaglio</b> non c'e' quel problema — una riga sola, nessuna
     * paginazione — e infatti li' il repository usa {@code @EntityGraph}: e' la
     * regola 15 applicata alla query che ne ha bisogno, non all'entita'.
     *
     * <p>{@code LinkedHashSet} inizializzato subito: un {@code Set} perche' la
     * chiave primaria della tabella di legame e' la coppia (tipologia,
     * dotazione) e la stessa dotazione non puo' comparire due volte; gia'
     * costruito perche' un'entity appena creata deve poter ricevere
     * {@code addAll} senza che nessuno debba ricordarsi di inizializzarla.
     */
    @BatchSize(size = 50)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tipologia_camera_dotazione",
            joinColumns = @JoinColumn(name = "tipologia_camera_id"),
            inverseJoinColumns = @JoinColumn(name = "dotazione_id"))
    private Set<Dotazione> dotazioni = new LinkedHashSet<>();
}
