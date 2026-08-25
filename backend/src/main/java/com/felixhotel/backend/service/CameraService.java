package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.StatoCamera;

/**
 * Inventario delle camere fisiche. E' backoffice: non c'e' nessuna lettura
 * pubblica, a differenza del catalogo — i controlli di ruolo stanno sul
 * Controller, con {@code @PreAuthorize}, e qui si assumono gia' fatti.
 *
 * <p>Come gli altri Service del progetto restituisce direttamente la busta
 * standard; gli errori viaggiano come sottoclassi di {@code AppException}.
 */
public interface CameraService {

    /**
     * Pagina di camere in ordine di numero, con due filtri facoltativi che si
     * combinano fra loro.
     *
     * @param page              numero di pagina, 0-based
     * @param size              quanti elementi per pagina; il tetto e' nello spec
     * @param tipologiaCameraId se null, non filtra per tipologia. Un id che non
     *                          corrisponde a niente non e' un errore: e' una
     *                          pagina vuota
     * @param stato             se null, non filtra per stato
     */
    ApiBaseResponsePaginated elenca(int page, int size, Long tipologiaCameraId, StatoCamera stato);

    /** Singola camera. Solleva {@code NotFoundException} se l'id non esiste. */
    ApiBaseResponse dettaglio(Long id);

    /**
     * Crea una camera. Solleva {@code ConflictException} se il numero e' gia' in
     * uso e {@code BadRequestException} se la tipologia indicata non esiste.
     */
    ApiBaseResponse crea(CameraRequest request);

    /**
     * Sostituisce i campi modificabili di una camera esistente. Solleva
     * {@code NotFoundException} se l'id non esiste, {@code ConflictException} se
     * il numero appartiene a un'altra camera e {@code BadRequestException} se la
     * tipologia indicata non esiste.
     */
    ApiBaseResponse aggiorna(Long id, CameraRequest request);

    /**
     * Elimina una camera. Solleva {@code NotFoundException} se non esiste e
     * {@code ConflictException} se delle prenotazioni la referenziano ancora.
     */
    ApiBaseResponse elimina(Long id);

    /**
     * Porta la camera in un nuovo stato operativo. Solleva
     * {@code NotFoundException} se non esiste.
     *
     * <p>Rimandare lo stato che la camera ha gia' non e' un errore: l'operazione
     * e' idempotente per disegno — chi la ripete perche' non era sicuro che la
     * prima fosse andata a buon fine non deve trovarsi un 409.
     */
    ApiBaseResponse impostaStato(Long id, CameraStatoRequest request);
}
