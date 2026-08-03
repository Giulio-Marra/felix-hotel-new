package com.felixhotel.backend.common.response;

/**
 * Esito generico di un'operazione di Service: il dato restituito piu' un
 * messaggio descrittivo scelto dal Service stesso, perche' e' lui a
 * conoscere il significato business dell'operazione appena eseguita (non il
 * Controller). Il Controller spacchetta questo oggetto per costruire la
 * {@link ApiResponse}, decidendo solo lo status HTTP — l'unica cosa che
 * resta di sua competenza.
 */
public record Result<T>(T data, String message) {
}
