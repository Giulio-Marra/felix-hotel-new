package com.felixhotel.backend.service;

import com.felixhotel.backend.config.EmailProperties;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.entity.enums.TipoTokenEmail;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.ServizioNotificheImpl;
import com.felixhotel.backend.service.impl.ServizioTokenEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I testi delle quattro email, e a chi vanno.
 *
 * <p><b>Perche' vale la pena provarli.</b> Verrebbe da pensare che il contenuto di
 * un'email sia troppo banale per un test — ed e' proprio il tipo di cosa che si rompe in
 * silenzio: un link costruito male manda tutti su una pagina che non esiste, un
 * destinatario preso dal campo sbagliato spedisce i dati di una persona a un'altra, e
 * nessuno se ne accorge finche' non lo dice un cliente. Gli IT verificano che il
 * messaggio <i>parta</i>; questa classe e' l'unica che guardi cosa c'e' dentro.
 *
 * <p><b>I testi non si ricopiano parola per parola</b>, e non sarebbe utile: un test che
 * confronta una frase con la stessa frase si limita a impedire di correggere un refuso.
 * Si guardano le tre cose che possono davvero essere sbagliate — il destinatario, il
 * fatto che il link porti il token appena emesso, e che il tipo di token sia quello
 * giusto per l'occasione.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServizioNotificheImpl")
class ServizioNotificheImplTest {

    private static final String BASE_URL = "https://felix.example.it";
    private static final String TOKEN = "un-token-qualunque";

    @Mock
    private ServizioTokenEmail servizioToken;
    @Mock
    private ServizioEmail servizioEmail;

    private ServizioNotificheImpl notifiche;

    @BeforeEach
    void inizializza() {
        notifiche = new ServizioNotificheImpl(servizioToken, servizioEmail,
                new EmailProperties("felix@example.it", BASE_URL));
    }

    @Test
    @DisplayName("la verifica va al cliente, col suo token e il link giusto")
    void verificaIndirizzo_componeIlMessaggio() {
        // given
        when(servizioToken.emetti(eq(TipoTokenEmail.VERIFICA_EMAIL), eq(TipoAccount.CLIENTE), any()))
                .thenReturn(TOKEN);

        // when
        notifiche.verificaIndirizzo(cliente());

        // then
        MessaggioEmail messaggio = catturato();
        assertThat(messaggio.destinatario()).isEqualTo("mario.rossi@example.com");
        assertThat(messaggio.corpo())
                .contains("Mario")
                .contains(BASE_URL + "/verifica-email?token=" + TOKEN);
    }

    @Test
    @DisplayName("l'invito va al membro del personale, con un token del tipo giusto")
    void invitoPersonale_componeIlMessaggio() {
        // given
        when(servizioToken.emetti(eq(TipoTokenEmail.INVITO_STAFF), eq(TipoAccount.PERSONALE), any()))
                .thenReturn(TOKEN);

        // when
        notifiche.invitoPersonale(staff());

        // then: il tipo del token e' meta' del test. Un invito emesso come token di
        // verifica non aprirebbe la rotta di attivazione, e l'errore si vedrebbe solo
        // quando la persona clicca — cioe' il primo giorno di lavoro
        MessaggioEmail messaggio = catturato();
        assertThat(messaggio.destinatario()).isEqualTo("anna.bianchi@felixhotel.it");
        assertThat(messaggio.corpo()).contains(BASE_URL + "/attiva-account?token=" + TOKEN);
    }

    @Test
    @DisplayName("il reset usa il tipo di account che gli viene detto")
    void resetPassword_perIlPersonale_emetteIlTokenGiusto() {
        // given: il reset e' l'unica delle quattro che vale per tutte e due le
        // popolazioni, e sbagliare tabella qui vorrebbe dire mandare a una persona il
        // link che cambia la password di un'altra con lo stesso id
        when(servizioToken.emetti(eq(TipoTokenEmail.RESET_PASSWORD), eq(TipoAccount.PERSONALE), eq(9L)))
                .thenReturn(TOKEN);

        // when
        notifiche.resetPassword("anna.bianchi@felixhotel.it", "Anna", TipoAccount.PERSONALE, 9L);

        // then
        MessaggioEmail messaggio = catturato();
        assertThat(messaggio.destinatario()).isEqualTo("anna.bianchi@felixhotel.it");
        assertThat(messaggio.corpo()).contains(BASE_URL + "/reimposta-password?token=" + TOKEN);
    }

    @Test
    @DisplayName("la conferma di prenotazione non porta nessun token e riporta il soggiorno")
    void confermaPrenotazione_componeIlMessaggio() {
        // when
        notifiche.confermaPrenotazione(prenotazione());

        // then: e' l'unica delle quattro senza token, ed e' anche l'unica che contenga
        // dei numeri — quindi l'unica in cui un campo preso storto si vede
        MessaggioEmail messaggio = catturato();
        assertThat(messaggio.destinatario()).isEqualTo("mario.rossi@example.com");
        assertThat(messaggio.oggetto()).contains("Camera Doppia");
        assertThat(messaggio.corpo())
                .contains("02/09/2026")
                .contains("05/09/2026")
                .contains("240.00")
                .doesNotContain("token=");
    }

    private MessaggioEmail catturato() {
        ArgumentCaptor<MessaggioEmail> messaggio = ArgumentCaptor.forClass(MessaggioEmail.class);
        verify(servizioEmail).invia(messaggio.capture());
        return messaggio.getValue();
    }

    private Utente cliente() {
        Utente utente = new Utente();
        utente.setId(1L);
        utente.setNome("Mario");
        utente.setCognome("Rossi");
        utente.setEmail("mario.rossi@example.com");
        return utente;
    }

    private Staff staff() {
        Staff staff = new Staff();
        staff.setId(9L);
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        staff.setEmail("anna.bianchi@felixhotel.it");
        return staff;
    }

    private Prenotazione prenotazione() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setNome("Camera Doppia");

        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(3L);
        prenotazione.setUtente(cliente());
        prenotazione.setTipologiaCamera(tipologia);
        prenotazione.setDataCheckIn(LocalDate.of(2026, 9, 2));
        prenotazione.setDataCheckOut(LocalDate.of(2026, 9, 5));
        prenotazione.setNumeroOspiti(2);
        prenotazione.setImportoTotale(new BigDecimal("240.00"));
        return prenotazione;
    }
}
