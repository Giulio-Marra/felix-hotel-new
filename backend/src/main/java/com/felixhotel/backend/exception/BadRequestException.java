package com.felixhotel.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 400 — la richiesta e' formalmente valida per lo schema OpenAPI ma viola
 * una regola applicativa sull'input che lo schema non sa esprimere (es. il
 * consenso privacy che deve valere true, o una data di check-out precedente
 * al check-in).
 *
 * <p>Gli errori di validazione dei singoli campi non passano da qui: li
 * solleva Spring dalle annotazioni sui DTO generati e li formatta il
 * {@link GlobalExceptionHandler}, che in quel caso valorizza anche
 * {@code data} con la mappa dei campi rifiutati.
 */
public class BadRequestException extends AppException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Variante che conserva l'eccezione originale nei log, gemella di quella
     * gia' presente su {@link ConflictException}. Serve quando a scoprire
     * l'input sbagliato e' il database e non un nostro controllo preventivo:
     * il vincolo violato interessa a noi, non a chi ha fatto la richiesta.
     */
    public BadRequestException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
