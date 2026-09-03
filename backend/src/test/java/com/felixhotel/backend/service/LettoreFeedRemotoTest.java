package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.LettoreFeedRemoto;
import com.felixhotel.backend.service.impl.LettoreFeedRemoto.FeedNonRaggiungibileException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'unico pezzo del progetto che esce sulla rete verso un indirizzo deciso da qualcun
 * altro.
 *
 * <p><b>Con un server vero e non con un finto</b>, al contrario di tutto il resto degli
 * unitari: quel che c'e' da provare qui — uno stato HTTP di errore, un corpo piu' grande
 * del consentito, una porta su cui non risponde nessuno — <i>e'</i> il comportamento del
 * client HTTP, e un finto restituirebbe le risposte che gli abbiamo insegnato noi.
 * Il server sta in memoria e su una porta effimera, quindi non serve Docker e questo
 * resta un {@code *Test} e non un {@code *IT}.
 *
 * <p><b>I timeout non hanno un test</b>, ed e' una rinuncia consapevole: provarli
 * vorrebbe dire un server che tace per venti secondi, cioe' venti secondi aggiunti ad
 * ogni build. Sono due costanti lette una volta sola, e il rischio che valgano zero senza
 * che nessuno se ne accorga e' minore del costo di quel test.
 */
@DisplayName("LettoreFeedRemoto")
class LettoreFeedRemotoTest {

    private static final String CALENDARIO = """
            BEGIN:VCALENDAR
            VERSION:2.0
            END:VCALENDAR
            """;

    private HttpServer server;
    private LettoreFeedRemoto lettore;

    @BeforeEach
    void setUp() throws IOException {
        // Porta 0: il sistema ne sceglie una libera, cosi' due build in parallelo sulla
        // stessa macchina non si contendono un numero fisso
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        lettore = new LettoreFeedRemoto();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("restituisce il corpo della risposta")
    void scarica_rispostaValida() {
        rispondi(200, CALENDARIO);

        assertThat(lettore.scarica(indirizzo())).isEqualTo(CALENDARIO);
    }

    @Test
    @DisplayName("uno stato di errore non diventa un calendario vuoto")
    void scarica_statoDiErrore_solleva() {
        // E' il caso pericoloso: una pagina di errore letta come "nessuna occupazione"
        // rimetterebbe in vendita tutte le camere che il canale aveva venduto
        rispondi(404, "<html>Not Found</html>");

        assertThatThrownBy(() -> lettore.scarica(indirizzo()))
                .isInstanceOf(FeedNonRaggiungibileException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("un corpo piu' grande del consentito si rifiuta invece di troncarlo")
    void scarica_corpoEnorme_solleva() {
        // Troncare darebbe un calendario in cui le ultime occupazioni non ci sono, cioe'
        // camere che tornano in vendita senza che nessuno se ne accorga
        rispondi(200, "X".repeat(3 * 1024 * 1024));

        assertThatThrownBy(() -> lettore.scarica(indirizzo()))
                .isInstanceOf(FeedNonRaggiungibileException.class)
                .hasMessageContaining("MB consentiti");
    }

    @Test
    @DisplayName("un indirizzo che non risponde diventa un errore, non un'eccezione qualsiasi")
    void scarica_nessunoRisponde_solleva() {
        // Il giro periodico distingue questo caso solo dal tipo dell'eccezione: se
        // arrivasse una IOException nuda, il messaggio salvato sulla sorgente sarebbe
        // quello di una libreria invece che una frase
        int porta = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> lettore.scarica("http://127.0.0.1:" + porta + "/calendario.ics"))
                .isInstanceOf(FeedNonRaggiungibileException.class);
    }

    private void rispondi(int stato, String corpo) {
        server.createContext("/calendario.ics", scambio -> scrivi(scambio, stato, corpo));
    }

    private static void scrivi(HttpExchange scambio, int stato, String corpo) throws IOException {
        byte[] byteDelCorpo = corpo.getBytes(StandardCharsets.UTF_8);
        scambio.getResponseHeaders().add("Content-Type", "text/calendar");
        scambio.sendResponseHeaders(stato, byteDelCorpo.length);

        try (OutputStream uscita = scambio.getResponseBody()) {
            uscita.write(byteDelCorpo);
        }
    }

    private String indirizzo() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/calendario.ics";
    }
}
