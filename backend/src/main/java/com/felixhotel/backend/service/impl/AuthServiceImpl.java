package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.AuthResponse;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.AuthMapper;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.JwtService;
import com.felixhotel.backend.service.AuthService;
import com.felixhotel.backend.service.LoginAttemptService;
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

import java.time.LocalDateTime;

/**
 * Implementazione della logica di autenticazione.
 *
 * <p>Gli errori si sollevano come sottoclassi di {@code AppException}, ognuna
 * delle quali porta con se' lo status che il caso merita: a impacchettarle
 * nella busta standard pensa il {@code GlobalExceptionHandler}, qui non si
 * costruisce nessuna risposta d'errore a mano. Fa eccezione il login, che
 * cattura l'{@code AuthenticationException} di Spring Security per poter
 * rispondere "Credenziali non valide" invece del messaggio generico
 * dell'handler.
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
    private final LoginAttemptService loginAttemptService;
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
        // Il consenso privacy (GDPR) deve essere esplicitamente true: OpenAPI puo' dichiarare
        // il campo obbligatorio ma non che debba valere true, quindi il vincolo si verifica
        // qui. E' un problema dell'input, non di stato: 400, non 409.
        if (!Boolean.TRUE.equals(request.getConsensoPrivacy())) {
            throw new BadRequestException("Il consenso al trattamento dei dati personali e' obbligatorio");
        }

        if (utenteRepository.existsByEmail(request.getEmail()) || staffRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email gia' registrata");
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
        // L'account nasce gia' verificato perche' un flusso di verifica non esiste: non c'e'
        // modo di spedire l'email, ne' un token di conferma, ne' un controllo che impedisca
        // il login a chi non ha confermato. Lasciare il campo a false lo farebbe sembrare un
        // vincolo attivo mentre il login autenticherebbe comunque tutti (regola 17: niente
        // promesse senza il codice che le mantiene). Torna a false il giorno che la verifica
        // esistera' davvero — invio, token con scadenza e login bloccato finche' non e'
        // confermata — e non un momento prima.
        utente.setEmailVerificata(true);
        // Arrivati qui il consenso e' per forza true (controllato sopra), quindi la data si
        // valorizza sempre: e' l'istante in cui il consenso e' stato raccolto.
        utente.setConsensoPrivacy(true);
        utente.setDataConsenso(LocalDateTime.now());
        utente.setRuolo(ruoloUser);

        Utente salvato = utenteRepository.save(utente);

        // Nessun token qui: la registrazione crea l'account e basta, l'autenticazione si
        // ottiene con una chiamata esplicita a /api/auth/login. Il 201 restituisce la
        // risorsa appena creata, non una sessione: sono due operazioni distinte e chi
        // registra per conto d'altri (un domani, dal backoffice) non deve ritrovarsi loggato.
        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Registrazione completata con successo",
                utenteMapper.toAccountSummary(salvato));
    }

    /**
     * Autentica email+password (Utente o Staff, vedi CustomUserDetailsService)
     * tramite l'AuthenticationManager di Spring Security e restituisce un
     * nuovo JWT.
     */
    @Override
    public ApiBaseResponse login(LoginRequest request, String clientIp) {
        // Prima di ogni altra cosa, e in particolare prima di andare a leggere l'utente
        // dal database: chi ha gia' accumulato troppi tentativi falliti viene fermato
        // qui con un 429, senza che le credenziali vengano nemmeno guardate. E' il
        // punto della difesa — l'attacco non deve costare niente a noi e molto a chi
        // lo porta.
        loginAttemptService.checkNotThrottled(request.getEmail(), clientIp);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            // Si conta qualunque motivo di fallimento (password sbagliata, utente
            // inesistente, account disattivato): distinguerli darebbe a chi prova email
            // a caso un modo per capire quali esistono, e comunque sono tutti tentativi
            // di accesso non riusciti.
            loginAttemptService.recordFailure(request.getEmail(), clientIp);

            // L'eccezione originale si conserva come cause: distingue nei log un utente
            // inesistente da una password sbagliata, cosa che la risposta non fa apposta.
            throw new UnauthorizedException("Credenziali non valide", ex);
        }

        loginAttemptService.recordSuccess(request.getEmail(), clientIp);

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
            throw new UnauthorizedException("Nessun account autenticato");
        }

        return apiResponseMapper.toResponse(HttpStatus.OK, "Dati account recuperati",
                authMapper.toAccountSummary(principal));
    }
}
