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

import java.time.LocalDate;

/**
 * Una persona che dorme in albergo, col documento che ha esibito.
 *
 * <p><b>Non e' un account e non diventera' mai tale.</b> {@link Utente} e'
 * qualcuno che si autentica e prenota; questa e' una riga di registro, e la
 * differenza non e' terminologica: un ospite puo' non aver mai visto il sito,
 * puo' essere un bambino, e nella stragrande maggioranza dei casi
 * dell'albergo non conserva niente oltre a quel che la legge impone di
 * conservare. Non ha email, non ha password, non ha un ruolo.
 *
 * <p><b>Perche' esiste.</b> Il TULPS — l'articolo 109, quello della "schedina
 * alloggiati" — obbliga a registrare il documento di <i>ogni persona che
 * soggiorna</i>, non del solo intestatario della prenotazione. Prima di questa
 * tabella l'applicazione sapeva chi aveva pagato e quante persone erano
 * ({@code numero_ospiti}), ma non chi fossero: un numero non e' un registro.
 *
 * <p><b>Sottorisorsa e non risorsa</b>, esattamente come {@link MediaCamera} su
 * {@link TipologiaCamera}: un ospite non ha senso staccato dal soggiorno per cui
 * e' stato registrato, si indirizza sempre dentro di esso
 * ({@code /api/prenotazioni/{id}/ospiti/{ospiteId}}), e la chiave esterna ha
 * {@code ON DELETE CASCADE} lato database.
 *
 * <p>La relazione inversa <b>non c'e'</b>, come per le foto e per le dotazioni:
 * {@code Prenotazione} non tiene una collezione di ospiti. Si leggono sempre e
 * solo dal loro repository, per id di prenotazione — e qui la ragione e' piu'
 * forte che altrove, perche' una collezione mappata finirebbe caricata insieme
 * alla prenotazione proprio negli elenchi che il cliente vede, dove questi dati
 * non devono comparire affatto.
 */
@Entity
@Table(name = "ospite")
@Getter
@Setter
@NoArgsConstructor
public class Ospite extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Il soggiorno per cui questa persona e' registrata.
     *
     * <p>{@code LAZY} come tutte le relazioni del progetto (regola 15), e come
     * per le foto il fetch non serve <b>mai</b>: la risposta non contiene la
     * prenotazione — sta gia' nell'URL di chi ha chiesto — quindi il mapper non
     * tocca questo campo e nessuna query del repository ha bisogno di un
     * {@code @EntityGraph}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prenotazione_id", nullable = false)
    private Prenotazione prenotazione;

    /**
     * Nome come sta scritto sul documento.
     *
     * <p>Niente indice, niente unicita': due fratelli con lo stesso nome e
     * cognome sulla stessa prenotazione sono possibili, e a distinguerli e' il
     * documento.
     */
    @Column(nullable = false, length = 100)
    private String nome;

    /** Cognome come sta scritto sul documento. */
    @Column(nullable = false, length = 100)
    private String cognome;

    /**
     * Che documento e' stato esibito, oppure {@code null} se chi soggiorna non ne
     * ha uno perche' e' minorenne.
     *
     * <p><b>Facoltativo dal V10, e viaggia in coppia con {@link #numeroDocumento}</b>:
     * o ci sono tutti e due o non c'e' nessuno dei due. Un tipo senza numero e' mezzo
     * dato, e chi lo rileggesse non saprebbe se il numero manca perche' non esiste o
     * perche' qualcuno si e' fermato a meta' del modulo. A tenerli insieme non e' il
     * database — un {@code CHECK} sulle due colonne si potrebbe scrivere — ma il
     * Service, che e' anche l'unico posto in cui si sa se la persona e' maggiorenne
     * il giorno in cui arriva, cioe' l'unico posto in cui la coppia mancante e' un
     * errore invece che il caso normale.
     *
     * <p>{@code EnumType.STRING} come ovunque nel progetto: l'ordinale renderebbe
     * il significato delle righe dipendente dall'ordine in cui i valori sono
     * scritti nel sorgente, e riordinarli e' una modifica che nessuno considera
     * pericolosa.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", length = 30)
    private TipoDocumento tipoDocumento;

    /**
     * Il numero come sta stampato sul documento.
     *
     * <p>Unico dentro la prenotazione, con un confronto che <b>distingue le
     * maiuscole</b> — come gli url delle foto e al contrario dei nomi di
     * tipologie, dotazioni e camere. Il motivo e' lo stesso: non e' un nome che
     * una persona scrive a modo suo, e' una stringa emessa da un'autorita'.
     * Il vincolo lo garantisce l'indice del
     * V7__unicita_documento_ospite.sql, dove sta scritto anche perche' quel
     * doppione fa danno invece di essere solo ridondante.
     *
     * <p><b>Null per i minorenni</b>, come {@link #tipoDocumento}. Due righe senza
     * documento sulla stessa prenotazione non violano l'indice unico, perche' in
     * Postgres due NULL non collidono: e' quel che serve, visto che dove non c'e' un
     * documento non c'e' niente da confrontare.
     */
    @Column(name = "numero_documento", length = 50)
    private String numeroDocumento;

    /**
     * Data di nascita. <b>Obbligatoria dal V10</b>, dove prima era l'unico campo
     * facoltativo della tabella.
     *
     * <p>L'inversione ha una ragione sola: e' questa colonna a dire se il documento
     * ci deve essere. Finche' poteva mancare, l'assenza del documento non si poteva
     * ne' accettare ne' rifiutare senza indovinare — "e' un bambino" e "il modulo e'
     * incompleto" avevano lo stesso aspetto. Da qui in avanti chi registra un ospite
     * dice sempre quando e' nato, ed e' il dato da cui discende tutto il resto.
     *
     * <p>Ci si appoggera' anche l'esenzione per eta' della tassa di soggiorno, che
     * senza un campo sempre valorizzato sarebbe una stima e non un calcolo.
     */
    @Column(name = "data_nascita", nullable = false)
    private LocalDate dataNascita;
}
