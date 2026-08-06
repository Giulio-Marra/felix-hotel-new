package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.AuthApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint di autenticazione: registrazione clienti e login (clienti e
 * staff) sono pubblici — elencati per path esatto in SecurityConfig, non
 * con un wildcard — mentre {@code /api/auth/me} richiede il token. Rotte,
 * DTO e busta di risposta vengono da {@link AuthApi} (contract-first, generata da
 * openapi/felix-hotel-api.yaml, annotazioni Swagger incluse): qui si
 * implementa solo il corpo dei metodi.
 *
 * <p>Il Controller e' volutamente vuoto di logica: chiama il Service e
 * restituisce la busta che gli torna indietro, usando come status HTTP
 * quello che la busta stessa dichiara (cosi' non c'e' modo che i due
 * divergano).
 */
@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;

    /**
     * La richiesta HTTP in corso, iniettata da Spring come proxy: ad ogni
     * chiamata risolve la richiesta di quel momento, quindi il campo e' sicuro
     * anche con piu' richieste in parallelo.
     *
     * <p>Serve solo a leggere l'IP di chi chiama, che login e registrazione usano
     * per contare per origine i tentativi (falliti, nel primo caso; tutti, nel
     * secondo). Sta qui e non nel Service di proposito: il
     * layer web e' questo, e il Service riceve una semplice stringa invece di
     * dipendere dai tipi servlet. L'alternativa sarebbe stata un parametro nella
     * firma del metodo, che pero' non e' nostra — la impone l'interfaccia
     * generata dallo spec OpenAPI.
     */
    private final HttpServletRequest httpRequest;

    @Override
    public ResponseEntity<ApiBaseResponse> register(RegisterRequest request) {
        ApiBaseResponse response = authService.register(request, clientIp());
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> login(LoginRequest request) {
        ApiBaseResponse response = authService.login(request, clientIp());
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> me() {
        ApiBaseResponse response = authService.me();
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * Indirizzo IP di chi ha mandato la richiesta, preso dalla connessione TCP.
     *
     * <p><b>Di proposito non si guarda {@code X-Forwarded-For}</b>, ne' gli altri
     * header simili: sono scritti dal client e chiunque puo' metterci dentro
     * quello che vuole. Fidarsene qui significherebbe permettere a chi attacca di
     * cambiare header ad ogni tentativo e non farsi contare mai — cioe' spegnere
     * la protezione con una riga.
     *
     * <p>Il rovescio della medaglia va tenuto presente per quando si andra' in
     * produzione: dietro un reverse proxy o un load balancer, tutte le richieste
     * arrivano con l'IP del proxy e i tentativi finirebbero in un unico
     * contatore. Il momento di leggere quegli header sara' quello, ma solo
     * configurando {@code ForwardedHeaderFilter} e fidandosi esclusivamente del
     * proxy noto — mai del client.
     */
    private String clientIp() {
        return httpRequest.getRemoteAddr();
    }
}
