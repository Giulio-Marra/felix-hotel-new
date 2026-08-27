package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.RegisterRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Porta un test dentro l'applicazione dalla porta d'ingresso vera: registra un
 * account e ne ottiene il token passando dagli endpoint di autenticazione, come
 * farebbe un client.
 *
 * <p><b>Perche' e' una classe a se' e non due metodi in
 * {@link IntegrationTestBase}.</b> Quella e' la classe di infrastruttura, cioe'
 * quello che vale per ogni IT qualunque risorsa stia provando: il contesto
 * Spring, MockMvc, i contatori da azzerare. {@code /api/auth/register} e
 * {@code /api/auth/login} non sono infrastruttura, sono due rotte di una risorsa
 * precisa — e finche' stavano li' dentro, la base di <i>tutti</i> i test sapeva a
 * memoria gli URL di <i>uno</i> degli endpoint. Il giorno che quelle rotte
 * cambiano, il posto da toccare e' questo, e si vede dal nome del file.
 *
 * <p>Le rotte sono {@code public} perche' {@code AuthApiIT} e
 * {@code SecurityConfigIT} costruiscono richieste proprie e non passano dai
 * metodi qui sotto. Se le riscrivessero, dello stesso URL esisterebbe una copia
 * per ogni classe che lo nomina; importandole da qui ne esiste una sola, ed e'
 * in questo file.
 *
 * <p>Non e' un {@code @Component}: si costruisce a mano in
 * {@code IntegrationTestBase.inizializzaDati()}, come {@link TestDataFactory} e
 * per la stessa ragione — vive quanto un test, non quanto il contesto Spring, e
 * non ha nessun motivo di esistere nei contesti che non la usano.
 */
public class Autenticatore {

    public static final String REGISTER = "/api/auth/register";
    public static final String LOGIN = "/api/auth/login";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    /** Serve a costruire la richiesta di login senza scrivere il body a mano (regola 16). */
    private final TestDataFactory dati;

    public Autenticatore(MockMvc mockMvc, ObjectMapper objectMapper, TestDataFactory dati) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.dati = dati;
    }

    /**
     * Registra un account, fallendo il test se la registrazione non riesce.
     * "Serve un account che esista" e' il presupposto di qualunque test che
     * tocchi la sicurezza, non solo di quelli sugli endpoint di auth.
     */
    public void registraAccount(RegisterRequest richiesta) throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(richiesta)))
                .andExpect(status().isCreated());
    }

    /**
     * Esegue il login e restituisce il token JWT. Passa dall'endpoint reale
     * invece di generare il token con JwtService: un token costruito a mano
     * proverebbe solo che il filtro accetta i token che noi stessi sappiamo
     * fabbricare, non che la catena login -&gt; token -&gt; uso funzioni davvero.
     */
    public String ottieniToken(String email) throws Exception {
        return ottieniToken(email, TestDataFactory.PASSWORD_VALIDA);
    }

    /**
     * Come sopra, ma con una password che non e' quella di default.
     *
     * <p>Serve da quando un ADMIN puo' assegnarne una nuova a un account del
     * personale: li' la verifica che conta e' che con la password nuova si entri
     * davvero, e il metodo senza argomenti proverebbe sempre la stessa.
     */
    public String ottieniToken(String email, String password) throws Exception {
        String risposta = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dati.loginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(risposta).path("data").path("token").asString();
        assertThat(token).as("token restituito dal login").isNotBlank();
        return token;
    }
}
