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
 * risposta.
 *
 * <p>Ci arriva <b>anche</b> il 403 sollevato da {@code @PreAuthorize} dentro un
 * metodo, che pure nasce dopo il dispatcher: l'advice non lo gestisce, lo
 * rilancia apposta perche' risalga fin qui (vedi
 * {@code GlobalExceptionHandler.handleAccessoNegato}). Il motivo e' che a
 * questo livello si sa se chi chiama e' autenticato o no, e quindi si puo'
 * rispondere 401 invece di 403 a chi non lo e'. Effetto collaterale utile:
 * tutte le negazioni di permesso escono da un punto solo, con la stessa busta.
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
