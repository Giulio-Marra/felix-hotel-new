package com.felixhotel.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — la richiesta e' valida in se', ma confligge con lo stato attuale dei
 * dati: un'email gia' registrata, una camera gia' occupata nelle date
 * richieste, una prenotazione gia' annullata che si prova ad annullare di
 * nuovo.
 *
 * <p>Differenza pratica con {@link BadRequestException}: li' la richiesta e'
 * sbagliata sempre, qui sarebbe andata a buon fine in un altro momento o su
 * un'altra risorsa.
 */
public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }

    /**
     * Variante che conserva l'eccezione originale nei log. Serve quando il
     * conflitto lo scopre il database e non il nostro controllo preventivo
     * (violazione di un vincolo di unicita' o di chiave esterna): il messaggio
     * che leggiamo noi dice quale vincolo, quello che leggera' il client no.
     */
    public ConflictException(String message, Throwable cause) {
        super(HttpStatus.CONFLICT, message, cause);
    }
}
