package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.AuthResponse;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.AuthMapper;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Implementazione della logica di autenticazione. Nota di scope: qui si
 * gestisce solo l'errore di login (credenziali non valide -> 401), perche'
 * altrimenti un'eccezione di Spring Security senza gestore risalirebbe come
 * 500. La gestione centralizzata di tutte le eccezioni (busta standard
 * anche sugli errori) e' rimandata a un branch dedicato successivo.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String RUOLO_USER = "USER";

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;
    private final RuoloRepository ruoloRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UtenteMapper utenteMapper;
    private final AuthMapper authMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Registra un nuovo cliente (Utente) con ruolo USER. La registrazione
     * pubblica di Staff non e' prevista: quegli account si creano solo
     * internamente.
     */
    @Override
    @Transactional
    public ApiBaseResponse register(RegisterRequest request) {
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

        // Nessun token qui: la registrazione crea l'account e basta, l'autenticazione si
        // ottiene con una chiamata esplicita a /api/auth/login. L'account nasce con
        // emailVerificata = false, autenticarlo subito vanificherebbe la verifica.
        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Registrazione completata con successo",
                utenteMapper.toAccountSummary(salvato));
    }

    /**
     * Autentica email+password (Utente o Staff, vedi CustomUserDetailsService)
     * tramite l'AuthenticationManager di Spring Security e restituisce un
     * nuovo JWT.
     */
    @Override
    public ApiBaseResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenziali non valide", ex);
        }

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getUserId(), principal.getUsername(), principal.getRuoloNome());

        // Solo il token: i dati dell'account li chiedera' il client all'endpoint dedicato.
        AuthResponse response = authMapper.toAuthResponse(token, jwtService.getExpirationMs());

        return apiResponseMapper.toResponse(HttpStatus.OK, "Login effettuato con successo", response);
    }

    /**
     * Riepilogo dell'account autenticato. L'utente si legge dal
     * SecurityContext e non da un parametro annotato
     * {@code @AuthenticationPrincipal}: la firma del metodo la impone
     * l'interfaccia generata dallo spec OpenAPI ({@code me()} senza
     * argomenti), e aggiungere un parametro nel Controller non sarebbe un
     * override — Spring mapperebbe il default method dell'interfaccia,
     * che risponde 501.
     */
    @Override
    public ApiBaseResponse me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // L'endpoint non e' in permitAll, quindi qui ci si arriva solo autenticati; il controllo
        // resta perche' un utente anonimo avrebbe come principal la stringa "anonymousUser",
        // che senza questo instanceof diventerebbe una ClassCastException (500 invece di 401).
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nessun account autenticato");
        }

        return apiResponseMapper.toResponse(HttpStatus.OK, "Dati account recuperati",
                authMapper.toAccountSummary(principal));
    }
}
