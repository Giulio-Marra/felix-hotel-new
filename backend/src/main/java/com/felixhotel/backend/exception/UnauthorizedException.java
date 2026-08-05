package com.felixhotel.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 — autenticazione mancante o non valida, sollevata dal codice
 * applicativo: credenziali errate al login, oppure un endpoint che si
 * aspetta un utente autenticato e non lo trova nel SecurityContext.
 *
 * <p>I 401 che nascono nella filter chain (token assente, scaduto, account
 * disattivato) non passano da qui: li produce
 * {@code ApiAuthenticationEntryPoint}, con la stessa busta.
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }

    /** Variante che conserva l'eccezione originale nei log (es. quella di Spring Security). */
    public UnauthorizedException(String message, Throwable cause) {
        super(HttpStatus.UNAUTHORIZED, message, cause);
    }
}
