package com.felixhotel.backend.service;

import com.felixhotel.backend.config.EmailProperties;
import com.felixhotel.backend.service.impl.ServizioEmailSmtp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * L'adattatore SMTP, cioe' l'unico pezzo del branch che gli altri test non toccano
 * <b>di proposito</b>: negli IT il suo posto lo prende {@code PostaDiProva}, che
 * raccoglie i messaggi invece di spedirli.
 *
 * <p><b>Perche' allora un test</b>: quella sostituzione lascia scoperte le due decisioni
 * che stanno in questa classe e da nessun'altra parte — <i>si spedisce dopo il
 * commit</i> e <i>un guasto della posta non fa fallire niente</i>. Sono due
 * comportamenti che non si vedono dalla firma del metodo, e senza questi test l'unica
 * cosa che li proverebbe sarebbe la produzione.
 *
 * <p>Unitario: {@code JavaMailSender} e' un mock, e la sincronizzazione transazionale si
 * apre a mano — non serve ne' Spring ne' un database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServizioEmailSmtp")
class ServizioEmailSmtpTest {

    private static final MessaggioEmail MESSAGGIO =
            new MessaggioEmail("mario.rossi@example.com", "Oggetto", "Corpo del messaggio");

    @Mock
    private JavaMailSender mailSender;

    private ServizioEmailSmtp servizio;

    @BeforeEach
    void inizializza() {
        servizio = new ServizioEmailSmtp(mailSender,
                new EmailProperties("felix@example.com", "https://esempio.it"));
    }

    @AfterEach
    void chiudiSincronizzazione() {
        // Se un test l'ha aperta e non l'ha chiusa, resterebbe aperta anche per i
        // successivi: e' uno stato legato al thread, e i test girano sullo stesso.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("senza transazione aperta spedisce subito")
    void invia_senzaTransazione_spedisceSubito() {
        // when
        servizio.invia(MESSAGGIO);

        // then: destinatario, oggetto, corpo e mittente, che viene dalla configurazione
        ArgumentCaptor<SimpleMailMessage> spedito = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(spedito.capture());

        assertThat(spedito.getValue().getTo()).containsExactly("mario.rossi@example.com");
        assertThat(spedito.getValue().getSubject()).isEqualTo("Oggetto");
        assertThat(spedito.getValue().getText()).isEqualTo("Corpo del messaggio");
        assertThat(spedito.getValue().getFrom()).isEqualTo("felix@example.com");
    }

    @Test
    @DisplayName("con una transazione aperta non spedisce finche' non e' andata a buon fine")
    void invia_conTransazioneAperta_aspettaIlCommit() {
        // given: e' la decisione che questo test esiste per proteggere. Spedire subito
        // vorrebbe dire che un rollback lascia nella casella di qualcuno il link a un
        // token che nel database non esiste piu'
        TransactionSynchronizationManager.initSynchronization();

        // when
        servizio.invia(MESSAGGIO);

        // then: niente e' partito
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        // e quando la transazione va a buon fine, parte
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sincronizzazione -> sincronizzazione.afterCommit());
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("se la transazione fallisce non spedisce niente")
    void invia_conTransazioneFallita_nonSpedisce() {
        // given
        TransactionSynchronizationManager.initSynchronization();

        // when: l'invio si registra, ma il commit non arriva mai
        servizio.invia(MESSAGGIO);
        TransactionSynchronizationManager.clearSynchronization();

        // then: e' il rovescio del test qui sopra, ed e' il caso che conta davvero —
        // l'altro dice "prima o poi parte", questo dice "se il lavoro non e' andato a
        // buon fine non parte affatto"
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("un guasto della posta non fa fallire chi ha chiamato")
    void invia_conSmtpIrraggiungibile_nonSolleva() {
        // given: l'SMTP e' giu'
        doThrow(new MailSendException("connessione rifiutata"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        // when/then: non solleva. Il contrario vorrebbe dire che il fornitore di posta
        // puo' impedire di confermare una prenotazione o di registrarsi — il prezzo,
        // dichiarato, e' che il messaggio e' perso e se ne accorge solo chi legge i log
        assertThatCode(() -> servizio.invia(MESSAGGIO)).doesNotThrowAnyException();
    }
}
