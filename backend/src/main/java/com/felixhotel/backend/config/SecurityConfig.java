package com.felixhotel.backend.config;

import com.felixhotel.backend.security.ApiAccessDeniedHandler;
import com.felixhotel.backend.security.ApiAuthenticationEntryPoint;
import com.felixhotel.backend.security.CustomUserDetailsService;
import com.felixhotel.backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configurazione centrale di Spring Security: filter chain stateless (JWT,
 * niente sessione HTTP), CORS verso il frontend, ed abilitazione di
 * {@code @PreAuthorize} a livello di metodo per l'autorizzazione basata sul
 * ruolo (ADMIN/STAFF/USER — niente RBAC granulare, come da convenzione di
 * progetto).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({LoginRateLimitProperties.class, RegistrationRateLimitProperties.class,
        CorsProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;
    private final CorsProperties corsProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Il catalogo si legge senza autenticarsi: e' quello che il sito mostra a
                        // chi passa di li'. Solo in lettura, pero' — il metodo fa parte della
                        // regola, quindi POST/PUT/DELETE sugli stessi path NON sono compresi e
                        // ricadono nel default autenticato piu' il @PreAuthorize del Controller.
                        // Il "/*" copre l'id del dettaglio ed e' un solo segmento: diverso dal
                        // "/**" vietato dalla convenzione, che aprirebbe in lettura anche i
                        // sottopercorsi. Ora si vede a cosa serviva quella distinzione: dei due
                        // sottopercorsi che esistono, /media va aperto e /dotazioni no — le
                        // dotazioni si leggono dalla loro rotta e dentro la tipologia, non da
                        // li'. Con un "/**" sarebbero pubblici tutti e due, e lo sarebbe dal
                        // primo minuto anche il prossimo che nascera' (le prenotazioni di una
                        // tipologia, le sue statistiche), senza che nessuno l'abbia deciso.
                        .requestMatchers(HttpMethod.GET,
                                "/api/tipologie-camera",
                                "/api/tipologie-camera/*",
                                // Le foto di una tipologia: sono le immagini della scheda di
                                // catalogo, quindi pubbliche come la scheda. Solo questo
                                // sottopercorso, solo in GET — aggiungerle e riordinarle resta
                                // agli ADMIN via @PreAuthorize.
                                "/api/tipologie-camera/*/media",
                                // Le dotazioni sono pubbliche per lo stesso motivo: sono le voci
                                // che compaiono nella scheda di ogni camera del catalogo.
                                "/api/dotazioni",
                                "/api/dotazioni/*",
                                // La ricerca di disponibilita': e' la domanda che un visitatore fa
                                // prima di registrarsi, e obbligarlo a un account per sapere se
                                // c'e' posto e' il modo di non ricevere prenotazioni. Espone quante
                                // camere restano libere, cioe' un dato di riempimento — che pero'
                                // e' esattamente cio' che un albergo pubblica di sua volonta' su
                                // ogni portale, non un'informazione che si sta lasciando sfuggire.
                                // Nessun path delle *prenotazioni* entra qui: quelle restano
                                // dietro l'autenticazione, e da questa rotta non se ne intravede
                                // nessuna — solo un conteggio.
                                "/api/disponibilita",
                                // Nome, indirizzo, recapiti e orari della struttura: le righe in
                                // fondo a ogni pagina di un sito d'albergo. Qui il path aperto e'
                                // quello lungo e la risorsa vera sta su /api/impostazioni, che
                                // resta autenticata: il verso e' voluto. Dei due sottoinsiemi,
                                // l'eccezione dichiarata e' il piu' piccolo — l'identita' fiscale,
                                // il CIN e il codice per Alloggiati Web vivono sull'altra rotta e
                                // non hanno modo di finire qui, perche' il DTO pubblico quei campi
                                // non li ha proprio (vedi ImpostazioniHotelMapper).
                                "/api/impostazioni/pubbliche"
                        ).permitAll()
                        .requestMatchers(
                                // Solo questi due endpoint di auth sono pubblici, elencati uno per uno:
                                // un wildcard "/api/auth/**" renderebbe pubblico anche /api/auth/me e
                                // qualsiasi endpoint di auth aggiunto in futuro, senza accorgersene.
                                "/api/auth/register",
                                "/api/auth/login",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                // Lo spec YAML caricato da Swagger UI (vedi OpenApiSpecConfig)
                                "/openapi/**",
                                // Spring Boot inoltra ogni errore a /error con un dispatch interno, che
                                // passa comunque dalla security chain: senza questo permitAll qualsiasi
                                // errore (401, 409, validazione fallita) tornerebbe al client come 403
                                // con body vuoto, perche' bloccato prima di poter essere scritto.
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // Errori che nascono qui nella filter chain, prima del DispatcherServlet: il
                // GlobalExceptionHandler non li vede, quindi la busta standard gliela danno
                // questi due. L'entry point serve anche a rispondere 401 invece del 403 che
                // Spring Security darebbe di default a una richiesta non autenticata; il 403
                // resta per chi e' autenticato ma non autorizzato, dove ha senso.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS costruito a partire dalle origini configurate, non da una lista
     * scritta qui dentro: vedi {@link CorsProperties} per il perche'. Se non ne
     * e' configurata nessuna — il default — nessuna origine esterna passa, che
     * e' il comportamento giusto per un ambiente di cui non si sa niente.
     *
     * <p>{@code allowCredentials} resta <b>false</b>: l'autenticazione viaggia
     * nell'header {@code Authorization} come Bearer token, che il browser non
     * allega da solo. Metterlo a true dichiarerebbe un uso di cookie/credenziali
     * implicite che questo progetto non fa, e sarebbe una promessa senza codice
     * dietro (regola 17) — oltre a rendere davvero pericoloso un pattern largo
     * come {@code http://localhost:*}. Va rimesso a true solo se un giorno il
     * token passera' per un cookie, e in quel caso le origini dovranno essere
     * esatte.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
