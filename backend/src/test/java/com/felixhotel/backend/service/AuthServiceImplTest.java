package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.AuthMapper;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.JwtService;
import com.felixhotel.backend.service.impl.AuthServiceImpl;
import com.felixhotel.backend.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link AuthServiceImpl}: la classe sotto esame e' vera,
 * tutto il resto e' finto (Mockito). Niente Spring, niente database, niente
 * Docker — girano con {@code mvnw test} in millisecondi.
 *
 * <p>Qui si verificano le <b>decisioni</b> del service: quale eccezione (e
 * quindi quale status) sceglie in ciascun ramo. Che poi quell'eccezione
 * diventi davvero una risposta HTTP con la busta giusta e' un'altra cosa, e la
 * verifica {@code AuthApiIT} — un unitario con i repository finti non potrebbe
 * accorgersene.
 *
 * <p>Il service oggi ha poca logica propria, quindi questi test sono pochi di
 * proposito: valgono soprattutto come <b>forma da copiare</b> quando arrivera'
 * la logica vera del dominio (disponibilita' camere, calcolo importi, regole
 * di annullamento), dove i rami da coprire saranno molti.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl")
class AuthServiceImplTest {

    /** Indirizzo di chi chiama, che il Controller estrae dalla richiesta e passa al service. */
    private static final String IP = "203.0.113.7";

    @Mock
    private UtenteRepository utenteRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private RuoloRepository ruoloRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;
    @Mock
    private UtenteMapper utenteMapper;
    @Mock
    private AuthMapper authMapper;
    @Mock
    private ApiResponseMapper apiResponseMapper;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private RegistrationAttemptService registrationAttemptService;

    @InjectMocks
    private AuthServiceImpl authService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("se il limite di registrazioni e' scattato non tocca nemmeno il database")
        void register_quandoRallentato_nonInterrogaIRepository() {
            // given: il contatore delle registrazioni ha gia' deciso di rifiutare questo indirizzo
            RegisterRequest richiesta = dati.registerRequest();
            org.mockito.Mockito.doThrow(new TooManyRequestsException("Riprova fra 1 minuto"))
                    .when(registrationAttemptService).checkNotThrottled(IP);

            // when/then: 429, non 400 ne' 409
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(TooManyRequestsException.class)
                    .extracting(ex -> ((TooManyRequestsException) ex).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // then: il controllo viene prima di tutto il resto, consenso privacy compreso.
            // E' il punto della difesa — la registrazione di troppo non deve costarci ne'
            // una query ne' un hash BCrypt, che e' lento di proposito.
            verifyNoInteractions(utenteRepository, staffRepository, ruoloRepository, passwordEncoder);
        }

        @Test
        @DisplayName("conta il tentativo anche quando la registrazione viene rifiutata")
        void register_qualunqueEsito_contaIlTentativo() {
            // given: una richiesta che passa il limite di frequenza ma verra' rifiutata dopo
            RegisterRequest richiesta = dati.registerRequest();
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(true);

            // when: la registrazione fallisce con un conflitto
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(ConflictException.class);

            // then: il tentativo e' stato contato lo stesso. E' la differenza rispetto al
            // login, dove si contano solo i fallimenti: qui l'abuso e' la frequenza, quindi
            // conta ogni chiamata — anche quelle che riescono, che sono anzi il risultato
            // che si vuole limitare. Se si contassero solo i successi, basterebbe martellare
            // con un'email gia' registrata per non farsi mai rallentare.
            verify(registrationAttemptService).recordAttempt(IP);
        }

        @Test
        @DisplayName("senza consenso privacy solleva BadRequestException e non tocca il database")
        void register_senzaConsensoPrivacy_sollevaBadRequest() {
            // given: una richiesta valida tranne il consenso privacy
            RegisterRequest richiesta = dati.registerRequest().consensoPrivacy(false);

            // when/then: il consenso e' un problema dell'input, quindi 400 e non 409
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("consenso")
                    .extracting(ex -> ((BadRequestException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // then: il controllo viene prima di qualsiasi accesso ai dati — nessun account
            // va nemmeno cercato, tantomeno salvato
            verifyNoInteractions(utenteRepository, staffRepository, ruoloRepository);
        }

        @Test
        @DisplayName("con email gia' usata da un Utente solleva ConflictException")
        void register_conEmailDiUtenteEsistente_sollevaConflict() {
            // given: l'email risulta gia' registrata fra i clienti
            RegisterRequest richiesta = dati.registerRequest();
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(true);

            // when/then: 409, conflitto con lo stato attuale dei dati
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            // then: nessun account creato
            verify(utenteRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("con email gia' usata da uno Staff solleva ConflictException")
        void register_conEmailDiStaffEsistente_sollevaConflict() {
            // given: l'email non e' fra i clienti, ma appartiene a un account del personale
            RegisterRequest richiesta = dati.registerRequest();
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            when(staffRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(true);

            // when/then: deve valere lo stesso 409 del caso precedente.
            // Questo test protegge un problema di sicurezza gia' trovato una volta:
            // utente.email e staff.email sono due UNIQUE indipendenti, e chi si registra
            // con l'email di uno STAFF ne oscurerebbe i login successivi
            // (CustomUserDetailsService cerca prima fra gli Utente).
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(ConflictException.class);

            verify(utenteRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("se il ruolo USER manca in tabella fallisce come errore interno, non come 4xx")
        void register_senzaRuoloUserInDatabase_sollevaIllegalState() {
            // given: email libera, ma la tabella dei ruoli non contiene USER
            RegisterRequest richiesta = dati.registerRequest();
            when(utenteRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(staffRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(ruoloRepository.findByNome("USER")).thenReturn(java.util.Optional.empty());

            // when/then: e' un errore di configurazione del database, non una colpa di chi
            // chiama: deve restare un 500 (IllegalStateException, gestita dal catch-all)
            // e non diventare una AppException con status 4xx
            assertThatThrownBy(() -> authService.register(richiesta, IP))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("USER");
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("se il ritardo antibrute force e' attivo non verifica nemmeno le credenziali")
        void login_quandoRallentato_nonInterrogaAuthenticationManager() {
            // given: il contatore dei tentativi ha gia' deciso che questa richiesta va rifiutata
            LoginRequest richiesta = dati.loginRequest("mario.rossi@example.com", TestDataFactory.PASSWORD_VALIDA);
            org.mockito.Mockito.doThrow(new TooManyRequestsException("Riprova fra 4 secondi"))
                    .when(loginAttemptService).checkNotThrottled(richiesta.getEmail(), IP);

            // when/then: 429, non 401
            assertThatThrownBy(() -> authService.login(richiesta, IP))
                    .isInstanceOf(TooManyRequestsException.class)
                    .extracting(ex -> ((TooManyRequestsException) ex).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            // then: il controllo viene prima di tutto il resto. E' il punto della difesa —
            // un attacco non deve costarci una lettura sul database e un confronto BCrypt
            // (che e' lento di proposito) ad ogni tentativo.
            verifyNoInteractions(authenticationManager, utenteRepository, staffRepository, jwtService);
        }
    }

    @Nested
    @DisplayName("me")
    class Me {

        @Test
        @DisplayName("senza autenticazione nel contesto solleva UnauthorizedException")
        void me_senzaAutenticazione_sollevaUnauthorized() {
            // given: SecurityContext vuoto (nessun login in corso in questo thread)
            org.springframework.security.core.context.SecurityContextHolder.clearContext();

            // when/then: 401. In esercizio non ci si arriva (l'endpoint e' protetto), ma
            // il controllo evita che un principal anonimo diventi una ClassCastException,
            // cioe' un 500 al posto di un 401.
            assertThatThrownBy(() -> authService.me())
                    .isInstanceOf(com.felixhotel.backend.exception.UnauthorizedException.class);
        }
    }

    @Test
    @DisplayName("i mapper non vengono usati quando la richiesta viene rifiutata")
    void register_quandoFallisce_nonCostruisceNessunDto() {
        // given: una richiesta che verra' rifiutata subito
        RegisterRequest richiesta = dati.registerRequest().consensoPrivacy(false);

        // when: la registrazione fallisce
        assertThatThrownBy(() -> authService.register(richiesta, IP)).isInstanceOf(BadRequestException.class);

        // then: nessuna busta e nessun DTO costruito. Serve a tenere fermo il confine
        // della regola di progetto: i DTO li assembla il mapper, e solo se c'e' qualcosa
        // da restituire.
        verifyNoInteractions(utenteMapper, authMapper, apiResponseMapper, jwtService, passwordEncoder);
        assertThat(richiesta.getConsensoPrivacy()).isFalse();
    }
}
