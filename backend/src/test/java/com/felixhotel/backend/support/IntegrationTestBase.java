package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.service.LoginAttemptService;
import com.felixhotel.backend.service.RegistrationAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
// Spring Boot 4 ha spacchettato spring-boot-test-autoconfigure in moduli per tecnologia:
// @AutoConfigureMockMvc vive in spring-boot-webmvc-test, non piu' sotto
// org.springframework.boot.test.autoconfigure.web.servlet (package di Boot 3).
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base di ogni test di integrazione (classi {@code *IT}): avvia il contesto
 * Spring completo e il Postgres di {@link TestcontainersConfig}, ed espone
 * {@link MockMvc} per chiamare gli endpoint dal bordo HTTP.
 *
 * <p>Sta tutto qui per un motivo di forma: le annotazioni di contesto si
 * scrivono una volta sola. Un IT eredita da questa classe e contiene solo i
 * suoi test — se una classe di test riporta {@code @SpringBootTest} per conto
 * suo, vuol dire che sta divergendo dallo standard.
 *
 * <p>MockMvc e non un client HTTP reale: attraversa comunque tutta la catena
 * che ci interessa (filtri di sicurezza compresi, quindi 401 e 403 sono quelli
 * veri), ma senza aprire una porta di rete.
 *
 * <p><b>Isolamento fra test</b>: il database NON viene ripulito
 * automaticamente. I test qui dentro passano dagli endpoint HTTP, e una
 * transazione di test non avvolge quello che succede dietro MockMvc in modo
 * affidabile; per questo ogni test si costruisce i propri dati con email
 * univoche (vedi {@link TestDataFactory#emailUnivoca()}) invece di dipendere
 * dallo stato lasciato da altri.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class IntegrationTestBase {

    /** Rotte usate dagli helper qui sotto, che servono a piu' di un IT. */
    protected static final String REGISTER = "/api/auth/register";
    protected static final String LOGIN = "/api/auth/login";

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Lo stesso ObjectMapper dell'applicazione (Jackson 3, package
     * {@code tools.jackson}): serializzare i body di richiesta con un mapper
     * diverso da quello che l'app usa per deserializzarli renderebbe il test
     * meno fedele proprio dove deve esserlo.
     */
    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Contatore dei tentativi di login falliti, azzerato prima di ogni test.
     * Vive in memoria e il contesto Spring e' condiviso da tutta la suite:
     * senza azzerarlo, i login sbagliati di proposito da un test resterebbero
     * contati per quelli successivi, che chiamando dallo stesso indirizzo si
     * vedrebbero rifiutare un login legittimo con un 429 uscito dal nulla.
     */
    @Autowired
    private LoginAttemptService loginAttemptService;

    /**
     * Contatore delle registrazioni, azzerato prima di ogni test per lo stesso
     * motivo del precedente — con un'aggravante: qui si conta ogni tentativo, non
     * solo quelli falliti, quindi anche il semplice {@link #registraAccount}
     * consuma il budget dell'indirizzo da cui MockMvc chiama.
     *
     * <p><b>Il vincolo che ne deriva</b>: dentro un singolo test le registrazioni
     * fatte dallo stesso indirizzo sono un numero limitato — quante lo dice
     * {@code felix.security.registration.tentativi-liberi-ip} nel profilo di test,
     * dove e' scritto anche il conto esatto. A chi ne servissero di piu' basta
     * cambiare indirizzo alla richiesta.
     */
    @Autowired
    private RegistrationAttemptService registrationAttemptService;

    protected TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
        loginAttemptService.reset();
        registrationAttemptService.reset();
    }

    /** Serializza un oggetto nel JSON da mandare come body della richiesta. */
    protected String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }

    /**
     * Registra un account, fallendo il test se la registrazione non riesce.
     * Sta qui e non in un singolo IT perche' "serve un account che esista"
     * e' il presupposto di qualunque test che tocchi la sicurezza, non solo
     * di quelli sugli endpoint di auth.
     */
    protected void registraAccount(RegisterRequest richiesta) throws Exception {
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
    protected String ottieniToken(String email) throws Exception {
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
