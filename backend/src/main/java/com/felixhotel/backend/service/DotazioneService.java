package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.DotazioneRequest;

/**
 * Gestione dell'elenco delle dotazioni assegnabili alle tipologie di camera:
 * lettura pubblica, scrittura riservata agli ADMIN (il controllo del ruolo sta
 * sul Controller, con {@code @PreAuthorize} — qui si assume gia' fatto).
 *
 * <p>Come gli altri Service del progetto restituisce direttamente la busta
 * standard, scegliendo messaggio e status; gli errori viaggiano invece come
 * sottoclassi di {@code AppException}, impacchettate dal
 * {@code GlobalExceptionHandler}.
 */
public interface DotazioneService {

    /**
     * Pagina di dotazioni in ordine alfabetico di nome.
     *
     * @param page numero di pagina, 0-based
     * @param size quanti elementi per pagina; il tetto e' dichiarato nello
     *             spec OpenAPI e verificato prima di arrivare qui
     */
    ApiBaseResponsePaginated elenca(int page, int size);

    /** Singola dotazione. Solleva {@code NotFoundException} se l'id non esiste. */
    ApiBaseResponse dettaglio(Long id);

    /** Crea una dotazione. Solleva {@code ConflictException} se il nome e' gia' in uso. */
    ApiBaseResponse crea(DotazioneRequest request);

    /**
     * Sostituisce i campi modificabili di una dotazione esistente. Solleva
     * {@code NotFoundException} se l'id non esiste e {@code ConflictException}
     * se il nome richiesto appartiene gia' a un'altra dotazione.
     */
    ApiBaseResponse aggiorna(Long id, DotazioneRequest request);

    /**
     * Elimina una dotazione, insieme ai legami con le tipologie che ce
     * l'avevano. Solleva {@code NotFoundException} se non esiste.
     */
    ApiBaseResponse elimina(Long id);
}
