package com.felixhotel.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;
import javax.crypto.SecretKey;

/**
 * Genera e valida i JWT applicativi (libreria jjwt). Payload volutamente
 * minimale: solo {@code userId} e {@code role} come claim custom, oltre
 * all'email come subject — nessuna lista di permessi nel token, come da
 * convenzione di progetto (autorizzazione basata solo sul ruolo).
 */
/*
 * final: il costruttore puo' fallire — Keys.hmacShaKeyFor rifiuta un segreto
 * troppo corto — e un costruttore che solleva un'eccezione lascia dietro di se'
 * un oggetto costruito a meta'. Una sottoclasse potrebbe raccoglierlo e usarne i
 * campi gia' valorizzati (SpotBugs: CT_CONSTRUCTOR_THROW). L'attacco vero e'
 * ormai storia — la finalizzazione e' disabilitata di default dalla 18 — quindi
 * il motivo onesto per cui la classe e' final e' un altro, piu' semplice: non
 * c'e' nessuna ragione per cui qualcuno debba ereditare da chi firma i token, e
 * toglierne la possibilita' non costa niente.
 */
@Service
public final class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String email, String ruoloNome) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("role", ruoloNome)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /** Valida firma, scadenza e che il token appartenga effettivamente all'utente atteso. */
    public boolean isTokenValid(String token, String expectedEmail) {
        return extractEmail(token).equals(expectedEmail) && !isExpired(token);
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }
}
