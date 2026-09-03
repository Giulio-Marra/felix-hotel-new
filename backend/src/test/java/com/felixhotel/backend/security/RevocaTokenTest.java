package com.felixhotel.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le due regole della revoca dei token, prese dove sono deterministiche.
 *
 * <p><b>Quello che qui non si prova e' che la revoca funzioni</b> — quello lo fa
 * {@code AuthApiIT} dal bordo HTTP, cambiando davvero una password e riprovando il vecchio
 * token. Qui c'e' il dettaglio che l'IT non saprebbe vedere fallire in modo affidabile: il
 * confronto fra un istante con la precisione del secondo e uno che ne ha di piu'.
 */
@DisplayName("Revoca dei token")
class RevocaTokenTest {

    private static final String SEGRETO = "chiave-di-test-non-segreta-lunga-abbastanza-per-hmac-sha256";

    @Nested
    @DisplayName("Precisione")
    class Precisione {

        private final JwtService jwtService = new JwtService(SEGRETO, 3_600_000L);

        @Test
        @DisplayName("un token emesso subito dopo la revoca resta valido")
        void emessoSubitoDopo_restaValido() {
            // **E' il test che ha corretto il disegno.** La prima stesura arrotondava la
            // soglia al secondo successivo, per via della precisione al secondo dello 'iat'
            // di un JWT: cosi' chi reimpostava la password e accedeva nello stesso secondo
            // — cioe' il caso normale, quello che il messaggio di risposta invita a fare —
            // si vedeva rifiutare il proprio token nuovo. Ora l'emissione sta nel token in
            // millisecondi, e la distinzione regge anche a distanza di un millisecondo
            LocalDateTime revoca = IstanteRevoca.adesso();
            String tokenNuovo = jwtService.generateToken(1L, "prova@example.com", "USER");

            assertThat(jwtService.emessoPrimaDella(tokenNuovo, revoca)).isFalse();
        }

        @Test
        @DisplayName("un token emesso subito prima della revoca cade")
        void emessoSubitoPrima_cade() {
            // Il gemello, e insieme all'altro delimita la cosa che conta: la revoca deve
            // separare due token distanti pochi millisecondi, non pochi secondi
            String tokenVecchio = jwtService.generateToken(1L, "prova@example.com", "USER");
            LocalDateTime revoca = IstanteRevoca.adesso();

            assertThat(jwtService.emessoPrimaDella(tokenVecchio, revoca)).isTrue();
        }
    }

    @Nested
    @DisplayName("JwtService.emessoPrimaDella")
    class Confronto {

        private final JwtService jwtService = new JwtService(SEGRETO, 3_600_000L);

        @Test
        @DisplayName("senza soglia nessun token e' da rifiutare")
        void senzaSoglia_nessunRifiuto() {
            // E' il caso di quasi tutti gli account: null vuol dire che non e' mai stato
            // revocato niente, e leggerlo come "revocato all'inizio dei tempi" butterebbe
            // fuori tutti
            String token = jwtService.generateToken(1L, "prova@example.com", "USER");

            assertThat(jwtService.emessoPrimaDella(token, null)).isFalse();
        }

        @Test
        @DisplayName("un token emesso prima della soglia si rifiuta")
        void emessoPrima_siRifiuta() {
            String token = jwtService.generateToken(1L, "prova@example.com", "USER");

            assertThat(jwtService.emessoPrimaDella(token, LocalDateTime.now().plusMinutes(5)))
                    .isTrue();
        }

        @Test
        @DisplayName("un token emesso dopo la soglia resta valido")
        void emessoDopo_restaValido() {
            // Altrimenti cambiare la password una volta butterebbe fuori anche chi si
            // autentica dopo, cioe' per sempre
            String token = jwtService.generateToken(1L, "prova@example.com", "USER");

            assertThat(jwtService.emessoPrimaDella(token, LocalDateTime.now().minusMinutes(5)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Utente e Staff")
    class ScritturaDellaPassword {

        @Test
        @DisplayName("cambiare una password esistente annota la revoca")
        void impostaPassword_conPasswordPrecedente_revoca() {
            var utente = new com.felixhotel.backend.entity.Utente();
            utente.impostaPassword("vecchio", null);

            LocalDateTime soglia = LocalDateTime.of(2026, 9, 3, 10, 15, 31);
            utente.impostaPassword("nuovo", soglia);

            assertThat(utente.getPasswordHash()).isEqualTo("nuovo");
            assertThat(utente.getTokenNonValidiPrimaDi()).isEqualTo(soglia);
        }

        @Test
        @DisplayName("darne una a chi non ne aveva non revoca niente")
        void impostaPassword_primaVolta_nonRevoca() {
            // E' il caso della registrazione e dell'invito accettato. Annotare la revoca
            // qui farebbe rifiutare il primo token di quell'account, se il login arrivasse
            // nello stesso secondo — cioe' romperebbe il caso normale per proteggere token
            // che non esistono
            var staff = new com.felixhotel.backend.entity.Staff();

            staff.impostaPassword("primo", LocalDateTime.of(2026, 9, 3, 10, 15, 31));

            assertThat(staff.getPasswordHash()).isEqualTo("primo");
            assertThat(staff.getTokenNonValidiPrimaDi()).isNull();
        }
    }
}
