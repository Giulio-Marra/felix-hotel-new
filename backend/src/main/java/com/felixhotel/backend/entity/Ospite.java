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

    /**
     * Perche' questa persona non paga la tassa di soggiorno, se e' uno dei motivi
     * che qualcuno deve dichiarare. {@code null} e' il caso normale.
     *
     * <p><b>Qui non finiscono l'eta' e le notti lunghe</b>, che sono le altre due
     * esenzioni: quelle il calcolo le deduce da {@link #dataNascita} e dalle date
     * della prenotazione, e scriverle anche qui vorrebbe dire due fonti per lo
     * stesso fatto — col giorno in cui non sono d'accordo e nessun criterio per
     * decidere quale delle due ha ragione. Il perche' esteso della divisione sta su
     * {@link MotivoEsenzione}.
     *
     * <p><b>Lo scrive il personale, guardando un tesserino</b>: residenza,
     * disabilita', servizio, ricovero sono fatti del mondo che nessun dato di
     * questa applicazione contiene. E' anche il motivo per cui non c'e' nessun
     * controllo che lo verifichi: non esiste niente contro cui verificarlo, e
     * l'unica cosa che il codice puo' fare e' registrare chi l'ha dichiarato —
     * cosa che l'audit di {@link BaseAuditableEntity} gia' non fa, e che diventera'
     * una domanda vera solo il giorno in cui un controllo del comune la porra'.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_esenzione", length = 40)
    private MotivoEsenzione motivoEsenzione;

    /**
     * Che ruolo ha questa persona nel gruppo che soggiorna: e' il primo campo della
     * schedina alloggiati e decide quanto del resto va compilato.
     *
     * <p><b>Facoltativo, come le cinque colonne che seguono</b>, e per una ragione
     * che vale per tutte e sei — scritta per esteso nel
     * V13__schedina_alloggiati.sql e riassunta qui perche' e' la cosa da sapere
     * prima di toccarle: <i>il registro e la schedina sono due obblighi con due
     * momenti</i>. Al banco si scrive chi e' arrivato, anche alle due di notte; la
     * schedina si manda entro ventiquattro ore. Un campo mancante deve fermare la
     * seconda, non la prima — quindi a pretenderlo non e' ne' il database ne'
     * {@code OspiteServiceImpl}, ma l'export, che risponde 409 nominando la persona
     * e il campo.
     *
     * <p>{@code EnumType.STRING} come ovunque, e con in piu' un {@code CHECK} in
     * database che {@link #tipoDocumento} non ha: la differenza e' la stessa gia'
     * scritta su {@link TipoCodifica} — questo elenco lo scrive l'applicazione,
     * quello lo cambia la Questura.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_alloggiato", length = 30)
    private TipoAlloggiato tipoAlloggiato;

    /** {@code M} o {@code F}. Il perche' siano due sta su {@link Sesso}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 1)
    private Sesso sesso;

    /**
     * Il codice del comune italiano di nascita, nella codifica del Ministero
     * ({@link TipoCodifica#COMUNE}). Vuoto per chi e' nato all'estero, che ha
     * invece {@link #statoNascita}.
     *
     * <p><b>E' una stringa e non una relazione verso {@link VoceCodifica}</b>, ed
     * e' la decisione da capire prima delle altre perche' contraddice l'istinto.
     * Una chiave esterna legherebbe questa riga alla tabella dei comuni, che
     * l'import del Ministero <b>sostituisce per intero</b> (V12): il giorno di una
     * fusione di comuni, l'aggiornamento fallirebbe per colpa di ospiti registrati
     * anni prima, oppure — con un cascade — riscriverebbe schedine gia' mandate.
     * Il codice qui e' <b>una fotografia</b>, come {@code importoTotale} sulla
     * prenotazione: dice cosa e' stato dichiarato quel giorno, e non deve muoversi
     * piu'. Che il codice esistesse lo ha verificato il Service quando l'ha scritto.
     *
     * <p>La <b>provincia</b> non e' una colonna, benche' il tracciato la chieda: sta
     * gia' sulla riga di {@link VoceCodifica} di questo comune, e copiarla qui
     * sarebbe una seconda fonte per lo stesso fatto. La legge l'export.
     */
    @Column(name = "comune_nascita", length = 20)
    private String comuneNascita;

    /**
     * Il codice dello stato estero di nascita ({@link TipoCodifica#STATO}), per chi
     * in Italia non e' nato. <b>Esclusivo rispetto a {@link #comuneNascita}</b>: il
     * tracciato ha due caselle e ne vuole compilata esattamente una, e che non siano
     * valorizzate tutte e due lo vieta un {@code CHECK} del V13 — nessuno nasce in
     * due posti, in nessun momento della vita della riga.
     */
    @Column(name = "stato_nascita", length = 20)
    private String statoNascita;

    /**
     * Il codice dello stato di cittadinanza ({@link TipoCodifica#STATO}).
     *
     * <p><b>Non si deduce dal luogo di nascita</b>, ed e' il motivo per cui e' una
     * colonna sua invece di un calcolo: chi e' nato a Milano da genitori stranieri
     * puo' non essere cittadino italiano, e chi e' nato all'estero puo' esserlo. Il
     * modulo chiede due cose perche' sono due.
     */
    @Column(length = 20)
    private String cittadinanza;

    /**
     * Dove e' stato rilasciato il documento: il codice di un comune italiano
     * <b>oppure</b> di uno stato estero, in una colonna sola perche' il tracciato ha
     * una casella sola.
     *
     * <p>E' l'unico campo del progetto che puo' contenere un codice di due famiglie
     * diverse, e la conseguenza va saputa: a validarlo si guarda in tutte e due, e
     * un codice che per caso esiste in entrambe passa senza che nessuno sappia quale
     * dei due era. Nel tracciato non fa differenza — e' la stessa casella — ma se un
     * giorno qualcosa dovesse distinguerli, e' qui che manca l'informazione.
     *
     * <p>Vuoto per chi un documento non ce l'ha: i minorenni registrati senza (V10) e
     * chi e' {@link TipoAlloggiato#FAMILIARE} o {@link TipoAlloggiato#MEMBRO_GRUPPO}.
     */
    @Column(name = "luogo_rilascio_documento", length = 20)
    private String luogoRilascioDocumento;
}
