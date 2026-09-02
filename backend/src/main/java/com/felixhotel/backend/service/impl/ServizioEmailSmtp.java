package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.EmailProperties;
import com.felixhotel.backend.service.MessaggioEmail;
import com.felixhotel.backend.service.ServizioEmail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L'invio vero, via SMTP.
 *
 * <p><b>SMTP e non l'API HTTP del fornitore</b>, deciso il 2026-08-28 prima di scrivere
 * una riga: Resend espone tutte e due, ma con l'API HTTP servirebbe un adattatore per
 * la produzione e uno diverso per lo sviluppo, perche' Mailpit parla SMTP e un'API HTTP
 * non la intercetterebbe. Cosi' il codice e' uno solo — questa classe — e fra sviluppo
 * e produzione cambia <b>solo la configurazione</b>. E' lo stesso principio gia'
 * applicato al CORS, dove il profilo {@code dev} cambia i valori e non le classi.
 *
 * <p><b>Due comportamenti che chi legge deve conoscere, perche' non si vedono dalla
 * firma del metodo.</b>
 *
 * <p>Il primo: <b>si spedisce dopo il commit</b>. Se c'e' una transazione aperta,
 * l'invio si registra e parte quando quella transazione e' andata a buon fine. Senza,
 * un rollback dopo l'invio lascerebbe nella casella di qualcuno il link a un token che
 * nel database non esiste — e chi ci clicca vedrebbe "link non valido" senza che
 * nessuno sappia piu' spiegare perche'. Sta qui e non nei Service di dominio proprio
 * perche' e' il tipo di accortezza che uno dei chiamanti prima o poi dimentica.
 *
 * <p>Il secondo: <b>non fallisce mai</b>. Un guasto dell'SMTP finisce nel log e si
 * ferma li'. Il contrario vorrebbe dire che il fornitore di posta puo' impedire di
 * confermare una prenotazione — vedi {@link ServizioEmail} per il perche' esteso, e i
 * gap per cosa questo costa.
 *
 * <p><b>Nel log non finisce ne' il corpo ne' l'oggetto</b>, e l'indirizzo c'e' perche'
 * senza il messaggio non servirebbe a niente: sapere che "un'email non e' partita" non
 * si puo' usare. E' la stessa riga di confine gia' tracciata altrove nel progetto — i
 * dati personali non escono dalle risposte d'errore, ma un log di sistema che serve a
 * riparare un guasto puo' dire a chi non e' arrivata la posta.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServizioEmailSmtp implements ServizioEmail {

    private final JavaMailSender mailSender;
    private final EmailProperties proprieta;

    @Override
    public void invia(MessaggioEmail messaggio) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // C'e' una transazione in corso: l'invio si mette in coda al suo commit. Se
            // la transazione fallisce, questo callback non viene mai chiamato — che e'
            // esattamente quel che serve.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    spedisci(messaggio);
                }
            });
            return;
        }
        // Nessuna transazione aperta: non c'e' niente da aspettare.
        spedisci(messaggio);
    }

    /**
     * Il tentativo vero.
     *
     * <p>Il {@code catch} e' su {@link MailException} e non su {@code Exception}: le
     * altre eccezioni qui sarebbero difetti nostri — un messaggio senza destinatario,
     * una configurazione assente — e ingoiarle vorrebbe dire non accorgersene mai. Un
     * SMTP irraggiungibile invece e' il mondo che si comporta come puo'.
     */
    private void spedisci(MessaggioEmail messaggio) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(proprieta.mittente());
        mail.setTo(messaggio.destinatario());
        mail.setSubject(messaggio.oggetto());
        mail.setText(messaggio.corpo());

        try {
            mailSender.send(mail);
        } catch (MailException ex) {
            log.error("Email non spedita a {}: l'operazione che l'ha richiesta e' comunque riuscita",
                    messaggio.destinatario(), ex);
        }
    }
}
