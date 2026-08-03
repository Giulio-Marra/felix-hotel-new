package com.felixhotel.backend.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

/**
 * Metadati di paginazione, allegati a {@link ApiResponse#page()} solo per le
 * risposte il cui {@code data} e' una pagina di risultati.
 */
@Schema(description = "Metadati di paginazione")
public record PageInfo(

        @Schema(description = "Numero di pagina corrente (0-based)", example = "0")
        int pageNumber,

        @Schema(description = "Dimensione della pagina", example = "20")
        int pageSize,

        @Schema(description = "Numero totale di elementi su tutte le pagine", example = "134")
        long totalElements,

        @Schema(description = "Numero totale di pagine", example = "7")
        int totalPages
) {

    public static PageInfo from(Page<?> page) {
        return new PageInfo(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
