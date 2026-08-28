package com.felixhotel.backend.security;

import com.felixhotel.backend.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unitari di {@link ChiamanteCorrente}.
 *
 * <p>Niente mock: la classe non ha collaboratori, e cio' che legge — il
 * {@code SecurityContextHolder} — questi test lo riempiono a mano. E' la stessa
 * forma gia' usata da {@code PrenotazioneServiceImplTest}, che il contesto se lo
 * costruisce da se' per ogni caso.
 *
 * <p><b>La meta' che vale davvero e' {@link ChiamanteCorrente#personale}</b>, ed
 * e' il motivo per cui la tabella qui sotto e' completa invece di fermarsi ai
 * due casi ovvi: la regola pretende <i>ruolo</i> e <i>tipo</i> insieme, quindi
 * chi la rompesse lo farebbe togliendo una delle due meta' — e una tabella che
 * provi solo "staff vero passa" e "cliente vero non passa" resterebbe verde in
 * entrambi i casi. I due incroci storti (un cliente con ruolo da staff, un
 * account del personale col ruolo di un cliente) sono gli unici che la vedono
 * agire.
 *
 * <p>Fino al 2026-08-28 questa regola stava dentro {@code PrenotazioneServiceImpl}
 * e la provavano i suoi test, di rimbalzo, attraverso un elenco di prenotazioni.
 * Quelli restano e continuano a servire — dicono che <i>le prenotazioni</i>
 * applicano la regola — ma fallendo indicherebbero il punto sbagliato.
 */
@DisplayName("ChiamanteCorrente")
class ChiamanteCorrenteTest {

    private static final String EMAIL = "mario.rossi@example.com";

    private final ChiamanteCorrente chiamanteCorrente = new ChiamanteCorrente();

    /**
     * Il contesto e' legato al thread e JUnit ne riusa uno solo: senza questo,
     * l'autenticazione di un test resterebbe in piedi per quello dopo.
     */
    @AfterEach
    void svuotaContesto() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("autenticato")
    class Autenticato {

        @Test
        @DisplayName("restituisce il principal che sta nel contesto")
        void autenticato_conPrincipalNelContesto_loRestituisce() {
            // given: un cliente autenticato, come dopo un login riuscito
            AppUserPrincipal principal = principal(TipoAccount.CLIENTE, "USER");
            autentica(principal);

            // when/then: e' lo stesso oggetto, non una copia: da qui in poi i Service
            // ci leggono id e tipo, e ricostruirlo vorrebbe dire poterlo sbagliare
            assertThat(chiamanteCorrente.autenticato()).isSameAs(principal);
        }

        @Test
        @DisplayName("col contesto vuoto solleva UnauthorizedException")
        void autenticato_senzaAutenticazione_sollevaUnauthorized() {
            // given: nessun login in corso su questo thread
            SecurityContextHolder.clearContext();

            // when/then: 401 e non NullPointerException, che sarebbe un 500
            assertThatThrownBy(chiamanteCorrente::autenticato)
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessageContaining("Nessun account autenticato");
        }

        @Test
        @DisplayName("con un anonimo nel contesto solleva UnauthorizedException")
        void autenticato_conPrincipalAnonimo_sollevaUnauthorized() {
            // given: cio' che Spring Security mette nel contesto per una richiesta senza
            // token quando la catena dei filtri prevede l'anonimo — il principal e' la
            // stringa "anonymousUser", non un AppUserPrincipal
            SecurityContextHolder.getContext().setAuthentication(
                    new AnonymousAuthenticationToken("chiave", "anonymousUser",
                            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

            // when/then: 401. E' il caso per cui l'instanceof esiste: senza, quella
            // stringa diventerebbe una ClassCastException, cioe' un 500 al posto di un 401
            assertThatThrownBy(chiamanteCorrente::autenticato)
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Nested
    @DisplayName("personale")
    class Personale {

        @Test
        @DisplayName("un account del personale con ruolo STAFF e' personale")
        void personale_conTipoPersonaleERuoloStaff_eVero() {
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.PERSONALE, "STAFF"))).isTrue();
        }

        @Test
        @DisplayName("un account del personale con ruolo ADMIN e' personale")
        void personale_conTipoPersonaleERuoloAdmin_eVero() {
            // Non e' il doppione del caso qui sopra: i due ruoli stanno in un ||, e
            // finche' nessun test arriva con ADMIN quella meta' della condizione non
            // si e' mai vista agire
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.PERSONALE, "ADMIN"))).isTrue();
        }

        @Test
        @DisplayName("un cliente col ruolo di uno staff non e' personale")
        void personale_conTipoClienteERuoloStaff_eFalso() {
            // E' l'account ibrido: una riga di 'utente' a cui una UPDATE a mano ha dato
            // il ruolo STAFF. Nessun endpoint lo produce, ed e' precisamente il caso da
            // cui la regola difende — se qui passasse, leggerebbe la roba di tutti
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.CLIENTE, "STAFF"))).isFalse();
        }

        @Test
        @DisplayName("un cliente col ruolo di un amministratore non e' personale")
        void personale_conTipoClienteERuoloAdmin_eFalso() {
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.CLIENTE, "ADMIN"))).isFalse();
        }

        @Test
        @DisplayName("un account del personale col ruolo USER non e' personale")
        void personale_conTipoPersonaleERuoloUser_eFalso() {
            // L'ibrido dall'altra parte: sta nella tabella giusta ma non ha il
            // privilegio. E' il caso che prova che il tipo da solo non basta, come il
            // precedente prova che non basta il ruolo
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.PERSONALE, "USER"))).isFalse();
        }

        @Test
        @DisplayName("un cliente normale non e' personale")
        void personale_conTipoClienteERuoloUser_eFalso() {
            assertThat(chiamanteCorrente.personale(principal(TipoAccount.CLIENTE, "USER"))).isFalse();
        }
    }

    private AppUserPrincipal principal(TipoAccount tipo, String ruolo) {
        return new AppUserPrincipal(tipo, 7L, EMAIL, "hash", "Mario", "Rossi", ruolo, true);
    }

    private void autentica(AppUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
