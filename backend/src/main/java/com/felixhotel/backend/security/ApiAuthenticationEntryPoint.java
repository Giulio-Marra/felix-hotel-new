package com.felixhotel.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Risposta 401 per le richieste non autenticate su endpoint protetti:
 * token assente, malformato, scaduto o account disattivato.
 *
 * <p>Esiste perche' questi errori nascono dentro la filter chain di Spring
 * Security, prima che il DispatcherServlet individui un handler: il
 * {@code GlobalExceptionHandler} non li vede mai, quindi senza questa classe
 * la busta standard varrebbe per tutti gli errori tranne proprio i piu'
 * comuni. Sostituisce {@code HttpStatusEntryPoint}, che restituiva lo status
 * giusto ma con corpo vuoto.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter apiErrorWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.debug("Richiesta non autenticata su {}", request.getRequestURI(), authException);

        // Messaggio volutamente uniforme: distinguere "token scaduto" da "token invalido"
        // direbbe a chi sonda quali token esistono. Il dettaglio resta nei log.
        apiErrorWriter.write(response, HttpStatus.UNAUTHORIZED, "Autenticazione richiesta");
    }
}
