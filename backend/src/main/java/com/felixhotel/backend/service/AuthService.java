package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.EmailRequest;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.NuovaPasswordRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TokenRequest;

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
     *
     * @param clientIp indirizzo IP di chi sta chiamando, che serve a contare le
     *                 registrazioni per origine (vedi
     *                 {@link RegistrationAttemptService}). Arriva come stringa
     *                 gia' estratta dal Controller, per le stesse ragioni dette
     *                 su {@link #login}. Puo' essere {@code null} se non
     *                 determinabile.
     */
    ApiBaseResponse register(RegisterRequest request, String clientIp);

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
    /**
     * Conferma l'indirizzo di un cliente consumando il token del link ricevuto.
     * Non restituisce nessun token di accesso: confermare e accedere restano due
     * operazioni, come registrazione e login.
     */
    ApiBaseResponse verificaEmail(TokenRequest request);

    /**
     * Rimanda il link di conferma. <b>Risponde sempre allo stesso modo</b>, esista o no
     * quell'indirizzo: distinguere direbbe a chiunque quali sono registrati.
     *
     * @param clientIp serve al limite di frequenza — questa rotta manda un'email, quindi
     *                 senza un tetto sarebbe un modo di riempire la casella altrui
     */
    ApiBaseResponse reinviaVerificaEmail(EmailRequest request, String clientIp);

    /** Accetta un invito del personale e imposta la password che la persona ha scelto. */
    ApiBaseResponse attivaAccountPersonale(NuovaPasswordRequest request);

    /**
     * Manda il link per reimpostare la password, cercando in tutte e due le popolazioni.
     * Risponde sempre allo stesso modo, per la stessa ragione del reinvio.
     */
    ApiBaseResponse richiediResetPassword(EmailRequest request, String clientIp);

    /** Scrive la nuova password consumando il token di reset. */
    ApiBaseResponse reimpostaPassword(NuovaPasswordRequest request);

}
