package com.felixhotel.backend.service.impl;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * Quanti redirect si seguono prima di arrendersi. I canali spostano questi indirizzi
     * dietro un redirect piu' spesso di quanto si direbbe, ma cinque salti sono gia' molti
     * piu' del necessario: oltre, non e' uno spostamento, e' un anello.
     */
    private static final int SALTI_MASSIMI = 5;

    /**
     * <b>I redirect NON si seguono da soli</b>, ed e' la differenza che rende utile il
     * controllo sull'indirizzo. Con {@code Redirect.NORMAL} il client seguirebbe il
     * {@code Location} senza chiedere niente a nessuno, e un canale — o chiunque ne
     * controlli il dominio — potrebbe rimandarci su {@code http://169.254.169.254},
     * scavalcando in un salto la verifica fatta sull'indirizzo di partenza. Seguendoli a
     * mano, <b>ogni</b> salto passa dallo stesso controllo.
     */
    private final IndirizzoConsentito indirizzoConsentito;

    public LettoreFeedRemoto(IndirizzoConsentito indirizzoConsentito) {
        this.indirizzoConsentito = indirizzoConsentito;
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(ATTESA_CONNESSIONE)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Il contenuto dell'indirizzo, come testo.
     *
     * @throws FeedNonRaggiungibileException per qualunque motivo la lettura non riesca —
     *                                       rete, stato HTTP, dimensione
     */
    public String scarica(String url) {
        String corrente = url;

        for (int salto = 0; salto <= SALTI_MASSIMI; salto++) {
            // Ad **ogni** salto, non solo al primo: il controllo fatto quando la sorgente
            // e' stata salvata non dice niente su dove un redirect stia portando adesso.
            indirizzoConsentito.verifica(corrente);

            HttpResponse<InputStream> risposta = chiedi(corrente);
            int stato = risposta.statusCode();

            if (stato >= 300 && stato < 400) {
                corrente = prossimoSalto(risposta, corrente);
                continue;
            }
            if (stato < 200 || stato >= 300) {
                // Il corpo non si legge: davanti a un errore e' una pagina HTML che non
                // dice niente di piu' del numero, e finirebbe in un messaggio salvato.
                chiudi(risposta.body());
                throw new FeedNonRaggiungibileException("Il canale ha risposto " + stato);
            }
            return leggiConTetto(risposta.body());
        }

        throw new FeedNonRaggiungibileException(
                "L'indirizzo rimbalza da piu' di " + SALTI_MASSIMI + " redirect");
    }

    private HttpResponse<InputStream> chiedi(String url) {
        HttpRequest richiesta = HttpRequest.newBuilder(URI.create(url))
                .timeout(ATTESA_RISPOSTA)
                .header("Accept", "text/calendar")
                .GET()
                .build();

        try {
            return client.send(richiesta, HttpResponse.BodyHandlers.ofInputStream());
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
     * Dove porta un redirect.
     *
     * <p>Il {@code Location} puo' essere relativo — la specifica lo consente e i server lo
     * fanno — quindi si risolve contro l'indirizzo da cui si viene. Risolverlo e' anche
     * cio' che impedisce a un {@code Location} vuoto o storto di diventare una richiesta
     * verso un posto imprevisto: se non si compone, il giro finisce qui.
     */
    private static String prossimoSalto(HttpResponse<InputStream> risposta, String provenienza) {
        chiudi(risposta.body());

        String destinazione = risposta.headers().firstValue("Location")
                .orElseThrow(() -> new FeedNonRaggiungibileException(
                        "Il canale ha risposto " + risposta.statusCode() + " senza dire dove andare"));

        try {
            return URI.create(provenienza).resolve(destinazione).toString();
        } catch (IllegalArgumentException ex) {
            throw new FeedNonRaggiungibileException("Il redirect porta a un indirizzo non valido", ex);
        }
    }

    /** Il corpo che non si legge va comunque chiuso, o la connessione resta appesa. */
    private static void chiudi(InputStream corpo) {
        try {
            corpo.close();
        } catch (IOException ex) {
            // Chiudere un corpo che non ci interessa non ha nessun modo utile di fallire,
            // e sollevare qui coprirebbe il motivo vero per cui siamo arrivati fin qui.
            log.debug("Corpo della risposta non chiuso: {}", ex.getMessage());
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
    private String leggiConTetto(InputStream flusso) {
        try (InputStream corpo = flusso) {
            byte[] contenuto = corpo.readNBytes(DIMENSIONE_MASSIMA + 1);

            if (contenuto.length > DIMENSIONE_MASSIMA) {
                throw new FeedNonRaggiungibileException(
                        "Il calendario supera i " + (DIMENSIONE_MASSIMA / 1024 / 1024) + " MB consentiti");
            }
            return new String(contenuto, StandardCharsets.UTF_8);

        } catch (IOException ex) {
            // La connessione caduta a meta' della lettura e' un caso a se' rispetto a
            // quella mai aperta, ed e' quello piu' insidioso: senza questo ramo darebbe un
            // calendario troncato, cioe' camere che tornano in vendita.
            throw new FeedNonRaggiungibileException(
                    "Lettura interrotta a meta': " + descrivi(ex), ex);
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
