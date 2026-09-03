package com.felixhotel.backend.security;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Da quale istante far cadere i token gia' emessi, quando una password cambia.
 *
 * <p><b>E' una riga sola, e vale la pena che abbia un nome</b>: e' l'unico posto dove si
 * decide cosa significhi "da adesso" per una revoca, e i posti che la scrivono sono
 * cinque. Il giorno che quel significato cambiasse — un margine di sicurezza all'indietro,
 * un fuso diverso — cambierebbe qui e non in cinque diff.
 *
 * <p><b>Nessun arrotondamento, e la prima stesura ne aveva uno.</b> Prendeva l'inizio del
 * secondo successivo, per via della precisione al secondo dello {@code iat} di un JWT: e
 * cosi' faceva cadere anche il token di chi, subito dopo aver reimpostato la password,
 * accedeva nello stesso secondo — cioe' il caso normale, quello che l'ultima riga del
 * messaggio di risposta invita a fare (<i>"ora puoi accedere"</i>). Il rimedio non era
 * arrotondare meglio ma togliere il problema: {@link JwtService} scrive nel token
 * l'emissione in <b>millisecondi</b>, perche' quei token li emettiamo noi e la precisione
 * ce la scegliamo. Qui si scrive quindi l'istante vero.
 *
 * <p><b>L'istante si prende dallo stesso orologio che datta i token</b>, ed e' la seconda
 * correzione di questa classe — trovata da un test che falliva in modo intermittente.
 * {@code LocalDateTime.now()} legge un orologio ad alta risoluzione, mentre un JWT viene
 * dattato con {@code System.currentTimeMillis()}, che su Windows avanza <b>a scatti di
 * una quindicina di millisecondi</b>. Con due sorgenti diverse, un token emesso <i>dopo</i>
 * la revoca poteva risultare emesso prima, perche' il suo istante era fermo all'ultimo
 * scatto: il token nuovo cadeva, quello che si voleva revocare pure, e il perche' non si
 * vedeva da nessuna parte. Leggendo {@link Clock#millis()} le due meta' guardano lo stesso
 * orologio e la stessa risoluzione.
 *
 * <p><b>Un pareggio esatto lascia il token valido</b> ({@code isBefore} e' stretto), ed e'
 * il verso giusto: a pareggiare puo' essere solo un token emesso nello stesso scatto della
 * revoca — cioe' quello nuovo, perche' quello vecchio e' di molti scatti prima.
 */
public final class IstanteRevoca {

    private IstanteRevoca() {
        // Solo metodi statici: e' una regola, non un oggetto.
    }

    /** L'istante da scrivere adesso. */
    public static LocalDateTime adesso() {
        return adesso(Clock.systemDefaultZone());
    }

    /** Versione con l'orologio esplicito, per i test. */
    public static LocalDateTime adesso(Clock clock) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), clock.getZone());
    }
}
