package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.common.response.Result;
import com.felixhotel.backend.dto.auth.AccountSummary;
import com.felixhotel.backend.dto.auth.AuthResponse;
import com.felixhotel.backend.dto.auth.LoginRequest;
import com.felixhotel.backend.dto.auth.RegisterRequest;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.JwtService;
import com.felixhotel.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Implementazione della logica di autenticazione. Nota di scope: qui si
 * gestisce solo l'errore di login (credenziali non valide -> 401), perche'
 * altrimenti un'eccezione di Spring Security senza gestore risalirebbe come
 * 500. La gestione centralizzata di tutte le eccezioni (busta ApiResponse
 * anche sugli errori) e' rimandata a un branch dedicato successivo.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    private static final String RUOLO_USER = "USER";

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;
    private final RuoloRepository ruoloRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UtenteMapper utenteMapper;

    /**
     * Registra un nuovo cliente (Utente) con ruolo USER. La registrazione
     * pubblica di Staff non e' prevista: quegli account si creano solo
     * internamente.
     */
    @Override
    @Transactional
    public Result<AuthResponse> register(RegisterRequest request) {
        // L'unicita' email va controllata su entrambe le popolazioni: utente.email e staff.email
        // sono due vincoli UNIQUE indipendenti in DB, altrimenti un cliente potrebbe registrarsi
        // con l'email di un account Staff/ADMIN esistente e "oscurarlo" ai login successivi
        // (CustomUserDetailsService cerca prima tra gli Utente).
        if (utenteRepository.existsByEmail(request.getEmail()) || staffRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email gia' registrata");
        }

        Ruolo ruoloUser = ruoloRepository.findByNome(RUOLO_USER)
                .orElseThrow(() -> new IllegalStateException(
                        "Ruolo USER mancante in DB: verificare V1__init_schema.sql"));

        Utente utente = new Utente();
        utente.setNome(request.getNome());
        utente.setCognome(request.getCognome());
        utente.setEmail(request.getEmail());
        utente.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        utente.setTelefono(request.getTelefono());
        utente.setDataNascita(request.getDataNascita());
        utente.setDataRegistrazione(LocalDateTime.now());
        utente.setAttivo(true);
        utente.setEmailVerificata(false);
        utente.setConsensoPrivacy(request.getConsensoPrivacy());
        utente.setDataConsenso(Boolean.TRUE.equals(request.getConsensoPrivacy()) ? LocalDateTime.now() : null);
        utente.setRuolo(ruoloUser);

        Utente salvato = utenteRepository.save(utente);

        String token = jwtService.generateToken(salvato.getId(), salvato.getEmail(), ruoloUser.getNome());
        AuthResponse response = new AuthResponse()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresInMs(jwtService.getExpirationMs())
                .account(utenteMapper.toAccountSummary(salvato));

        return new Result<>(response, "Registrazione completata con successo");
    }

    /**
     * Autentica email+password (Utente o Staff, vedi CustomUserDetailsService)
     * tramite l'AuthenticationManager di Spring Security e restituisce un
     * nuovo JWT.
     */
    @Override
    public Result<AuthResponse> login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenziali non valide", ex);
        }

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getUserId(), principal.getUsername(), principal.getRuoloNome());
        AccountSummary account = new AccountSummary()
                .id(principal.getUserId())
                .nome(principal.getNome())
                .cognome(principal.getCognome())
                .email(principal.getUsername())
                .ruolo(principal.getRuoloNome());

        AuthResponse response = new AuthResponse()
                .token(token)
                .tokenType(TOKEN_TYPE)
                .expiresInMs(jwtService.getExpirationMs())
                .account(account);

        return new Result<>(response, "Login effettuato con successo");
    }
}
