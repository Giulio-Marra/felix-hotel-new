package com.felixhotel.backend.exception;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dei percorsi d'errore che non appartengono a nessuna
 * risorsa in particolare.
 *
 * <p>Nasce da una segnalazione della copertura, non da una funzionalita' nuova:
 * il report JaCoCo introdotto con la soglia ha mostrato che quattro handler del
 * {@link GlobalExceptionHandler} non erano esercitati da nessun test. Tre sono
 * reti difensive che per costruzione non scattano in funzionamento normale (vedi
 * i gap aperti nei documenti); il quarto — la rotta inesistente — era solo non
 * provato, ed e' questo file.
 */
@DisplayName("Percorsi d'errore trasversali")
class GlobalExceptionHandlerIT extends IntegrationTestBase {

    private static final String ROTTA_INESISTENTE = "/api/questa-rotta-non-esiste";

    @Nested
    @DisplayName("rotta inesistente")
    class RottaInesistente {

        @Test
        @DisplayName("da autenticato risponde 404 nella busta standard")
        void rottaInesistente_daAutenticato_risponde404() throws Exception {
            // given: un cliente qualsiasi, purche' autenticato
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);
            String token = auth.ottieniToken(cliente.getEmail());

            // when: chiede un URL sotto /api che nessun Controller serve
            mockMvc.perform(get(ROTTA_INESISTENTE)
                            .header("Authorization", "Bearer " + token))
                    // then: 404 nella busta standard, non la pagina d'errore di Spring.
                    // E' l'ultimo pezzo della convenzione che restava scoperto: anche
                    // "non esiste" deve arrivare con status, message e timestamp come
                    // tutto il resto
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("da anonimo risponde 401 e non 404")
        void rottaInesistente_daAnonimo_risponde401() throws Exception {
            // when: lo stesso URL, senza token
            mockMvc.perform(get(ROTTA_INESISTENTE))
                    // then: 401 e non 404, ed e' giusto cosi'. La catena di sicurezza
                    // viene prima del DispatcherServlet, quindi decide senza sapere se la
                    // rotta esista; il default e' anyRequest().authenticated(), e chi non
                    // si e' autenticato si ferma li'.
                    //
                    // L'effetto collaterale e' desiderabile: un anonimo non puo' scoprire
                    // quali URL esistono confrontando 404 e 401, perche' li riceve tutti
                    // uguali. Il test sta qui per fissarlo come comportamento voluto — se
                    // un domani diventasse 404, sarebbe una regressione silenziosa che
                    // regala la mappa dell'API a chi la sonda
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }
}
