package com.felixhotel.backend.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Filtro eseguito una volta per richiesta: se e' presente un header
 * {@code Authorization: Bearer <token>} valido, popola il SecurityContext
 * cosi' che {@code @PreAuthorize} possa valutare il ruolo a valle. Se il
 * token manca o non e' valido, la richiesta prosegue anonima: sara' la
 * {@code SecurityConfig} a bloccare gli endpoint protetti.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            try {
                String token = authHeader.substring(BEARER_PREFIX.length());
                String email = jwtService.extractEmail(token);

                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                    // isEnabled() (= colonna 'attivo') va ricontrollato ad ogni richiesta, non solo
                    // al login: altrimenti un account disattivato continuerebbe ad accedere con il
                    // token gia' emesso fino alla sua scadenza. L'utente e' gia' caricato da DB qui,
                    // quindi il controllo non costa una query in piu'.
                    if (userDetails.isEnabled() && jwtService.isTokenValid(token, userDetails.getUsername())) {
                        var authToken = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            } catch (JwtException | UsernameNotFoundException ex) {
                // Token assente/malformato/scaduto: nessuna autenticazione impostata, si prosegue anonimi.
            }
        }

        filterChain.doFilter(request, response);
    }
}
