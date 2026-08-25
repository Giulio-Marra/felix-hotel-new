package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;

/**
 * La galleria fotografica di una tipologia di camera.
 *
 * <p>E' una <b>sottorisorsa</b>: ogni metodo prende come primo argomento l'id
 * della tipologia, e non per comodita' di firma — una foto esiste solo dentro
 * la galleria a cui appartiene. Non c'e' nessuna operazione che parta dal solo
 * id di un media, e non e' un'omissione: averla vorrebbe dire poter toccare la
 * foto di una tipologia scrivendo nell'URL un'altra.
 *
 * <p>Da questo discendono i due 404 diversi che i metodi qui sotto sollevano
 * con lo stesso tipo di eccezione: <i>la tipologia non esiste</i> e <i>questa
 * foto non e' di questa tipologia</i>. Al client arrivano uguali di proposito
 * (vedi la risposta NotFound nello spec: distinguere direbbe a chi prova
 * identificativi a caso quali sono stati usati davvero); dentro restano due
 * controlli separati.
 *
 * <p>Come gli altri Service del progetto restituisce direttamente la busta
 * standard; gli errori viaggiano come sottoclassi di {@code AppException}. I
 * controlli di ruolo stanno sul Controller, con {@code @PreAuthorize}, e qui si
 * assumono gia' fatti.
 */
public interface MediaCameraService {

    /**
     * Le foto della tipologia, nell'ordine in cui vanno mostrate.
     *
     * <p>Non e' paginato ed e' l'unico elenco del progetto a non esserlo: il
     * tetto che rende la cosa sicura non e' su questa lettura ma su
     * {@link #aggiungi}, che rifiuta la foto oltre la trentesima.
     *
     * <p>Una tipologia senza foto e' una lista vuota; una tipologia che non
     * esiste e' {@code NotFoundException}. I due casi vanno tenuti distinti: chi
     * legge il catalogo deve poter capire se la scheda non ha immagini o se ha
     * chiesto una scheda che non c'e'.
     */
    ApiBaseResponse elenca(Long tipologiaCameraId);

    /**
     * Aggiunge una foto in fondo alla galleria. Solleva
     * {@code NotFoundException} se la tipologia non esiste e
     * {@code ConflictException} se la stessa url e' gia' in questa galleria o se
     * le foto sono gia' al tetto massimo.
     */
    ApiBaseResponse aggiungi(Long tipologiaCameraId, MediaCameraRequest request);

    /**
     * Toglie una foto dalla galleria. Solleva {@code NotFoundException} se la
     * tipologia non esiste, se la foto non esiste, o se esiste ma appartiene a
     * un'altra tipologia.
     *
     * <p>Le foto rimaste non vengono rinumerate: il loro ordine relativo non
     * cambia, e i valori di {@code ordine} non sono osservabili dall'esterno
     * (vedi {@code MediaCamera#ordine}).
     */
    ApiBaseResponse elimina(Long tipologiaCameraId, Long mediaId);

    /**
     * Ridefinisce l'intera sequenza della galleria. Solleva
     * {@code NotFoundException} se la tipologia non esiste e
     * {@code BadRequestException} se l'elenco ricevuto non coincide
     * <b>esattamente</b> con le foto che la tipologia ha adesso — id mancanti,
     * di troppo o ripetuti.
     *
     * <p>E' idempotente: rimandare la sequenza gia' in vigore risponde 200 e non
     * cambia niente.
     */
    ApiBaseResponse riordina(Long tipologiaCameraId, MediaCameraOrdineRequest request);
}
