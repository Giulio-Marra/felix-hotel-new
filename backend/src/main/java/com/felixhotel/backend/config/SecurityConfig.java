package com.felixhotel.backend.config;

import com.felixhotel.backend.security.ApiAccessDeniedHandler;
import com.felixhotel.backend.security.ApiAuthenticationEntryPoint;
import com.felixhotel.backend.security.CustomUserDetailsService;
import com.felixhotel.backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;
    private final ApiAccessDeniedHandler apiAccessDeniedHandler;

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
     * CORS aperto verso il frontend in sviluppo locale (React su localhost,
     * porta variabile). Da restringere ai domini reali quando si andra' in
     * produzione.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
