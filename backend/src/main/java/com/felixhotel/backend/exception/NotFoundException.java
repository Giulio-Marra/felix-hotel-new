package com.felixhotel.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 404 — la risorsa richiesta non esiste (id inesistente, o esistente ma non
 * visibile a chi chiama).
 *
 * <p>Il costruttore con risorsa+id costruisce un messaggio uniforme, cosi'
 * non ci si ritrova con dieci formulazioni diverse dello stesso errore
 * sparse per i Service.
 */
public class NotFoundException extends AppException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }

    /** Esempio di messaggio prodotto: "Prenotazione con id 42 non trovata". */
    public NotFoundException(String risorsa, Object id) {
        super(HttpStatus.NOT_FOUND, risorsa + " con id " + id + " non trovata");
    }
}
