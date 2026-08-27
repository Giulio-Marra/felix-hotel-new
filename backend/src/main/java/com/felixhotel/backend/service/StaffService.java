package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffAggiornamentoRequest;
import com.felixhotel.backend.dto.StaffAttivazioneRequest;
import com.felixhotel.backend.dto.StaffPasswordRequest;
import com.felixhotel.backend.dto.StaffRequest;

/**
 * Gestione degli account del personale. E' l'unico modo di far nascere uno
 * STAFF o un ADMIN: la registrazione pubblica crea solo clienti, e fino a
 * questo Service un account di backoffice si creava soltanto con una
 * {@code INSERT} scritta a mano.
 *
 * <p>Tutto qui dentro e' riservato agli ADMIN. Il controllo di ruolo sta sul
 * Controller, con {@code @PreAuthorize}, e qui si assume gia' fatto — come per
 * ogni altra risorsa del progetto.
 *
 * <p>Come gli altri Service restituisce direttamente la busta standard; gli
 * errori viaggiano come sottoclassi di {@code AppException}.
 */
public interface StaffService {

    /**
     * Pagina di account in ordine di cognome e nome, con due filtri facoltativi
     * che si combinano fra loro.
     *
     * @param page   numero di pagina, 0-based
     * @param size   quanti elementi per pagina; il tetto e' nello spec
     * @param ruolo  se null, non filtra per ruolo
     * @param attivo se null, non filtra per attivazione — ed e' il caso normale:
     *               l'elenco mostra anche chi e' stato disattivato, altrimenti
     *               non lo si potrebbe piu' ritrovare per riattivarlo
     */
    ApiBaseResponsePaginated elenca(int page, int size, RuoloStaff ruolo, Boolean attivo);

    /** Singolo account. Solleva {@code NotFoundException} se l'id non esiste. */
    ApiBaseResponse dettaglio(Long id);

    /**
     * Crea un account del personale, gia' attivo. Solleva
     * {@code ConflictException} se l'email e' gia' usata da un altro account —
     * del personale o di un cliente, perche' il login e' uno solo.
     */
    ApiBaseResponse crea(StaffRequest request);

    /**
     * Sostituisce i campi anagrafici e il ruolo di un account esistente. Non
     * tocca la password ne' l'attivazione, che hanno i loro metodi.
     *
     * <p>Solleva {@code NotFoundException} se l'id non esiste e
     * {@code ConflictException} in due casi diversi: l'email appartiene a
     * qualcun altro, oppure l'operazione toglierebbe il ruolo ADMIN all'ultimo
     * amministratore attivo rimasto.
     */
    ApiBaseResponse aggiorna(Long id, StaffAggiornamentoRequest request);

    /**
     * Apre o chiude l'accesso di un account, senza cancellarlo. Solleva
     * {@code NotFoundException} se l'id non esiste e {@code ConflictException}
     * se disattiverebbe l'ultimo amministratore attivo.
     *
     * <p>Rimandare lo stato che l'account ha gia' non e' un errore: come per lo
     * stato delle camere, l'operazione e' idempotente per disegno.
     */
    ApiBaseResponse impostaAttivazione(Long id, StaffAttivazioneRequest request);

    /**
     * Sostituisce la password di un account. Solleva {@code NotFoundException}
     * se l'id non esiste.
     *
     * <p>Non chiede la password precedente: chi chiama e' un ADMIN che sta
     * rimediando per conto d'altri, non il titolare dell'account.
     */
    ApiBaseResponse impostaPassword(Long id, StaffPasswordRequest request);
}
