package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PageInfo;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Costruisce la busta di risposta standard. E' l'unico punto del progetto
 * in cui la busta viene riempita: il {@code timestamp} non lo scrive mai
 * nessun altro, e status/messaggio arrivano dal Service, che e' l'unico a
 * sapere com'e' andata l'operazione.
 *
 * <p>Le buste sono due per tutto il progetto ({@code ApiBaseResponse} e
 * {@code ApiBaseResponsePaginated}, generate dallo spec OpenAPI), quindi
 * qui bastano due metodi.
 */
@Component
public class ApiResponseMapper {

    /** Busta standard non paginata: l'oggetto da restituire finisce in {@code data}. */
    public ApiBaseResponse toResponse(HttpStatus status, String message, Object data) {
        return new ApiBaseResponse()
                .status(status.value())
                .message(message)
                .timestamp(OffsetDateTime.now())
                .data(data);
    }

    /** Come {@link #toResponse}, piu' i metadati di paginazione ricavati dalla Page. */
    public ApiBaseResponsePaginated toPaginatedResponse(HttpStatus status, String message, Object data, Page<?> page) {
        return new ApiBaseResponsePaginated()
                .status(status.value())
                .message(message)
                .timestamp(OffsetDateTime.now())
                .data(data)
                .page(toPageInfo(page));
    }

    /** Conversione Page (Spring Data) -> PageInfo (DTO esposto in risposta). */
    public PageInfo toPageInfo(Page<?> page) {
        return new PageInfo()
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages());
    }
}
