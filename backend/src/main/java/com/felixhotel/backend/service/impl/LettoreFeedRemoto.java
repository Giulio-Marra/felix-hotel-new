package com.felixhotel.backend.service.impl;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Scarica un calendario da un indirizzo esterno.
 *
 * <p><b>Esiste come classe sua per un motivo solo, ed e' il collaudo</b>: e' l'unico
 * punto del progetto che esce sulla rete verso un indirizzo che decide qualcun altro.
 * Separandolo, la sincronizzazione si prova senza rete — le si passa un lettore che
 * restituisce un file scritto a mano — e questa classe resta piccola abbastanza da
 * guardarla tutta.
 *
 * <p><b>Client HTTP del JDK e non {@code RestClient}</b>, che pure il progetto avrebbe:
 * qui non serve niente di quel che {@code RestClient} porta — nessuna conversione JSON,
 * nessun interceptor, nessuna busta — mentre servono due cose che il JDK da' dirette, i
 * timeout e un tetto ai byte letti.
 *
 * <p><b>I tre limiti, e cosa impedisce ognuno.</b> Senza timeout di connessione un canale
 * che non risponde tiene appeso il thread del giro periodico finche' il sistema operativo
 * non si arrende; senza timeout di lettura lo stesso vale per uno che risponde a goccia;
 * senza tetto alla dimensione, un indirizzo che restituisce un file enorme — per errore o
 * per malizia — riempie la memoria del backend. Sono gli stessi tre limiti che l'SMTP ha
 * gia' in {@code application.properties}, e per la stessa ragione: un'operazione di
 * sfondo che non finisce e' peggio di una che fallisce.
 */
@Component
public class LettoreFeedRemoto {

    /** Quanto si aspetta che il canale apra la connessione. */
    private static final Duration ATTESA_CONNESSIONE = Duration.ofSeconds(10);

    /** Quanto si aspetta che finisca di rispondere. */
    private static final Duration ATTESA_RISPOSTA = Duration.ofSeconds(20);

    /**
     * Il tetto ai byte letti: due megabyte.
     *
     * <p>Un calendario di dodici mesi per una camera sta in qualche decina di kilobyte,
     * quindi il margine e' di due ordini di grandezza. Non e' configurabile (regola 24):
     * non e' qualcosa che due alberghi vorrebbero diverso.
     */
    private static final int DIMENSIONE_MASSIMA = 2 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(ATTESA_CONNESSIONE)
            // I canali spostano questi indirizzi dietro un redirect piu' spesso di quanto
            // si direbbe. NORMAL e non ALWAYS: da https non si torna indietro a http.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Il contenuto dell'indirizzo, come testo.
     *
     * @throws FeedNonRaggiungibileException per qualunque motivo la lettura non riesca —
     *                                       rete, stato HTTP, dimensione
     */
    public String scarica(String url) {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create(url))
                .timeout(ATTESA_RISPOSTA)
                .header("Accept", "text/calendar")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> risposta =
                    client.send(richiesta, HttpResponse.BodyHandlers.ofInputStream());

            if (risposta.statusCode() < 200 || risposta.statusCode() >= 300) {
                // Il corpo non si legge: davanti a un errore e' una pagina HTML che non
                // dice niente di piu' del numero, e finirebbe in un messaggio salvato.
                throw new FeedNonRaggiungibileException(
                        "Il canale ha risposto " + risposta.statusCode());
            }
            return leggiConTetto(risposta.body());

        } catch (IOException ex) {
            throw new FeedNonRaggiungibileException("Indirizzo non raggiungibile: " + descrivi(ex), ex);
        } catch (InterruptedException ex) {
            // Ripristinare il flag e' obbligatorio: senza, chi ha interrotto questo thread
            // non ha piu' modo di accorgersene, ed e' il rilievo che SpotBugs solleva.
            Thread.currentThread().interrupt();
            throw new FeedNonRaggiungibileException("Lettura interrotta", ex);
        }
    }

    /**
     * Come si racconta un guasto di rete a chi leggera' la riga della sorgente.
     *
     * <p><b>Serve perche' le eccezioni piu' comuni qui non hanno un messaggio</b>: una
     * {@code ConnectException} verso una porta chiusa ne ha uno nullo, e senza questo
     * ripiego l'ADMIN si troverebbe scritto <i>"Indirizzo non raggiungibile: null"</i>, che
     * gli dice meno del nome della classe.
     */
    private static String descrivi(IOException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * Legge al massimo {@link #DIMENSIONE_MASSIMA} byte, e se ce n'erano di piu' fallisce
     * invece di troncare.
     *
     * <p><b>Fallire e' l'unica risposta giusta</b>: un calendario tagliato a meta' e' un
     * calendario in cui le ultime occupazioni non ci sono, cioe' camere che tornerebbero
     * in vendita senza che nessuno se ne accorga. Si legge un byte in piu' del tetto
     * proprio per poter distinguere "esattamente pieno" da "troppo grande".
     */
    private String leggiConTetto(InputStream flusso) throws IOException {
        try (InputStream corpo = flusso) {
            byte[] contenuto = corpo.readNBytes(DIMENSIONE_MASSIMA + 1);

            if (contenuto.length > DIMENSIONE_MASSIMA) {
                throw new FeedNonRaggiungibileException(
                        "Il calendario supera i " + (DIMENSIONE_MASSIMA / 1024 / 1024) + " MB consentiti");
            }
            return new String(contenuto, StandardCharsets.UTF_8);
        }
    }

    /**
     * Il calendario non si e' potuto scaricare.
     *
     * <p>Non estende {@code AppException} per la stessa ragione di
     * {@code LetturaIcs.IcsIlleggibileException}: nessuno sta aspettando questa lettura,
     * quindi non c'e' nessuna risposta HTTP da comporre. Diventa un esito {@code ERRORE}
     * sulla riga della sorgente.
     */
    public static class FeedNonRaggiungibileException extends RuntimeException {

        public FeedNonRaggiungibileException(String messaggio) {
            super(messaggio);
        }

        public FeedNonRaggiungibileException(String messaggio, Throwable causa) {
            super(messaggio, causa);
        }
    }
}
