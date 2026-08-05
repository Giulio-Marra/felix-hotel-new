package com.felixhotel.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * 429 — la richiesta e' formalmente valida e sarebbe pure autorizzata, ma
 * arriva troppo presto: chi la manda ha superato un limite di frequenza e
 * deve aspettare prima di riprovare.
 *
 * <p>Differenza pratica con {@link UnauthorizedException}: li' le credenziali
 * sono sbagliate, qui non vengono nemmeno guardate. E' la risposta al brute
 * force sul login — vedi
 * {@code com.felixhotel.backend.service.LoginAttemptService}.
 *
 * <p>Il tempo di attesa residuo finisce nel messaggio e non nell'header
 * {@code Retry-After}: aggiungere un header vorrebbe dire un
 * {@code @ExceptionHandler} dedicato a questa sola eccezione, mentre la
 * convenzione di progetto (regola 13) e' che le sottoclassi di
 * {@link AppException} passino tutte dallo stesso handler.
 */
public class TooManyRequestsException extends AppException {

    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
