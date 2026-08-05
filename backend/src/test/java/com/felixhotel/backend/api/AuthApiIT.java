package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.backend.support.TestDataFactory;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione degli endpoint di autenticazione: richiesta HTTP reale
 * (via MockMvc) fino a Postgres e ritorno.
 *
 * <p>Verifica il cablaggio, che e' dove questo progetto ha trovato i suoi bug
 * veri: filtri di sicurezza, GlobalExceptionHandler, serializzazione della
 * busta, caricamento delle relazioni LAZY fuori transazione.
 *
 * <p>Ogni test controlla <b>sia lo status HTTP sia la forma della busta</b>:
 * verificare solo il codice lascerebbe scoperta meta' della convenzione di
 * progetto (status/message/timestamp/data).
 */
@DisplayName("API di autenticazione")
class AuthApiIT extends IntegrationTestBase {

    private static final String REGISTER = "/api/auth/register";
    private static final String LOGIN = "/api/auth/login";
    private static final String ME = "/api/auth/me";

    /** Serve a disattivare un account a database, senza passare da un endpoint che non esiste. */
    @Autowired
    private UtenteRepository utenteRepository;

    /**
     * Lo stesso segreto che usa l'applicazione (valorizzato dal profilo di test):
     * permette di fabbricare token firmati correttamente ma con scadenza scelta.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Nested
    @DisplayName("POST /api/auth/register")
    class Register {

        @Test
        @DisplayName("con dati validi crea l'account e risponde 201")
        void register_conDatiValidi_risponde201() throws Exception {
            // given: una richiesta di registrazione valida in ogni campo
            RegisterRequest richiesta = dati.registerRequest();

            // when: si registra il nuovo account
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201 e busta con l'AccountSummary appena creato, ruolo USER e nessun token
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.email").value(richiesta.getEmail()))
                    .andExpect(jsonPath("$.data.ruolo").value("USER"))
                    .andExpect(jsonPath("$.data.token").doesNotExist());
        }

        @Test
        @DisplayName("senza consenso privacy risponde 400")
        void register_senzaConsensoPrivacy_risponde400() throws Exception {
            // given: una richiesta valida tranne il consenso privacy, che e' negato
            RegisterRequest richiesta = dati.registerRequest().consensoPrivacy(false);

            // when: si prova a registrarsi
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400, perche' il vincolo GDPR non e' esprimibile nello schema
                    // OpenAPI e lo verifica il service
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con email malformata e password corta risponde 400 elencando i campi")
        void register_conCampiNonValidi_risponde400ConDettaglioCampi() throws Exception {
            // given: due vincoli dello spec violati contemporaneamente
            RegisterRequest richiesta = dati.registerRequest()
                    .email("non-una-email")
                    .password("corta");

            // when: si prova a registrarsi
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400 con la mappa campo -> messaggio in 'data', unica risposta
                    // d'errore che valorizza 'data' (serve al frontend per evidenziare gli input)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.email").exists())
                    .andExpect(jsonPath("$.data.password").exists());
        }

        @Test
        @DisplayName("con email gia' registrata risponde 409")
        void register_conEmailDuplicata_risponde409() throws Exception {
            // given: un account gia' registrato
            RegisterRequest richiesta = dati.registerRequest();
            registraAccount(richiesta);

            // when: si registra di nuovo la stessa email
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 409, conflitto con lo stato attuale dei dati
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("con body JSON malformato risponde 400")
        void register_conJsonMalformato_risponde400() throws Exception {
            // given/when: un body che non e' JSON valido
            mockMvc.perform(post(REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nome\":"))
                    // then: 400 nella busta standard, non l'errore grezzo di Jackson
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("con credenziali corrette restituisce il token")
        void login_conCredenzialiCorrette_restituisceToken() throws Exception {
            // given: un account registrato
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);

            // when: si effettua il login con le stesse credenziali
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.loginRequest(registrazione.getEmail(), TestDataFactory.PASSWORD_VALIDA))))
                    // then: 200 con il solo token (i dati dell'account si chiedono a /me)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.token").isNotEmpty())
                    .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.data.expiresInMs").isNumber())
                    .andExpect(jsonPath("$.data.account").doesNotExist());
        }

        @Test
        @DisplayName("con password sbagliata risponde 401")
        void login_conPasswordSbagliata_risponde401() throws Exception {
            // given: un account registrato
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);

            // when: si effettua il login con la password sbagliata
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.loginRequest(registrazione.getEmail(), "PasswordSbagliata999"))))
                    // then: 401, senza rivelare se l'email esista o meno
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con email inesistente risponde 401 come per la password sbagliata")
        void login_conEmailInesistente_risponde401() throws Exception {
            // given/when: credenziali di un account mai registrato
            mockMvc.perform(post(LOGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.loginRequest(dati.emailUnivoca(), TestDataFactory.PASSWORD_VALIDA))))
                    // then: stesso 401 del caso precedente. E' voluto: distinguere i due casi
                    // direbbe a chi sonda quali email sono registrate.
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Nested
    @DisplayName("Ritardo progressivo sui login falliti")
    class RitardoSuiLoginFalliti {

        /**
         * Indirizzi diversi da quello di default di MockMvc (127.0.0.1), per
         * poter distinguere chi attacca da chi si limita a passare di li'.
         */
        private static final String IP_ATTACCANTE = "203.0.113.7";
        private static final String IP_ESTRANEO = "198.51.100.4";

        @Test
        @DisplayName("dopo troppi tentativi falliti rifiuta con 429 anche la password giusta")
        void login_dopoTroppiTentativiFalliti_risponde429() throws Exception {
            // given: un account vero e i tentativi liberi consumati con password sbagliate
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(loginDa(IP_ATTACCANTE, registrazione.getEmail(), "PasswordSbagliata999"))
                        .andExpect(status().isUnauthorized());
            }

            // when: si riprova, stavolta con la password CORRETTA
            mockMvc.perform(loginDa(IP_ATTACCANTE, registrazione.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    // then: 429 nella busta standard. Che venga rifiutata anche la password
                    // giusta e' il punto: la richiesta si ferma prima che le credenziali
                    // vengano guardate, altrimenti la protezione non farebbe risparmiare
                    // niente all'applicazione sotto attacco.
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Riprova")))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("il ritardo di un account non tocca gli altri")
        void login_conAltroAccount_nonEInfluenzatoDalRitardoAltrui() throws Exception {
            // given: un account rallentato da tre tentativi falliti, e un secondo account
            RegisterRequest rallentato = dati.registerRequest();
            registraAccount(rallentato);
            RegisterRequest indenne = dati.registerRequest();
            registraAccount(indenne);
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(loginDa(IP_ATTACCANTE, rallentato.getEmail(), "PasswordSbagliata999"))
                        .andExpect(status().isUnauthorized());
            }

            // when/then: il secondo account entra senza attese. Il conteggio e' per email:
            // se non lo fosse, basterebbe sbagliare qualche password per rendere il login
            // lento a tutti.
            mockMvc.perform(loginDa(IP_ATTACCANTE, indenne.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.token").isNotEmpty());
        }

        @Test
        @DisplayName("chi prova molte email diverse viene rallentato per indirizzo IP")
        void login_conMolteEmailDiverseDalloStessoIp_risponde429() throws Exception {
            // given: un account vero, e un IP che ha appena provato cinque email a caso
            // (nessuna delle quali ha da sola superato la propria soglia)
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(loginDa(IP_ATTACCANTE, dati.emailUnivoca(), TestDataFactory.PASSWORD_VALIDA))
                        .andExpect(status().isUnauthorized());
            }

            // when/then: dallo stesso IP anche un login legittimo viene rifiutato. E' la
            // difesa dal password spraying — provare una password probabile su tanti
            // account — che il solo conteggio per email non vedrebbe mai.
            mockMvc.perform(loginDa(IP_ATTACCANTE, registrazione.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429));

            // ...mentre da un altro indirizzo lo stesso login passa. Questo pezzo verifica
            // il cablaggio che nessun test unitario puo' vedere: che l'IP arrivi davvero
            // dalla richiesta HTTP fino al contatore. Se il Controller non lo leggesse, il
            // test sopra passerebbe lo stesso (tutto finirebbe in un unico contatore) e
            // questo fallirebbe.
            mockMvc.perform(loginDa(IP_ESTRANEO, registrazione.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.token").isNotEmpty());
        }

        /** Richiesta di login che dichiara di arrivare dall'indirizzo IP indicato. */
        private MockHttpServletRequestBuilder loginDa(String ip, String email, String password) throws Exception {
            return post(LOGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(dati.loginRequest(email, password)))
                    .with(richiesta -> {
                        richiesta.setRemoteAddr(ip);
                        return richiesta;
                    });
        }
    }

    @Nested
    @DisplayName("GET /api/auth/me")
    class Me {

        @Test
        @DisplayName("con token valido restituisce l'account del token")
        void me_conTokenValido_restituisceAccount() throws Exception {
            // given: un account registrato e autenticato
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            String token = ottieniToken(registrazione.getEmail());

            // when: si chiede il proprio profilo
            mockMvc.perform(get(ME).header("Authorization", "Bearer " + token))
                    // then: 200 con i dati dell'account del token, non di un altro
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.email").value(registrazione.getEmail()))
                    .andExpect(jsonPath("$.data.ruolo").value("USER"));
        }

        @Test
        @DisplayName("senza token risponde 401 nella busta standard")
        void me_senzaToken_risponde401() throws Exception {
            // given/when: nessun header Authorization
            mockMvc.perform(get(ME))
                    // then: 401 e non 403, e con un corpo: questo errore nasce nella filter
                    // chain, dove serve l'ApiAuthenticationEntryPoint per avere la busta
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("con token malformato risponde 401")
        void me_conTokenMalformato_risponde401() throws Exception {
            // given/when: un token che non e' un JWT
            mockMvc.perform(get(ME).header("Authorization", "Bearer non-un-token"))
                    // then: 401, il token viene scartato dal filtro e la richiesta resta anonima
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    /**
     * Casi in cui il token e' formalmente valido ma non deve piu' funzionare.
     *
     * <p>Sono due correzioni di sicurezza gia' applicate al progetto e finora
     * verificate solo a mano, una volta sola: senza questi test tornerebbero
     * indietro in silenzio alla prima modifica del filtro JWT.
     */
    @Nested
    @DisplayName("Validita' del token oltre la firma")
    class ValiditaToken {

        @Test
        @DisplayName("un account disattivato dopo il login non accede piu' col token gia' emesso")
        void me_conAccountDisattivatoDopoIlLogin_risponde401() throws Exception {
            // given: un account registrato, autenticato, che sta usando regolarmente il token
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            String token = ottieniToken(registrazione.getEmail());

            mockMvc.perform(get(ME).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());

            // when: l'account viene disattivato mentre il suo token e' ancora valido
            Utente utente = utenteRepository.findByEmail(registrazione.getEmail()).orElseThrow();
            utente.setAttivo(false);
            utenteRepository.save(utente);

            // then: lo stesso token non vale piu'. Il filtro ricontrolla isEnabled() ad ogni
            // richiesta proprio per questo: senza, l'account disattivato continuerebbe ad
            // accedere fino alla scadenza naturale del token (fino a un'ora).
            mockMvc.perform(get(ME).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("un account riattivato torna ad accedere con lo stesso token")
        void me_conAccountRiattivato_rispondeDiNuovo200() throws Exception {
            // given: un account disattivato, il cui token e' quindi rifiutato
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            String token = ottieniToken(registrazione.getEmail());

            Utente utente = utenteRepository.findByEmail(registrazione.getEmail()).orElseThrow();
            utente.setAttivo(false);
            utenteRepository.save(utente);

            // when: l'account viene riattivato
            utente.setAttivo(true);
            utenteRepository.save(utente);

            // then: il token di prima torna a funzionare senza bisogno di rifare il login.
            // Verifica il rovescio del test precedente: il controllo e' sullo stato attuale
            // dell'account, non una revoca definitiva del token.
            mockMvc.perform(get(ME).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value(registrazione.getEmail()));
        }

        @Test
        @DisplayName("un token scaduto viene rifiutato anche se la firma e' valida")
        void me_conTokenScaduto_risponde401() throws Exception {
            // given: un account esistente e un token firmato con la chiave giusta, ma gia'
            // scaduto. Fabbricato qui invece di aspettare la scadenza vera (un'ora) o di
            // avviare un secondo contesto con una scadenza artificiale.
            RegisterRequest registrazione = dati.registerRequest();
            registraAccount(registrazione);
            String tokenScaduto = tokenScadutoPer(registrazione.getEmail());

            // when/then: la firma e' valida, quindi a rifiutarlo puo' essere solo il controllo
            // di scadenza — se sparisse, questo test diventerebbe verde con un 200
            mockMvc.perform(get(ME).header("Authorization", "Bearer " + tokenScaduto))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        /** Costruisce un JWT firmato con la chiave dell'applicazione ma con scadenza nel passato. */
        private String tokenScadutoPer(String email) {
            Instant unOraFa = Instant.now().minus(Duration.ofHours(1));
            Instant mezzOraFa = Instant.now().minus(Duration.ofMinutes(30));

            return Jwts.builder()
                    .subject(email)
                    .claim("role", "USER")
                    .issuedAt(Date.from(unOraFa))
                    .expiration(Date.from(mezzOraFa))
                    .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .compact();
        }
    }

    /** Registra un account, fallendo il test se la registrazione non riesce. */
    private void registraAccount(RegisterRequest richiesta) throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated());
    }

    /**
     * Esegue il login e restituisce il token JWT. Passa dall'endpoint reale
     * invece di generare il token con JwtService: un token costruito a mano
     * proverebbe solo che il filtro accetta i token che noi stessi sappiamo
     * fabbricare, non che la catena login -> token -> uso funzioni davvero.
     */
    private String ottieniToken(String email) throws Exception {
        String risposta = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.loginRequest(email, TestDataFactory.PASSWORD_VALIDA))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(risposta).path("data").path("token").asString();
        assertThat(token).as("token restituito dal login").isNotBlank();
        return token;
    }
}
