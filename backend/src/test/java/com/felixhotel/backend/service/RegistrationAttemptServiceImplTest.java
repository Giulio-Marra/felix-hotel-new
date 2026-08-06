package com.felixhotel.backend.service;

import com.felixhotel.backend.config.RegistrationRateLimitProperties;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.service.impl.RegistrationAttemptServiceImpl;
import com.felixhotel.backend.support.OrologioDiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unitari del limite di frequenza sulle registrazioni.
 *
 * <p>Il meccanismo del ritardo (soglia, raddoppio, tetto, finestra) e' lo stesso
 * del login ed e' gia' coperto da {@link LoginAttemptServiceImplTest}: qui si
 * verifica cio' che questo service decide di suo — una sola chiave, l'indirizzo
 * IP, e un messaggio che parla di registrazioni e non di credenziali sbagliate.
 *
 * <p>Che ogni tentativo venga contato a prescindere dall'esito e' invece una
 * decisione di chi chiama, quindi si verifica in {@code AuthServiceImplTest}:
 * questo service riceve solo l'ordine di contare, non sa com'e' andata.
 */
@DisplayName("RegistrationAttemptServiceImpl")
class RegistrationAttemptServiceImplTest {

    private static final String IP = "203.0.113.7";
    private static final String ALTRO_IP = "203.0.113.8";

    /**
     * Valori scelti per poter contare i raddoppi a mente e per attraversare il
     * confine del minuto: 30s, poi 1 minuto, poi 2, con il tetto a 4.
     */
    private static final Duration RITARDO_INIZIALE = Duration.ofSeconds(30);
    private static final Duration RITARDO_MASSIMO = Duration.ofMinutes(4);
    private static final Duration FINESTRA = Duration.ofHours(1);

    private OrologioDiTest orologio;

    @BeforeEach
    void inizializza() {
        orologio = new OrologioDiTest(Instant.parse("2026-08-06T10:00:00Z"));
    }

    /**
     * Service con la soglia indicata. Ogni test se lo costruisce con i numeri che
     * gli servono, cosi' i contatori partono sempre puliti.
     */
    private RegistrationAttemptServiceImpl serviceCon(int tentativiLiberiIp) {
        return new RegistrationAttemptServiceImpl(
                new RegistrationRateLimitProperties(tentativiLiberiIp, RITARDO_INIZIALE, RITARDO_MASSIMO, FINESTRA),
                orologio);
    }

    @Nested
    @DisplayName("tentativi liberi")
    class TentativiLiberi {

        @Test
        @DisplayName("entro la soglia non impone nessuna attesa")
        void checkNotThrottled_sottoLaSoglia_nonRitarda() {
            // given: tre registrazioni libere, e tre gia' fatte
            RegistrationAttemptService service = serviceCon(3);
            for (int i = 0; i < 3; i++) {
                service.recordAttempt(IP);
            }

            // when/then: si puo' ancora registrare subito. E' il caso della famiglia che
            // crea qualche account dalla stessa connessione: non deve accorgersi che
            // questa protezione esiste.
            assertThatCode(() -> service.checkNotThrottled(IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("oltre la soglia rifiuta con 429 e dice fra quanto riprovare")
        void checkNotThrottled_oltreLaSoglia_sollevaTooManyRequests() {
            // given: tre registrazioni libere consumate, piu' una
            RegistrationAttemptService service = serviceCon(3);
            for (int i = 0; i < 4; i++) {
                service.recordAttempt(IP);
            }

            // when/then: 429, con l'attesa residua nel messaggio perche' il client sappia
            // quando riprovare (la busta non ha un campo dedicato, vedi regola 10)
            assertThatThrownBy(() -> service.checkNotThrottled(IP))
                    .isInstanceOf(TooManyRequestsException.class)
                    .hasMessageContaining("30 secondi")
                    .extracting(ex -> ((TooManyRequestsException) ex).getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("il messaggio parla di registrazioni, non di credenziali sbagliate")
        void checkNotThrottled_oltreLaSoglia_spiegaIlMotivoGiusto() {
            // given: un indirizzo che ha superato la soglia
            RegistrationAttemptService service = serviceCon(0);
            service.recordAttempt(IP);

            // when/then: qui non c'e' nessun tentativo "fallito" — la registrazione di
            // troppo e' quella riuscita. Un messaggio copiato da quello del login direbbe
            // a chi lo legge una cosa che non e' successa.
            assertThatThrownBy(() -> service.checkNotThrottled(IP))
                    .hasMessageContaining("registrazioni")
                    .hasMessageNotContaining("falliti");
        }

        @Test
        @DisplayName("passata l'attesa lascia ritentare")
        void checkNotThrottled_dopoLAttesa_lasciaPassare() {
            // given: un'attesa di trenta secondi in corso
            RegistrationAttemptService service = serviceCon(0);
            service.recordAttempt(IP);

            // when: passa l'attesa
            orologio.avanza(RITARDO_INIZIALE);

            // then: si puo' registrare di nuovo. E' la scelta "ritardo e non blocco": chi
            // ha davvero bisogno di un altro account lo ottiene, deve solo aspettare.
            assertThatCode(() -> service.checkNotThrottled(IP)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("crescita del ritardo")
    class CrescitaDelRitardo {

        @Test
        @DisplayName("raddoppia ad ogni tentativo, e oltre il minuto lo dice in minuti")
        void recordAttempt_ripetuto_raddoppiaEDescriveLAttesa() {
            // given: nessun tentativo libero, cosi' il primo gia' ritarda
            RegistrationAttemptService service = serviceCon(0);

            // when/then: 30 secondi dopo il primo...
            service.recordAttempt(IP);
            assertThatThrownBy(() -> service.checkNotThrottled(IP)).hasMessageContaining("30 secondi");

            // ...un minuto dopo il secondo, annunciato in minuti e non in "60 secondi":
            // e' la stessa attesa detta come la direbbe una persona
            orologio.avanza(Duration.ofSeconds(30));
            service.recordAttempt(IP);
            assertThatThrownBy(() -> service.checkNotThrottled(IP)).hasMessageContaining("1 minuto");

            // ...due minuti dopo il terzo
            orologio.avanza(Duration.ofMinutes(1));
            service.recordAttempt(IP);
            assertThatThrownBy(() -> service.checkNotThrottled(IP)).hasMessageContaining("2 minuti");
        }

        @Test
        @DisplayName("non supera mai il tetto configurato")
        void recordAttempt_moltiTentativi_siFermaAlTetto() {
            // given: venti tentativi di fila, molti piu' di quanti servano a superare i 4 minuti
            RegistrationAttemptService service = serviceCon(0);
            for (int i = 0; i < 20; i++) {
                service.recordAttempt(IP);
            }

            // when/then: l'attesa resta quella massima e non diventa di giorni. Senza il
            // tetto il raddoppio si trasformerebbe in un blocco permanente dell'indirizzo,
            // che dietro un NAT vuol dire di tutti quelli che ci stanno dietro.
            assertThatThrownBy(() -> service.checkNotThrottled(IP)).hasMessageContaining("4 minuti");
        }
    }

    @Nested
    @DisplayName("separazione e azzeramento")
    class SeparazioneEAzzeramento {

        @Test
        @DisplayName("il ritardo su un indirizzo non tocca gli altri")
        void checkNotThrottled_altroIp_nonEInfluenzato() {
            // given: un indirizzo rallentato
            RegistrationAttemptService service = serviceCon(0);
            service.recordAttempt(IP);

            // when/then: chi arriva da un altro indirizzo non paga per lui
            assertThatCode(() -> service.checkNotThrottled(ALTRO_IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passata la finestra di inattivita' il conteggio riparte da zero")
        void checkNotThrottled_dopoLaFinestra_dimenticaITentativi() {
            // given: parecchi tentativi, quindi un'attesa lunga in corso
            RegistrationAttemptService service = serviceCon(0);
            for (int i = 0; i < 10; i++) {
                service.recordAttempt(IP);
            }

            // when: passa la finestra senza altri tentativi
            orologio.avanza(FINESTRA.plusSeconds(1));

            // then: si riparte come se non fosse successo niente, e davvero da uno: il
            // tentativo successivo costa di nuovo il solo ritardo iniziale
            assertThatCode(() -> service.checkNotThrottled(IP)).doesNotThrowAnyException();
            service.recordAttempt(IP);
            assertThatThrownBy(() -> service.checkNotThrottled(IP)).hasMessageContaining("30 secondi");
        }

        @Test
        @DisplayName("senza indirizzo IP non solleva eccezioni impreviste")
        void checkNotThrottled_conIpNullo_nonEsplode() {
            // given: un service qualsiasi
            RegistrationAttemptService service = serviceCon(0);

            // when/then: un IP nullo puo' capitare (chiamate non HTTP, container esotici):
            // deve essere ignorato come chiave, non diventare un 500 al posto di un 201
            assertThatCode(() -> {
                service.recordAttempt(null);
                service.checkNotThrottled(null);
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("pulizia periodica")
    class PuliziaPeriodica {

        @Test
        @DisplayName("dimentica gli indirizzi con la finestra scaduta e conserva gli altri")
        void rimuoviScaduti_tieneSoloIContatoriAncoraValidi() {
            // given: un indirizzo che ha registrato molto tempo fa e uno che ha appena finito
            RegistrationAttemptServiceImpl service = serviceCon(0);
            service.recordAttempt(IP);
            orologio.avanza(FINESTRA.plusSeconds(1));
            service.recordAttempt(ALTRO_IP);

            // when: gira la pulizia periodica
            service.rimuoviScaduti();

            // then: la voce scaduta e' sparita (senza pulizia la memoria conserverebbe per
            // sempre ogni indirizzo che si sia mai registrato), quella valida e' rimasta
            assertThatCode(() -> service.checkNotThrottled(IP)).doesNotThrowAnyException();
            assertThatThrownBy(() -> service.checkNotThrottled(ALTRO_IP))
                    .isInstanceOf(TooManyRequestsException.class);
        }
    }
}
