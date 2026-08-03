package com.felixhotel.backend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Busta di risposta standard per **tutti** gli endpoint REST del progetto
 * (successo ed errore), cosi' il client (frontend React) ha sempre lo stesso
 * formato da parsare: status HTTP, messaggio leggibile, timestamp,
 * {@code data} (l'oggetto o l'array richiesto, null in caso di errore) e
 * {@code page} valorizzato solo per risposte paginate. Gli errori passano
 * da qui tramite {@code GlobalExceptionHandler}, non c'e' un formato a
 * parte.
 *
 * @param <T> tipo del payload restituito in {@code data}
 */
@Schema(description = "Busta di risposta standard: status, messaggio, timestamp, dati (e paginazione se presente)")
public record ApiResponse<T>(

        @Schema(description = "Codice di stato HTTP", example = "200")
        int status,

        @Schema(description = "Messaggio leggibile, di successo o di errore", example = "Operazione completata con successo")
        String message,

        @Schema(description = "Istante in cui e' stata generata la risposta")
        LocalDateTime timestamp,

        @Schema(description = "Payload della risposta: oggetto singolo, array, o null in caso di errore")
        T data,

        @Schema(description = "Metadati di paginazione, presenti solo se 'data' e' una pagina di risultati")
        PageInfo page
) {

    public static <T> ApiResponse<T> success(HttpStatus status, String message, T data) {
        return new ApiResponse<>(status.value(), message, LocalDateTime.now(), data, null);
    }

    public static <T> ApiResponse<T> success(HttpStatus status, String message, T data, PageInfo page) {
        return new ApiResponse<>(status.value(), message, LocalDateTime.now(), data, page);
    }

    public static <T> ApiResponse<T> error(HttpStatus status, String message) {
        return new ApiResponse<>(status.value(), message, LocalDateTime.now(), null, null);
    }
}
