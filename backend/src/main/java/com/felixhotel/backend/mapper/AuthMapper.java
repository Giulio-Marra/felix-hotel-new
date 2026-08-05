package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.AccountSummary;
import com.felixhotel.backend.dto.AuthResponse;
import com.felixhotel.backend.security.AppUserPrincipal;
import org.springframework.stereotype.Component;

/**
 * Costruzione dei DTO di autenticazione. Sta qui e non nel Service perche'
 * il Service fa logica (verifica credenziali, salva l'utente, emette il
 * token), non assemblaggio di DTO: quello e' lavoro da mapper, come da
 * convenzione di progetto (mapping scritto a mano, niente MapStruct).
 */
@Component
public class AuthMapper {

    /** Schema di autenticazione usato: valorizza {@code tokenType} nella risposta. */
    private static final String TOKEN_TYPE = "Bearer";

    /**
     * Risposta di login: solo il token emesso e la sua scadenza. I dati
     * dell'account non ci finiscono dentro — si recuperano dall'endpoint
     * dedicato usando il token appena ottenuto.
     */
    public AuthResponse toAuthResponse(String token, long expiresInMs) {
        return new AuthResponse()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresInMs(expiresInMs);
    }

    /**
     * Riepilogo dell'account a partire dal principal di Spring Security
     * (non da un'entity): usato da /api/auth/me, dove l'utente e' gia'
     * autenticato e puo' essere indifferentemente un Utente o uno Staff.
     */
    public AccountSummary toAccountSummary(AppUserPrincipal principal) {
        return new AccountSummary()
                .id(principal.getUserId())
                .nome(principal.getNome())
                .cognome(principal.getCognome())
                .email(principal.getUsername())
                .ruolo(principal.getRuoloNome());
    }
}
