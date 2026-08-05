package com.felixhotel.backend.service;

import com.felixhotel.backend.config.LoginRateLimitProperties;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.service.impl.LoginAttemptServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unitari del ritardo progressivo sui login falliti.
 *
 * <p>E' la prima classe del progetto con rami veri da coprire — soglia
 * superata o no, raddoppio, tetto, finestra scaduta — e per questo i test sono
 * molti piu' di quelli di {@code AuthServiceImplTest}: si verifica una
 * decisione presa dal codice, non un cablaggio.
 *
 * <p>Il tempo e' pilotato da {@link OrologioDiTest} e non aspettato davvero:
 * una suite che per verificare un'attesa di otto secondi ne dormisse otto
 * smetterebbe subito di essere eseguita, e un test che non si esegue non
 * protegge niente.
 */
@DisplayName("LoginAttemptServiceImpl")
class LoginAttemptServiceImplTest {

    private static final String EMAIL = "mario.rossi@example.com";
    private static final String IP = "203.0.113.7";
    private static final String ALTRO_IP = "203.0.113.8";

    /** Valori scelti piccoli e tondi per poter contare i raddoppi a mente: 1s, 2s, 4s, poi il tetto di 8s. */
    private static final Duration RITARDO_INIZIALE = Duration.ofSeconds(1);
    private static final Duration RITARDO_MASSIMO = Duration.ofSeconds(8);
    private static final Duration FINESTRA = Duration.ofMinutes(10);

    private OrologioDiTest orologio;

    @BeforeEach
    void inizializza() {
        orologio = new OrologioDiTest(Instant.parse("2026-08-05T10:00:00Z"));
    }

    /**
     * Service con le soglie indicate. Ogni test se lo costruisce con i numeri
     * che gli servono, cosi' i contatori partono sempre puliti e i test non si
     * condizionano a vicenda.
     */
    private LoginAttemptServiceImpl serviceCon(int tentativiLiberiEmail, int tentativiLiberiIp) {
        return new LoginAttemptServiceImpl(
                new LoginRateLimitProperties(tentativiLiberiEmail, tentativiLiberiIp,
                        RITARDO_INIZIALE, RITARDO_MASSIMO, FINESTRA),
                orologio);
    }

    @Nested
    @DisplayName("tentativi liberi")
    class TentativiLiberi {

        @Test
        @DisplayName("entro la soglia non impone nessuna attesa")
        void checkNotThrottled_sottoLaSoglia_nonRitarda() {
            // given: due tentativi liberi, e due gia' falliti
            LoginAttemptService service = serviceCon(2, 100);
            service.recordFailure(EMAIL, IP);
            service.recordFailure(EMAIL, IP);

            // when/then: chi ha appena consumato i tentativi liberi puo' ancora riprovare
            // subito. E' il caso dell'utente vero che sbaglia password: non deve accorgersi
            // che questa protezione esiste.
            assertThatCode(() -> service.checkNotThrottled(EMAIL, IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("al primo fallimento oltre la soglia rifiuta con 429")
        void checkNotThrottled_oltreLaSoglia_sollevaTooManyRequests() {
            // given: due tentativi liberi consumati, piu' uno
            LoginAttemptService service = serviceCon(2, 100);
            service.recordFailure(EMAIL, IP);
            service.recordFailure(EMAIL, IP);
            service.recordFailure(EMAIL, IP);

            // when/then: 429, con l'attesa residua scritta nel messaggio perche' il client
            // sappia quando riprovare (la busta non ha un campo dedicato, vedi regola 10)
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP))
                    .isInstanceOf(TooManyRequestsException.class)
                    .hasMessageContaining("1 secondo")
                    .extracting(ex -> ((TooManyRequestsException) ex).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("passata l'attesa lascia ritentare")
        void checkNotThrottled_dopoLAttesa_lasciaPassare() {
            // given: un'attesa di un secondo in corso
            LoginAttemptService service = serviceCon(2, 100);
            service.recordFailure(EMAIL, IP);
            service.recordFailure(EMAIL, IP);
            service.recordFailure(EMAIL, IP);

            // when: passa il secondo di attesa
            orologio.avanza(Duration.ofSeconds(1));

            // then: si puo' ritentare. Il punto della scelta "ritardo e non blocco": chi
            // conosce la propria password ci entra comunque, deve solo aspettare.
            assertThatCode(() -> service.checkNotThrottled(EMAIL, IP)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("crescita del ritardo")
    class CrescitaDelRitardo {

        @Test
        @DisplayName("raddoppia ad ogni fallimento successivo")
        void recordFailure_ripetuto_raddoppiaIlRitardo() {
            // given: nessun tentativo libero, cosi' il primo fallimento gia' ritarda
            LoginAttemptService service = serviceCon(0, 100);

            // when/then: 1s dopo il primo fallimento...
            service.recordFailure(EMAIL, IP);
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP)).hasMessageContaining("1 secondo");

            // ...2s dopo il secondo...
            orologio.avanza(Duration.ofSeconds(1));
            service.recordFailure(EMAIL, IP);
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP)).hasMessageContaining("2 secondi");

            // ...4s dopo il terzo. E' il raddoppio che rende impraticabile il brute force:
            // il costo di ogni tentativo cresce, quello di un errore onesto no.
            orologio.avanza(Duration.ofSeconds(2));
            service.recordFailure(EMAIL, IP);
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP)).hasMessageContaining("4 secondi");
        }

        @Test
        @DisplayName("non supera mai il tetto configurato")
        void recordFailure_moltiTentativi_siFermaAlTetto() {
            // given: venti fallimenti di fila, molti piu' di quanti servano a superare gli 8s
            LoginAttemptService service = serviceCon(0, 100);
            for (int i = 0; i < 20; i++) {
                service.recordFailure(EMAIL, IP);
            }

            // when/then: l'attesa resta quella massima e non diventa di ore. Senza il tetto
            // il raddoppio si trasformerebbe in un blocco definitivo, cioe' proprio la cosa
            // che questa protezione vuole evitare.
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP))
                    .hasMessageContaining("8 secondi");
        }
    }

    @Nested
    @DisplayName("azzeramento del conteggio")
    class Azzeramento {

        @Test
        @DisplayName("un accesso riuscito azzera il ritardo dell'email")
        void recordSuccess_azzeraIlContatoreDellEmail() {
            // given: un'attesa in corso sull'email
            LoginAttemptService service = serviceCon(0, 100);
            service.recordFailure(EMAIL, IP);

            // when: l'utente indovina la password
            service.recordSuccess(EMAIL, IP);

            // then: riparte pulito, senza trascinarsi dietro i tentativi sbagliati di prima
            assertThatCode(() -> service.checkNotThrottled(EMAIL, IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("un accesso riuscito NON azzera il ritardo dell'indirizzo IP")
        void recordSuccess_nonAzzeraIlContatoreDellIp() {
            // given: un IP che ha superato la propria soglia provando altre email
            LoginAttemptService service = serviceCon(100, 0);
            service.recordFailure("vittima@example.com", IP);

            // when: dallo stesso IP si fa un login riuscito con un account proprio
            service.recordSuccess(EMAIL, IP);

            // then: l'IP resta rallentato. Se cosi' non fosse, a chi attacca basterebbe un
            // account valido qualsiasi per ripulirsi il limite ogni pochi tentativi e
            // continuare indisturbato: e' la scorciatoia che questo test chiude.
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP))
                    .isInstanceOf(TooManyRequestsException.class);
        }

        @Test
        @DisplayName("passata la finestra di inattivita' il conteggio riparte da zero")
        void checkNotThrottled_dopoLaFinestra_dimenticaITentativi() {
            // given: parecchi fallimenti, quindi un'attesa lunga in corso
            LoginAttemptService service = serviceCon(0, 100);
            for (int i = 0; i < 10; i++) {
                service.recordFailure(EMAIL, IP);
            }

            // when: passa la finestra senza altri tentativi
            orologio.avanza(FINESTRA.plusSeconds(1));

            // then: si riparte come se non fosse successo niente — chi ha sbagliato la
            // password stamattina non deve pagarla nel pomeriggio
            assertThatCode(() -> service.checkNotThrottled(EMAIL, IP)).doesNotThrowAnyException();

            // e il conteggio riparte davvero da uno, invece di riprendere da dove era
            // rimasto: il fallimento successivo costa di nuovo un solo secondo
            service.recordFailure(EMAIL, IP);
            assertThatThrownBy(() -> service.checkNotThrottled(EMAIL, IP)).hasMessageContaining("1 secondo");
        }
    }

    @Nested
    @DisplayName("separazione delle chiavi")
    class SeparazioneDelleChiavi {

        @Test
        @DisplayName("il ritardo su un'email non tocca le altre")
        void checkNotThrottled_altraEmail_nonEInfluenzata() {
            // given: un'email rallentata
            LoginAttemptService service = serviceCon(0, 100);
            service.recordFailure(EMAIL, IP);

            // when/then: un altro account dallo stesso IP entra senza attese, finche' l'IP
            // resta sotto la propria soglia
            assertThatCode(() -> service.checkNotThrottled("altra@example.com", IP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("il ritardo su un IP non tocca gli altri")
        void checkNotThrottled_altroIp_nonEInfluenzato() {
            // given: un IP rallentato
            LoginAttemptService service = serviceCon(100, 0);
            service.recordFailure(EMAIL, IP);

            // when/then: chi arriva da un altro indirizzo non paga per lui
            assertThatCode(() -> service.checkNotThrottled(EMAIL, ALTRO_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("l'email conta a prescindere da maiuscole e spazi")
        void recordFailure_emailScrittaDiverso_stessoContatore() {
            // given: fallimenti registrati sull'email scritta in minuscolo
            LoginAttemptService service = serviceCon(0, 100);
            service.recordFailure(EMAIL, IP);

            // when/then: la stessa email con maiuscole e spazi deve finire nello stesso
            // contatore. Senza normalizzazione basterebbe alternare le maiuscole ad ogni
            // tentativo per non farsi rallentare mai.
            assertThatThrownBy(() -> service.checkNotThrottled("  Mario.Rossi@Example.COM  ", ALTRO_IP))
                    .isInstanceOf(TooManyRequestsException.class);
        }

        @Test
        @DisplayName("con email o IP assenti non solleva eccezioni impreviste")
        void checkNotThrottled_conChiaviNulle_nonEsplode() {
            // given: un service qualsiasi
            LoginAttemptService service = serviceCon(0, 0);

            // when/then: email vuota e IP nullo capitano (body malformato, chiamate non
            // HTTP): devono essere ignorati come chiave, non diventare un 500
            assertThatCode(() -> {
                service.recordFailure(null, null);
                service.recordFailure("", null);
                service.checkNotThrottled(null, null);
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("pulizia periodica")
    class PuliziaPeriodica {

        @Test
        @DisplayName("dimentica le chiavi con la finestra scaduta e conserva le altre")
        void rimuoviScaduti_tieneSoloIContatoriAncoraValidi() {
            // given: un'email che ha fallito molto tempo fa e una che ha appena fallito
            LoginAttemptServiceImpl service = serviceCon(0, 100);
            service.recordFailure("vecchia@example.com", IP);
            orologio.avanza(FINESTRA.plusSeconds(1));
            service.recordFailure("recente@example.com", ALTRO_IP);

            // when: gira la pulizia periodica
            service.rimuoviScaduti();

            // then: la voce scaduta e' sparita (senza pulizia la memoria conserverebbe per
            // sempre ogni email mai sbagliata da chiunque), quella valida e' rimasta
            assertThatCode(() -> service.checkNotThrottled("vecchia@example.com", IP))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> service.checkNotThrottled("recente@example.com", ALTRO_IP))
                    .isInstanceOf(TooManyRequestsException.class);
        }
    }

    /**
     * Orologio che avanza solo quando glielo si dice: permette di verificare
     * attese e scadenze senza che la suite debba davvero aspettarle.
     */
    private static final class OrologioDiTest extends Clock {

        private Instant adesso;

        private OrologioDiTest(Instant partenza) {
            this.adesso = partenza;
        }

        void avanza(Duration quanto) {
            adesso = adesso.plus(quanto);
        }

        @Override
        public Instant instant() {
            return adesso;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
