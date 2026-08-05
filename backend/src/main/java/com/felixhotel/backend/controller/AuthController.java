package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.AuthApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.service.AuthService;
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

    @Override
    public ResponseEntity<ApiBaseResponse> register(RegisterRequest request) {
        ApiBaseResponse response = authService.register(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> login(LoginRequest request) {
        ApiBaseResponse response = authService.login(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> me() {
        ApiBaseResponse response = authService.me();
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
