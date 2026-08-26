package com.felixhotel.backend.config;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.backend.support.SoloAdminTestController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.felixhotel.backend.support.Autenticatore.LOGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione della configurazione di sicurezza: CORS e permessi, cioe'
 * le due cose che non si vedono guardando un singolo endpoint ma che valgono
 * per tutti.
 *
 * <p>Gira nel profilo {@code test}, che <b>non</b> configura nessuna origine
 * CORS: e' apposta, perche' quello che si vuole proteggere qui e' proprio il
 * comportamento di default.
 */
@DisplayName("Configurazione di sicurezza")
class SecurityConfigIT extends IntegrationTestBase {

    @Nested
    @DisplayName("CORS")
    class Cors {

        @Test
        @DisplayName("senza origini configurate una richiesta cross-origin viene rifiutata")
        void cors_senzaOriginiConfigurate_rifiutaLaRichiestaCrossOrigin() throws Exception {
            // given/when: il preflight che il browser manda prima di una POST cross-origin,
            // da un'origine qualsiasi. Nessuna origine e' configurata in questo profilo.
            mockMvc.perform(options(LOGIN)
                            .header("Origin", "http://sito-di-un-altro.example")
                            .header("Access-Control-Request-Method", "POST"))
                    // then: rifiutato. Il valore di questo test non e' il codice di stato ma
                    // il default che protegge: la lista delle origini vive nella
                    // configurazione (CorsProperties) e se qualcuno rimettesse un valore
                    // permissivo in application.properties — dove varrebbe per tutti gli
                    // ambienti, produzione compresa — questo test lo direbbe subito.
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("una richiesta senza header Origin non e' cross-origin e passa")
        void cors_senzaHeaderOrigin_nonVieneToccata() throws Exception {
            // given/when: la stessa rotta chiamata senza Origin (curl, un altro servizio,
            // il frontend servito dallo stesso host)
            mockMvc.perform(get("/api/auth/me"))
                    // then: il CORS non c'entra nulla e la richiesta prosegue fino alla
                    // catena di sicurezza, che risponde 401 perche' manca il token. Senza
                    // questo test, un CORS chiuso male potrebbe bloccare tutto e il test
                    // precedente passerebbe lo stesso, per il motivo sbagliato.
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Nested
    @DisplayName("Permessi negati da @PreAuthorize")
    class PermessiNegati {

        @Test
        @DisplayName("un utente autenticato senza il ruolo richiesto riceve 403 nella busta standard")
        void preAuthorize_conRuoloInsufficiente_risponde403() throws Exception {
            // given: un account USER autenticato regolarmente
            RegisterRequest registrazione = dati.registerRequest();
            auth.registraAccount(registrazione);
            String token = auth.ottieniToken(registrazione.getEmail());

            // when: chiama un endpoint riservato agli ADMIN
            mockMvc.perform(get(SoloAdminTestController.PATH)
                            .header("Authorization", "Bearer " + token))
                    // then: 403 con la busta standard, e soprattutto NON 500. L'eccezione
                    // di accesso negato viene rilanciata dall'advice apposta per farla
                    // risalire alla filter chain: se qualcuno togliesse quel rilancio
                    // credendolo codice morto, finirebbe nel catch-all generico e ogni
                    // permesso negato diventerebbe un errore interno.
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("una richiesta senza token sullo stesso endpoint riceve 401, non 403")
        void preAuthorize_senzaAutenticazione_risponde401() throws Exception {
            // given/when: nessun token
            mockMvc.perform(get(SoloAdminTestController.PATH))
                    // then: 401 e non 403. Attenzione a cosa prova davvero questo test: la
                    // rotta non e' fra i permitAll, quindi la richiesta viene fermata dalla
                    // regola anyRequest().authenticated() e il @PreAuthorize non entra
                    // nemmeno in gioco. Il caso in cui e' il @PreAuthorize a scattare su un
                    // anonimo — endpoint permitAll piu' annotazione — oggi non esiste in
                    // produzione e non e' esercitabile da qui senza cambiare SecurityConfig.
                    // Il test resta perche' fissa la coppia 401/403 vista dal client: preso
                    // insieme al precedente, dice che i due casi danno risposte diverse.
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }
}
