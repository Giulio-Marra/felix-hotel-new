package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Una foto della galleria di una {@link TipologiaCamera}.
 *
 * <p><b>Non e' un file, e' un indirizzo.</b> L'immagine sta altrove — un CDN,
 * uno spazio oggetti — e questa tabella ne tiene l'elenco e l'ordine. Non
 * esiste nessun endpoint di upload e questa classe non presuppone che ne
 * nascera' uno: il giorno che arrivasse, aggiungerebbe un modo di <i>produrre</i>
 * l'url, non cambierebbe cosa qui viene conservato.
 *
 * <p><b>Sottorisorsa e non risorsa.</b> Una foto non ha senso staccata dalla
 * tipologia a cui appartiene: si indirizza sempre dentro di essa
 * ({@code /api/tipologie-camera/{id}/media/{mediaId}}), e la chiave esterna ha
 * {@code ON DELETE CASCADE} lato database — cancellare una tipologia porta via
 * le sue foto, che senza di lei non vorrebbero dire niente. E' il contrario di
 * quel che succede con le {@link Camera}, dove la chiave esterna e' senza
 * cascata e cancellare una tipologia che ne ha ancora da' 409: una stanza
 * esiste anche se cambia categoria, una fotografia della categoria no.
 *
 * <p>La relazione inversa <b>non c'e'</b>, come per {@link Dotazione}:
 * {@code TipologiaCamera} non tiene una collezione di media. Le foto si leggono
 * sempre e solo dal loro repository, per id di tipologia; una collezione
 * bidirezionale sarebbe un secondo posto da tenere allineato che nessun caso
 * d'uso chiede — e finirebbe caricata insieme alla tipologia proprio negli
 * elenchi paginati dove non serve.
 */
@Entity
@Table(name = "media_camera")
@Getter
@Setter
@NoArgsConstructor
public class MediaCamera extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tipologia a cui la foto appartiene.
     *
     * <p>{@code LAZY} come tutte le relazioni del progetto (regola 15), e qui il
     * fetch non serve <b>mai</b>: la risposta non contiene la tipologia — sta
     * gia' nell'URL di chi ha chiesto — quindi il mapper non tocca questo campo
     * e nessuna query del repository ha bisogno di un {@code @EntityGraph}. E'
     * l'unico caso del progetto in cui una relazione esiste solo per essere
     * scritta e filtrata, mai letta.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tipologia_camera_id", nullable = false)
    private TipologiaCamera tipologiaCamera;

    /**
     * Indirizzo assoluto dell'immagine.
     *
     * <p>Unico dentro la tipologia, con un confronto che <b>distingue le
     * maiuscole</b> — a differenza dei nomi di tipologie, dotazioni e camere
     * (V2, V3, V4). Il vincolo lo garantisce l'indice del
     * V5__unicita_url_media_camera.sql, dove sta scritto anche il perche'.
     */
    @Column(nullable = false, length = 500)
    private String url;

    /**
     * Posizione nella galleria: cresce dalla copertina in poi.
     *
     * <p><b>E' un dettaglio di conservazione, non un dato pubblico</b>: non
     * compare in nessuna risposta. Chi legge le foto le riceve gia' ordinate, e
     * l'ordine e' quello dell'array — vedi {@code MediaCameraResponse} nello
     * spec per il ragionamento completo.
     *
     * <p>Da cio' discendono due cose che sembrano trascuratezze e non lo sono.
     * La prima: i valori <b>possono avere buchi</b>, perche' eliminare una foto
     * non rinumera le altre — l'ordine relativo e' tutto quel che conta, e
     * riscrivere n righe per ricompattare dei numeri che nessuno guarda sarebbe
     * lavoro speso per un'estetica invisibile. La seconda: <b>non c'e' un indice
     * unico</b> sulla coppia (tipologia, ordine). Un vincolo del genere andrebbe
     * dichiarato {@code DEFERRABLE} per sopravvivere a un riordino — Postgres
     * verifica gli indici unici riga per riga dentro l'UPDATE, e scambiare due
     * posizioni passa per uno stato intermedio in cui coincidono — e in cambio
     * garantirebbe l'unicita' di un numero che non e' l'ordine, ma solo il modo
     * in cui l'ordine viene ricordato. La lettura ordina per
     * {@code (ordine, id)}: l'id come spareggio fa si' che due foto che
     * finissero sullo stesso numero restino comunque in un ordine stabile,
     * invece di scambiarsi di posto a ogni chiamata.
     *
     * <p>Inizializzato a zero perche' un {@code new MediaCamera()} nasca valido
     * senza dipendere da chi lo costruisce: il DEFAULT della colonna non scatta
     * mai, Hibernate nomina sempre tutte le colonne nell'INSERT.
     */
    @Column(nullable = false)
    private Integer ordine = 0;
}
