package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;

/**
 * Logica di business per registrazione e login. Il Controller resta sottile
 * e delega qui, come da convenzione di progetto (Controller -> Service
 * interfaccia/impl).
 *
 * <p>I metodi restituiscono gia' la busta standard {@link ApiBaseResponse}:
 * status ed esito li conosce il Service, che e' l'unico a sapere com'e'
 * andata l'operazione (del resto e' gia' lui a decidere lo status degli
 * errori, scegliendo quale sottoclasse di {@code AppException} lanciare). Il
 * Controller si limita a girarla al client.
 */
public interface AuthService {

    /**
     * Registra un nuovo cliente (ruolo USER) e ne restituisce il riepilogo.
     * Non emette alcun token: per autenticarsi serve una chiamata esplicita
     * a {@link #login}.
     */
    ApiBaseResponse register(RegisterRequest request);

    /**
     * Autentica un account (cliente o personale) e restituisce un JWT.
     *
     * @param clientIp indirizzo IP di chi sta chiamando, che serve solo a
     *                 contare i tentativi falliti per origine (vedi
     *                 {@link LoginAttemptService}). Arriva come stringa gia'
     *                 estratta dal Controller: e' quest'ultimo il layer che
     *                 conosce la richiesta HTTP, e passarlo cosi' evita di far
     *                 entrare i tipi servlet nel Service. Puo' essere
     *                 {@code null} se non determinabile.
     */
    ApiBaseResponse login(LoginRequest request, String clientIp);

    /**
     * Riepilogo dell'account autenticato, ricavato dal token della
     * richiesta in corso: non prende parametri proprio perche' l'utente
     * non e' scelto da chi chiama, e' quello del token.
     */
    ApiBaseResponse me();
}
