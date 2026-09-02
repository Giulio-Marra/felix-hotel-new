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
    public static final String VERIFICA = "/api/auth/verifica-email";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    /** Serve a costruire la richiesta di login senza scrivere il body a mano (regola 16). */
    private final TestDataFactory dati;

    /** Da cui si legge il link di conferma che la registrazione ha appena mandato. */
    private final PostaDiProva posta;

    public Autenticatore(MockMvc mockMvc, ObjectMapper objectMapper, TestDataFactory dati,
                         PostaDiProva posta) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.dati = dati;
        this.posta = posta;
    }

    /**
     * Registra un account <b>e ne conferma l'indirizzo</b>, fallendo il test se uno dei
     * due passi non riesce. "Serve un account che esista e possa entrare" e' il
     * presupposto di qualunque test che tocchi la sicurezza, non solo di quelli sugli
     * endpoint di auth.
     *
     * <p><b>I due passi stanno insieme dal 2026-09-02</b>, ed e' la modifica che ha
     * toccato piu' test di tutto il branch: da quel giorno un account non confermato non
     * si autentica, quindi ogni IT che registrava e poi faceva login avrebbe smesso di
     * funzionare. Metterli insieme qui invece che in ognuno di quei test e' esattamente
     * il motivo per cui questa classe esiste.
     *
     * <p><b>La conferma passa dal bordo HTTP e dal link vero</b>, non da una scrittura
     * diretta nel database: il token si legge dall'email appena mandata, come farebbe
     * una persona. Costa una chiamata in piu' per test, e in cambio fa girare il flusso
     * di verifica <b>in ogni singolo IT del progetto</b> — molta piu' copertura di
     * quanta ne darebbe un test dedicato.
     *
     * <p>Chi vuole il caso opposto — un account registrato e <i>non</i> confermato — usa
     * {@link #registraSenzaConfermare}.
     */
    public void registraAccount(RegisterRequest richiesta) throws Exception {
        registraSenzaConfermare(richiesta);
        confermaIndirizzo(richiesta.getEmail());
    }

    /** Solo la registrazione: l'account resta non confermato e non si autentica. */
    public void registraSenzaConfermare(RegisterRequest richiesta) throws Exception {
        mockMvc.perform(post(REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(richiesta)))
                .andExpect(status().isCreated());
    }

    /** Apre il link di conferma arrivato a questo indirizzo. */
    public void confermaIndirizzo(String email) throws Exception {
        mockMvc.perform(post(VERIFICA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + posta.tokenPer(email) + "\"}"))
                .andExpect(status().isOk());
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
