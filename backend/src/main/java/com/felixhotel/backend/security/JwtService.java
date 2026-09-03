package com.felixhotel.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /** Nome del claim con l'emissione in millisecondi. Vedi {@link #generateToken}. */
    private static final String EMESSO_IL = "emessoIl";

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
                // L'emissione in millisecondi, oltre allo 'iat' standard che ne porta i
                // secondi. Serve alla revoca: con la sola precisione del secondo, un token
                // emesso nello stesso secondo in cui una password cambia sarebbe
                // indistinguibile da uno emesso subito prima — e qualunque regola si
                // scegliesse per quel pareggio sbaglierebbe un caso reale. Rifiutando si
                // butta fuori chi ha appena reimpostato la password e accede subito;
                // accettando si lascia dentro per un secondo chi si voleva cacciare.
                // Il token lo emettiamo noi, quindi la precisione ce la scegliamo.
                .claim(EMESSO_IL, now.getTime())
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

    /**
     * Se il token e' stato emesso <b>prima</b> che quell'account revocasse i suoi.
     *
     * <p><b>Sta qui e non nel filtro</b> perche' e' l'unica classe che sappia leggere
     * dentro un token, e perche' il confronto ha un dettaglio che va spiegato una volta
     * sola: la data di emissione ha la precisione di un <b>secondo</b> — cosi' la
     * definisce la specifica — mentre la soglia e' un {@code TIMESTAMP} del database, che
     * di precisione ne ha di piu'. Il confronto regge lo stesso perche' la soglia viene
     * scritta gia' arrotondata al secondo successivo, vedi {@link IstanteRevoca}: senza
     * quell'arrotondamento, un token emesso nello stesso secondo della revoca
     * sopravviverebbe o cadrebbe a seconda di millisecondi che il token non porta.
     *
     * <p>L'istante del token si riporta al <b>fuso di sistema</b>, che e' la stessa
     * convenzione con cui i {@code TIMESTAMP} senza fuso vengono letti in tutto il
     * progetto (vedi {@code PrenotazioneMapper.toOffset}). Il giorno che quelle colonne
     * diventassero {@code TIMESTAMPTZ}, questa conversione va rifatta e non adattata.
     *
     * @param soglia da quando i token non valgono piu'; {@code null} vuol dire che non e'
     *               mai stato revocato niente, e allora nessun token e' da rifiutare
     */
    public boolean emessoPrimaDella(String token, LocalDateTime soglia) {
        if (soglia == null) {
            return false;
        }

        return emissione(token).isBefore(soglia);
    }

    /**
     * Quando il token e' stato emesso, al millisecondo.
     *
     * <p><b>Il ripiego sullo {@code iat} standard non e' teorico</b>: i token emessi prima
     * del 2026-09-03 non hanno il claim in millisecondi, e finche' non scadono passano da
     * qui. Per loro si torna alla precisione del secondo, che e' l'unica che hanno.
     *
     * <p>L'istante si riporta al <b>fuso di sistema</b>, che e' la stessa convenzione con
     * cui i {@code TIMESTAMP} senza fuso vengono letti in tutto il progetto (vedi
     * {@code PrenotazioneMapper.toOffset}). Il giorno che quelle colonne diventassero
     * {@code TIMESTAMPTZ}, questa conversione va rifatta e non adattata.
     */
    private LocalDateTime emissione(String token) {
        Instant istante = extractClaim(token, claims -> {
            Long millisecondi = claims.get(EMESSO_IL, Long.class);
            return millisecondi != null
                    ? Instant.ofEpochMilli(millisecondi)
                    : claims.getIssuedAt().toInstant();
        });

        return LocalDateTime.ofInstant(istante, ZoneId.systemDefault());
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
