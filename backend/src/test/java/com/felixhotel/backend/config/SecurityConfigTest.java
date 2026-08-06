package com.felixhotel.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari della configurazione CORS: verificano che la lista di origini
 * configurata arrivi davvero dentro la {@code CorsConfiguration}, e che una
 * lista vuota non lasci passare nessuno.
 *
 * <p>Sono unitari e non di integrazione perche' qui basta costruire l'oggetto:
 * far partire il contesto Spring una seconda volta con un altro profilo, solo
 * per provare l'altra meta' della configurazione, costerebbe un secondo
 * container Postgres. Il caso "di default non passa nessuno" e' comunque
 * verificato anche dal bordo HTTP in {@code SecurityConfigIT}.
 */
@DisplayName("Configurazione CORS")
class SecurityConfigTest {

    /** Il pattern su cui SecurityConfig registra la configurazione: vale per tutte le rotte. */
    private static final String TUTTE_LE_ROTTE = "/**";

    @Nested
    @DisplayName("Con origini configurate")
    class ConOriginiConfigurate {

        @Test
        @DisplayName("l'origine che corrisponde al pattern viene ammessa")
        void cors_conOrigineCorrispondente_laAmmette() {
            // given: la configurazione del profilo dev, con il jolly sulla porta
            CorsConfiguration configurazione = corsConfigurationCon(List.of("http://localhost:*"));

            // when/then: il frontend in sviluppo passa, qualunque porta gli tocchi
            assertThat(configurazione.checkOrigin("http://localhost:5173")).isNotNull();
            assertThat(configurazione.checkOrigin("http://localhost:3000")).isNotNull();
        }

        @Test
        @DisplayName("un'origine estranea resta esclusa")
        void cors_conOrigineEstranea_laEsclude() {
            // given: la stessa configurazione
            CorsConfiguration configurazione = corsConfigurationCon(List.of("http://localhost:*"));

            // when/then: il jolly vale sulla porta, non sull'host
            assertThat(configurazione.checkOrigin("http://sito-di-un-altro.example")).isNull();
            assertThat(configurazione.checkOrigin("https://localhost.example.com")).isNull();
        }

        @Test
        @DisplayName("le credenziali restano disabilitate")
        void cors_conOriginiConfigurate_nonAmmetteCredenziali() {
            // given: una configurazione qualsiasi
            CorsConfiguration configurazione = corsConfigurationCon(List.of("http://localhost:*"));

            // when/then: il token viaggia nell'header Authorization, non in un cookie, quindi
            // non serve. Abilitarlo insieme a un pattern largo come "localhost:*" e' invece
            // la combinazione che rende il CORS pericoloso davvero.
            assertThat(configurazione.getAllowCredentials()).isFalse();
        }
    }

    @Nested
    @DisplayName("Senza origini configurate (il default)")
    class SenzaOriginiConfigurate {

        @Test
        @DisplayName("nessuna origine viene ammessa")
        void cors_senzaOrigini_nonAmmetteNessuno() {
            // given: quello che parte in un ambiente in cui nessuno ha configurato niente
            CorsConfiguration configurazione = corsConfigurationCon(List.of());

            // when/then: chiuso. E' il punto della modifica: il default sicuro non e' una
            // raccomandazione ma il comportamento, e per aprire bisogna dirlo.
            assertThat(configurazione.checkOrigin("http://localhost:5173")).isNull();
            assertThat(configurazione.checkOrigin("https://felixhotel.example")).isNull();
        }
    }

    /**
     * Costruisce la CorsConfiguration come farebbe l'applicazione all'avvio. Le
     * altre dipendenze di SecurityConfig sono null perche' il bean del CORS non
     * le tocca: passargli dei mock non renderebbe il test piu' vero.
     */
    private CorsConfiguration corsConfigurationCon(List<String> origini) {
        SecurityConfig config = new SecurityConfig(null, null, null, null, new CorsProperties(origini));

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();

        CorsConfiguration configurazione = source.getCorsConfigurations().get(TUTTE_LE_ROTTE);
        assertThat(configurazione).as("configurazione CORS registrata su " + TUTTE_LE_ROTTE).isNotNull();
        return configurazione;
    }
}
