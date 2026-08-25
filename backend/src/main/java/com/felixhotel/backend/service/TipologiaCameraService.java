package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.TipologiaCameraDotazioniRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;

/**
 * Gestione del catalogo delle tipologie di camera: lettura pubblica,
 * scrittura riservata agli ADMIN (il controllo del ruolo sta sul Controller,
 * con {@code @PreAuthorize} — qui si assume gia' fatto).
 *
 * <p>I metodi restituiscono direttamente la busta standard: e' il Service a
 * sapere com'e' andata l'operazione, quindi e' lui a scegliere messaggio e
 * status, e il Controller si limita a rigirare quello che riceve. Gli errori
 * viaggiano invece come sottoclassi di {@code AppException}, impacchettate
 * dal {@code GlobalExceptionHandler}.
 */
public interface TipologiaCameraService {

    /**
     * Pagina di tipologie in ordine alfabetico di nome.
     *
     * @param page numero di pagina, 0-based
     * @param size quanti elementi per pagina; il tetto e' dichiarato nello
     *             spec OpenAPI e verificato prima di arrivare qui
     */
    ApiBaseResponsePaginated elenca(int page, int size);

    /** Singola tipologia. Solleva {@code NotFoundException} se l'id non esiste. */
    ApiBaseResponse dettaglio(Long id);

    /** Crea una tipologia. Solleva {@code ConflictException} se il nome e' gia' in uso. */
    ApiBaseResponse crea(TipologiaCameraRequest request);

    /**
     * Sostituisce i campi modificabili di una tipologia esistente. Solleva
     * {@code NotFoundException} se l'id non esiste e {@code ConflictException}
     * se il nome richiesto appartiene gia' a un'altra tipologia.
     */
    ApiBaseResponse aggiorna(Long id, TipologiaCameraRequest request);

    /**
     * Elimina una tipologia. Solleva {@code NotFoundException} se non esiste e
     * {@code ConflictException} se qualcosa la referenzia ancora.
     */
    ApiBaseResponse elimina(Long id);

    /**
     * Sostituisce l'intero insieme delle dotazioni assegnate alla tipologia:
     * quelle elencate restano o vengono aggiunte, quelle assenti staccate. Una
     * lista vuota le toglie tutte, ed e' un valore legittimo.
     *
     * <p>Solleva {@code NotFoundException} se la tipologia non esiste e
     * {@code BadRequestException} se uno degli id non corrisponde a nessuna
     * dotazione — due status diversi di proposito: il 404 dice "la tipologia
     * che hai indicato nell'URL non c'e'", il 400 "gli id che mi hai mandato
     * dentro non sono buoni", e sono due cose da riparare in modo diverso.
     */
    ApiBaseResponse impostaDotazioni(Long id, TipologiaCameraDotazioniRequest request);
}
