package com.felixhotel.backend.exception;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gestione centralizzata delle eccezioni: converte tutto quello che risale
 * dai Controller nella stessa busta {@link ApiBaseResponse} usata dalle
 * risposte di successo, cosi' il client trova sempre lo stesso formato
 * (status/message/timestamp/data) sia che l'operazione riesca sia che
 * fallisca.
 *
 * <p>Copre solo gli errori che passano dal DispatcherServlet, cioe' quelli
 * generati da un handler gia' individuato. Gli errori che nascono prima,
 * dentro la filter chain di Spring Security (richiesta senza token, token
 * scaduto, ruolo insufficiente), non arrivano fin qui: li formattano
 * {@code ApiAuthenticationEntryPoint} e {@code ApiAccessDeniedHandler},
 * che scrivono la stessa busta a mano — vedi {@code SecurityConfig}.
 *
 * <p>Nota sullo status: qui, a differenza del percorso di successo, lo
 * status non lo sceglie il Service ma il tipo di eccezione; resta pero'
 * scritto una volta sola nella busta e riusato come status HTTP della
 * risposta, quindi i due non possono divergere.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ApiResponseMapper apiResponseMapper;

    /**
     * Errori applicativi sollevati di proposito dai Service
     * ({@link com.felixhotel.backend.exception.AppException} e sottoclassi):
     * portano gia' addosso status e messaggio, qui vengono solo impacchettati
     * nella busta standard. E' la strada normale degli errori del progetto —
     * un solo handler per tutte le sottoclassi, presenti e future.
     *
     * <p>Livello WARN e senza stacktrace: sono esiti previsti (email
     * duplicata, risorsa inesistente), non guasti da investigare.
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiBaseResponse> handleApp(AppException ex) {
        log.warn("Errore applicativo [{}]: {}", ex.getStatus().value(), ex.getMessage());
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    /**
     * Rete per le {@code ResponseStatusException} sollevate da Spring stesso
     * (non dal nostro codice, che usa {@link AppException}): senza questo
     * handler finirebbero nel catch-all e diventerebbero 500, perdendo lo
     * status che l'eccezione dichiarava.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiBaseResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());

        // getReason() puo' essere null (costruttore col solo status): in quel caso si
        // ripiega sulla descrizione standard dello status, mai su un messaggio null.
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

        log.warn("Errore [{}]: {}", status.value(), message);
        return build(status, message, null);
    }

    /**
     * Validazione del body fallita: i vincoli sono quelli dichiarati nello
     * spec OpenAPI (required, minLength, format email...) e finiti come
     * annotazioni sui DTO generati.
     *
     * <p>E' l'unico caso in cui una risposta d'errore valorizza {@code data}:
     * ci finisce la mappa campo -> messaggio, cosi' il frontend puo'
     * evidenziare i singoli input rifiutati senza dover interpretare la
     * stringa di {@code message}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiBaseResponse> handleValidation(MethodArgumentNotValidException ex) {
        // LinkedHashMap: mantiene l'ordine di dichiarazione dei campi, cosi' la risposta
        // e' stabile tra chiamate identiche (utile per i test e per la lettura umana).
        Map<String, String> errori = new LinkedHashMap<>();

        for (FieldError errore : ex.getBindingResult().getFieldErrors()) {
            String messaggio = errore.getDefaultMessage() != null
                    ? errore.getDefaultMessage()
                    : "valore non valido";
            // merge: se lo stesso campo viola piu' vincoli si concatenano, invece di
            // perdere silenziosamente tutti i messaggi tranne l'ultimo.
            errori.merge(errore.getField(), messaggio, (esistente, nuovo) -> esistente + "; " + nuovo);
        }

        // Errori non legati a un campo specifico (vincoli a livello di oggetto): senza
        // questo giro sparirebbero dalla risposta lasciando 'data' vuoto.
        ex.getBindingResult().getGlobalErrors().forEach(errore ->
                errori.put(errore.getObjectName(), errore.getDefaultMessage() != null
                        ? errore.getDefaultMessage()
                        : "valore non valido"));

        return build(HttpStatus.BAD_REQUEST, "Dati non validi", errori);
    }

    /**
     * Body assente, JSON sintatticamente rotto o non convertibile nel DTO
     * atteso (es. stringa dove serve un numero). Il dettaglio dell'eccezione
     * non viene esposto: descrive strutture interne di Jackson e non aiuta
     * chi chiama.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiBaseResponse> handleBodyNonLeggibile(HttpMessageNotReadableException ex) {
        log.debug("Body della richiesta non leggibile", ex);
        return build(HttpStatus.BAD_REQUEST, "Corpo della richiesta assente o non leggibile", null);
    }

    /** Parametro di path/query non convertibile nel tipo atteso (es. "abc" dove serve un id numerico). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiBaseResponse> handleTipoParametro(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST,
                "Parametro '" + ex.getName() + "' non valido", null);
    }

    /**
     * Autenticazione fallita a livello di Controller/Service. In pratica
     * copre i casi non gia' convertiti a mano: {@code AuthServiceImpl.login}
     * cattura la sua {@code AuthenticationException} per poter rispondere
     * "Credenziali non valide" invece di un messaggio generico.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiBaseResponse> handleAutenticazione(AuthenticationException ex) {
        log.debug("Autenticazione fallita", ex);
        return build(HttpStatus.UNAUTHORIZED, "Autenticazione richiesta", null);
    }

    /**
     * Ruolo insufficiente su un metodo annotato {@code @PreAuthorize}:
     * l'utente e' autenticato (altrimenti sarebbe 401), ma non ha i permessi.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiBaseResponse> handleAccessoNegato(AccessDeniedException ex) {
        log.debug("Accesso negato", ex);
        return build(HttpStatus.FORBIDDEN, "Permessi insufficienti per questa operazione", null);
    }

    /**
     * Rotta inesistente. Due eccezioni diverse per lo stesso caso viste da
     * fuori: {@code NoHandlerFoundException} quando nessun Controller
     * risponde all'URL, {@code NoResourceFoundException} quando la richiesta
     * arriva invece al gestore delle risorse statiche e li' non trova nulla.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiBaseResponse> handleRottaInesistente(Exception ex) {
        log.debug("Rotta inesistente", ex);
        return build(HttpStatus.NOT_FOUND, "Risorsa non trovata", null);
    }

    /**
     * Rete di sicurezza per tutto il resto: qualsiasi eccezione non prevista
     * diventa un 500 nella busta standard, invece della pagina d'errore di
     * default di Spring.
     *
     * <p>Il messaggio esposto e' volutamente generico — il dettaglio
     * (stacktrace, query, nomi di colonne) resta nei log e non va mostrato a
     * chi chiama, perche' e' un'informazione utile a chi attacca. Livello
     * ERROR: qui ci arrivano solo bug o guasti veri.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiBaseResponse> handleGenerica(Exception ex) {
        log.error("Errore non gestito", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Si e' verificato un errore imprevisto", null);
    }

    /**
     * Costruisce la busta d'errore e la usa anche come status HTTP della
     * risposta: come nei Controller, lo status del body e quello della
     * risposta vengono dallo stesso posto.
     */
    private ResponseEntity<ApiBaseResponse> build(HttpStatus status, String message, Object data) {
        ApiBaseResponse response = apiResponseMapper.toResponse(status, message, data);
        return ResponseEntity.status(status).body(response);
    }
}
