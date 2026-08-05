package com.felixhotel.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Risposta 403 per le richieste autenticate ma senza i permessi necessari
 * (ruolo insufficiente), quando il controllo scatta nella filter chain.
 *
 * <p>Gemello di {@link ApiAuthenticationEntryPoint}: stesso motivo di
 * esistere (l'errore nasce prima del DispatcherServlet, quindi il
 * {@code GlobalExceptionHandler} non lo intercetta), stesso formato di
 * risposta. Il 403 sollevato da {@code @PreAuthorize} su un metodo, invece,
 * passa dal dispatcher e lo gestisce l'advice: i due percorsi producono la
 * stessa busta apposta, cosi' il client non vede differenze — verificato a
 * runtime su entrambe le strade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter apiErrorWriter;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        log.debug("Accesso negato su {}", request.getRequestURI(), accessDeniedException);

        apiErrorWriter.write(response, HttpStatus.FORBIDDEN, "Permessi insufficienti per questa operazione");
    }
}
