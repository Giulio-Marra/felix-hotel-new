package com.felixhotel.backend.controller;

import com.felixhotel.backend.common.response.ApiResponse;
import com.felixhotel.backend.common.response.Result;
import com.felixhotel.backend.dto.auth.AuthResponse;
import com.felixhotel.backend.dto.auth.LoginRequest;
import com.felixhotel.backend.dto.auth.RegisterRequest;
import com.felixhotel.backend.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint pubblici di autenticazione: registrazione clienti e login
 * (clienti e staff). Nessuna autorizzazione richiesta (vedi SecurityConfig,
 * "/api/auth/**" e' in permitAll). Ogni risposta e' avvolta nella busta
 * standard {@link ApiResponse}; il messaggio arriva gia' scelto dal Service
 * dentro {@link Result}, qui si decide solo lo status HTTP.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticazione", description = "Registrazione e login")
public class AuthController {

    private final AuthService authService;

    // Crea un nuovo account cliente (ruolo USER) e restituisce subito un JWT valido.
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        Result<AuthResponse> result = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, result.message(), result.data()));
    }

    // Autentica un account (cliente o personale) via email/password e restituisce un JWT.
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        Result<AuthResponse> result = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, result.message(), result.data()));
    }
}
