package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.EmailProperties;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.TipoTokenEmail;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.MessaggioEmail;
import com.felixhotel.backend.service.ServizioEmail;
import com.felixhotel.backend.service.ServizioNotifiche;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * Compone le quattro email del progetto e le manda.
 *
 * <p><b>Esiste per tenere i Service di dominio all'oscuro di tre cose</b>: che esiste un
 * token, che esiste un testo, e che esiste un modo di spedire. {@code AuthServiceImpl}
 * chiama <i>"manda la verifica a questo utente"</i> e non sa altro — se domani la
 * verifica passasse per un SMS, cambierebbe questa classe e nessun'altra.
 *
 * <p><b>I testi stanno qui e non in file di template</b>, ed e' la stessa scelta gia'
 * fatta per il testo semplice invece dell'HTML: quattro messaggi di tre righe l'uno non
 * giustificano un motore di template, cioe' una dipendenza in piu' e un secondo posto in
 * cui il contenuto puo' rompersi senza che il compilatore se ne accorga. Il giorno in cui
 * i messaggi diventano dieci, o devono esistere in due lingue, e' una decisione sua.
 *
 * <p><b>Nessuno di questi metodi puo' far fallire chi lo chiama</b>: l'invio non solleva
 * (vedi {@link ServizioEmail}) e l'emissione del token vive nella stessa transazione di
 * chi ha chiamato, quindi se quella fallisce non resta ne' il token ne' l'email — che e'
 * esattamente il comportamento voluto.
 */
@Service
@RequiredArgsConstructor
public class ServizioNotificheImpl implements ServizioNotifiche {

    private static final DateTimeFormatter GIORNO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ServizioTokenEmail servizioToken;
    private final ServizioEmail servizioEmail;
    private final EmailProperties proprieta;

    @Override
    public void verificaIndirizzo(Utente utente) {
        String token = servizioToken.emetti(
                TipoTokenEmail.VERIFICA_EMAIL, TipoAccount.CLIENTE, utente.getId());

        servizioEmail.invia(new MessaggioEmail(
                utente.getEmail(),
                "Conferma il tuo indirizzo email",
                "Ciao " + utente.getNome() + ",\n\n"
                        + "per completare la registrazione conferma il tuo indirizzo email:\n"
                        + link("/verifica-email", token) + "\n\n"
                        + "Il link vale 24 ore. Finche' non lo apri non potrai accedere.\n\n"
                        + "Se non ti sei registrato tu, ignora questo messaggio: "
                        + "senza la conferma quell'account non si attiva."));
    }

    @Override
    public void invitoPersonale(Staff staff) {
        String token = servizioToken.emetti(
                TipoTokenEmail.INVITO_STAFF, TipoAccount.PERSONALE, staff.getId());

        servizioEmail.invia(new MessaggioEmail(
                staff.getEmail(),
                "Il tuo accesso a Felix Hotel",
                "Ciao " + staff.getNome() + ",\n\n"
                        + "e' stato creato un account per te. Scegli la tua password qui:\n"
                        + link("/attiva-account", token) + "\n\n"
                        + "Il link vale 7 giorni. Se scade, chiedi a un amministratore "
                        + "di rimandartelo.\n\n"
                        + "La password scegliela tu e non dirla a nessuno: "
                        + "nessuno qui dentro ha bisogno di conoscerla."));
    }

    @Override
    public void resetPassword(String email, String nome, TipoAccount tipoAccount, Long soggettoId) {
        String token = servizioToken.emetti(TipoTokenEmail.RESET_PASSWORD, tipoAccount, soggettoId);

        servizioEmail.invia(new MessaggioEmail(
                email,
                "Reimposta la tua password",
                "Ciao " + nome + ",\n\n"
                        + "hai chiesto di reimpostare la password. Falla qui:\n"
                        + link("/reimposta-password", token) + "\n\n"
                        + "Il link vale un'ora.\n\n"
                        + "Se non sei stato tu, non devi fare niente: "
                        + "la tua password attuale resta valida."));
    }

    /**
     * La conferma di prenotazione.
     *
     * <p><b>L'unica delle quattro che non porta un token</b>, ed e' il motivo per cui il
     * suo fallimento pesa di piu': le altre tre si possono farsi rimandare, questa no —
     * se non arriva, il cliente resta senza la sua conferma e non ha nessun modo di
     * chiederne un'altra. E' scritto fra i gap.
     *
     * <p>Legge {@code tipologiaCamera} e {@code utente}, che sono relazioni LAZY: va
     * chiamata dentro la transazione di chi conferma, come tutti i mapper del progetto.
     */
    @Override
    public void confermaPrenotazione(Prenotazione prenotazione) {
        Utente cliente = prenotazione.getUtente();

        servizioEmail.invia(new MessaggioEmail(
                cliente.getEmail(),
                "Prenotazione confermata - " + prenotazione.getTipologiaCamera().getNome(),
                "Ciao " + cliente.getNome() + ",\n\n"
                        + "la tua prenotazione e' confermata.\n\n"
                        + "Camera:   " + prenotazione.getTipologiaCamera().getNome() + "\n"
                        + "Arrivo:   " + prenotazione.getDataCheckIn().format(GIORNO) + "\n"
                        + "Partenza: " + prenotazione.getDataCheckOut().format(GIORNO) + "\n"
                        + "Ospiti:   " + prenotazione.getNumeroOspiti() + "\n"
                        + "Totale:   " + prenotazione.getImportoTotale() + " EUR\n\n"
                        + "Il totale non comprende la tassa di soggiorno, "
                        + "che si paga in struttura.\n\n"
                        + "A presto."));
    }

    /**
     * Il link da mettere nel messaggio.
     *
     * <p><b>Il token si codifica</b> anche se oggi non ne avrebbe bisogno: e' Base64
     * URL-safe, quindi non contiene caratteri da sfuggire. Sta qui lo stesso perche' il
     * giorno in cui l'alfabeto del token cambiasse, questa riga e' l'unica cosa che
     * separa un link buono da uno rotto — e non e' il tipo di cosa che qualcuno ricontrolla.
     */
    private String link(String percorso, String token) {
        return proprieta.baseUrl() + percorso + "?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}
