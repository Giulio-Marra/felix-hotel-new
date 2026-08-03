package com.felixhotel.backend.service;

import com.felixhotel.backend.common.response.Result;
import com.felixhotel.backend.dto.auth.AuthResponse;
import com.felixhotel.backend.dto.auth.LoginRequest;
import com.felixhotel.backend.dto.auth.RegisterRequest;

/**
 * Logica di business per registrazione e login. Il Controller resta sottile
 * e delega qui, come da convenzione di progetto (Controller -> Service
 * interfaccia/impl). Ogni metodo ritorna un {@link Result}, che porta sia
 * il DTO sia il messaggio descrittivo dell'esito: e' il Service a scegliere
 * il messaggio, perche' e' lui a conoscere il significato business
 * dell'operazione.
 */
public interface AuthService {

    /** Registra un nuovo cliente (ruolo USER) e restituisce subito un JWT valido. */
    Result<AuthResponse> register(RegisterRequest request);

    /** Autentica un account (cliente o personale) e restituisce un JWT. */
    Result<AuthResponse> login(LoginRequest request);
}
