package com.felixhotel.backend.security;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
// Jackson 3 (Spring Boot 4): il databind sta sotto "tools.jackson", non piu' sotto
// "com.fasterxml.jackson" — quello resta solo per le annotazioni. Il bean ObjectMapper
// del contesto e' di questo tipo: importare l'omonimo Jackson 2, che il generatore
// OpenAPI trascina comunque nel classpath, fa fallire l'avvio senza bean qualificato.
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Scrive la busta d'errore standard direttamente sulla response HTTP.
 *
 * <p>Serve agli errori che nascono nella filter chain di Spring Security
 * ({@link ApiAuthenticationEntryPoint}, {@link ApiAccessDeniedHandler}):
 * li' non c'e' un valore di ritorno che Spring MVC possa convertire in JSON,
 * perche' siamo prima del DispatcherServlet, quindi la serializzazione va
 * fatta a mano. Sta in una classe sua per non ripetere in ogni handler lo
 * stesso rituale status + content type + encoding + scrittura.
 */
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final ApiResponseMapper apiResponseMapper;
    private final ObjectMapper objectMapper;

    /**
     * Scrive la busta con lo status indicato e {@code data} null: da qui
     * passano solo errori di autenticazione/autorizzazione, che non hanno
     * un payload da restituire.
     */
    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        ApiBaseResponse body = apiResponseMapper.toResponse(status, message, null);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
