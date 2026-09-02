package com.felixhotel.backend.support;

import com.felixhotel.backend.service.MessaggioEmail;
import com.felixhotel.backend.service.ServizioEmail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La casella di posta dei test: raccoglie i messaggi invece di spedirli.
 *
 * <p><b>Perche' non un container SMTP</b>, deciso il 2026-08-28: il progetto usa
 * Testcontainers per Postgres perche' li' lo schema e' di Flyway e Hibernate lo valida,
 * cioe' il container prova qualcosa di nostro. Un container SMTP proverebbe che
 * {@code JavaMailSender} sa parlare SMTP, che e' codice di Spring — e in cambio
 * renderebbe la suite piu' lenta e dipendente da un'altra immagine.
 *
 * <p><b>Cosa prova invece questa classe</b>: che l'applicazione <i>abbia provato</i> a
 * mandare il messaggio, a chi, e con che contenuto. E' l'unica cosa che valga la pena
 * verificare, ed e' anche quella che si romperebbe davvero — un link costruito male, un
 * destinatario sbagliato, un'email che non parte affatto.
 *
 * <p><b>E' condivisa da tutti gli IT</b>, che girano in sequenza sullo stesso contesto:
 * per questo {@link #svuota()} viene chiamato prima di ogni test da
 * {@code IntegrationTestBase}. Senza, un test leggerebbe il messaggio del precedente.
 */
public class PostaDiProva implements ServizioEmail {

    /**
     * Il token dentro un link. Cattura tutto quel che segue {@code ?token=} fino alla
     * fine della riga: i token sono Base64 URL-safe, quindi non contengono ne' spazi ne'
     * caratteri che vadano sfuggiti.
     */
    private static final Pattern TOKEN = Pattern.compile("[?&]token=(\\S+)");

    private final List<MessaggioEmail> messaggi = new ArrayList<>();

    @Override
    public synchronized void invia(MessaggioEmail messaggio) {
        messaggi.add(messaggio);
    }

    /** Tutti i messaggi raccolti, dal primo all'ultimo. */
    public synchronized List<MessaggioEmail> messaggi() {
        return List.copyOf(messaggi);
    }

    /** L'ultimo messaggio mandato a questo indirizzo, se ce n'e' uno. */
    public synchronized Optional<MessaggioEmail> ultimoPer(String destinatario) {
        return messaggi.stream()
                .filter(m -> m.destinatario().equalsIgnoreCase(destinatario))
                .reduce((primo, secondo) -> secondo);
    }

    /**
     * Il token dell'ultimo link mandato a questo indirizzo.
     *
     * <p>E' il metodo per cui questa classe esiste: senza, un test che voglia provare la
     * conferma di un indirizzo dovrebbe andare a leggere la tabella dei token — che
     * contiene solo l'impronta, quindi non servirebbe — oppure saltare del tutto il giro
     * dal bordo HTTP.
     *
     * @throws IllegalStateException se non e' arrivato niente, o se il messaggio non
     *                               contiene un link: e' un difetto del test o del
     *                               codice, non un caso da gestire
     */
    public synchronized String tokenPer(String destinatario) {
        MessaggioEmail messaggio = ultimoPer(destinatario).orElseThrow(() ->
                new IllegalStateException("Nessuna email mandata a " + destinatario));

        Matcher matcher = TOKEN.matcher(messaggio.corpo());
        if (!matcher.find()) {
            throw new IllegalStateException("L'email mandata a " + destinatario + " non contiene un link");
        }
        return matcher.group(1);
    }

    /** Svuota la casella. La chiama {@code IntegrationTestBase} prima di ogni test. */
    public synchronized void svuota() {
        messaggi.clear();
    }
}
