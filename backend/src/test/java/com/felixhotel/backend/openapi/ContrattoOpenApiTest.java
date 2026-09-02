package com.felixhotel.backend.openapi;

import com.felixhotel.backend.service.impl.DurataSoggiorno;
import com.felixhotel.backend.service.impl.MediaCameraServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlli sul contratto OpenAPI, letto dallo stesso file che l'applicazione
 * serve a Swagger UI ({@code /openapi/felix-hotel-api.yaml} sul classpath).
 *
 * <p><b>Perche' esiste.</b> Lo spec e' l'investimento piu' grosso di questo
 * progetto: e' la fonte di verita' di DTO e rotte (regola 12) ed e' anche tutta
 * la documentazione (regola 4). Fino al 2026-08-25 nessuno l'aveva mai aperto in
 * un browser, e l'unica cosa che ne verificava la salute era che il generatore
 * non si lamentasse — cioe' molto poco: un {@code $ref} che non risolve non
 * ferma la build, ferma la pagina di chi prova a leggere l'API.
 *
 * <p><b>Cosa non fa.</b> Non dice che Swagger UI sia bella o navigabile: quello
 * si vede solo guardandola. Dice che il documento e' integro e completo, cioe'
 * elimina le ragioni per cui la UI potrebbe non mostrare niente o mostrare una
 * scheda vuota. Il resto resta lavoro da occhi.
 *
 * <p><b>Guarda anche dove il contratto tocca il codice.</b> Alcuni limiti
 * esistono in due posti per forza — nello spec, che rifiuta la richiesta
 * sbagliata al bordo, e in Java, che li applica dove la decisione viene presa —
 * e OpenAPI non ha modo di generarli l'uno dall'altro. Dove succede, il
 * confronto lo fa {@link LimitiCondivisi}: e' l'unico modo perche' "vanno
 * cambiati insieme" resti vero invece di restare scritto.
 *
 * <p>Unitario e non IT: legge un file, non serve ne' Spring ne' Postgres. Che
 * importi una classe di produzione non lo rende meno unitario — di
 * {@code MediaCameraServiceImpl} legge una costante, non ne costruisce uno.
 */
@DisplayName("Contratto OpenAPI")
class ContrattoOpenApiTest {

    private static final String PERCORSO = "/openapi/felix-hotel-api.yaml";

    /** I verbi HTTP che in uno spec identificano un'operazione. */
    private static final Set<String> VERBI = Set.of("get", "post", "put", "patch", "delete");

    private static Map<String, Object> spec;

    @BeforeAll
    static void caricaSpec() throws Exception {
        try (InputStream in = ContrattoOpenApiTest.class.getResourceAsStream(PERCORSO)) {
            assertThat(in).as("lo spec deve stare sul classpath in %s", PERCORSO).isNotNull();
            spec = new Yaml().load(in);
        }
    }

    /** Tutte le operazioni dello spec, con l'etichetta leggibile "VERBO /rotta". */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, Map<String, Object>>> operazioni() {
        List<Map.Entry<String, Map<String, Object>>> risultato = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");

        paths.forEach((rotta, item) -> ((Map<String, Object>) item).forEach((verbo, op) -> {
            if (VERBI.contains(verbo)) {
                risultato.add(Map.entry(
                        verbo.toUpperCase() + " " + rotta, (Map<String, Object>) op));
            }
        }));
        return risultato;
    }

    @Test
    @DisplayName("si carica e dichiara la versione di OpenAPI")
    void spec_siCarica() {
        // then: se questo cade, Swagger UI non mostra niente del tutto
        assertThat(spec).containsKey("openapi");
        assertThat(spec).containsKey("paths");
        assertThat(operazioni()).as("operazioni dichiarate").isNotEmpty();
    }

    @Nested
    @DisplayName("riferimenti interni")
    class Riferimenti {

        @Test
        @DisplayName("ogni $ref punta a qualcosa che esiste")
        void ref_tutti_risolvono() {
            // when: si seguono tutti i $ref del documento
            List<String> rotti = new ArrayList<>();
            cerca(spec, rotti);

            // then: nessuno deve restare appeso. E' il difetto piu' facile da
            // introdurre — si rinomina uno schema e si dimentica un riferimento — e
            // il piu' cattivo, perche' non rompe ne' la build ne' i test degli
            // endpoint: rompe soltanto la pagina di chi prova a leggere l'API, cioe'
            // l'unica persona che non e' in questa stanza
            assertThat(rotti).as("$ref che non risolvono").isEmpty();
        }

        @SuppressWarnings("unchecked")
        private void cerca(Object nodo, List<String> rotti) {
            if (nodo instanceof Map<?, ?> mappa) {
                mappa.forEach((chiave, valore) -> {
                    if ("$ref".equals(chiave) && valore instanceof String ref) {
                        if (!risolve(ref)) {
                            rotti.add(ref);
                        }
                    } else {
                        cerca(valore, rotti);
                    }
                });
            } else if (nodo instanceof List<?> lista) {
                lista.forEach(v -> cerca(v, rotti));
            }
        }

        @SuppressWarnings("unchecked")
        private boolean risolve(String ref) {
            if (!ref.startsWith("#/")) {
                // Riferimenti a file esterni: in questo progetto non esistono, e se
                // comparissero andrebbero verificati in un altro modo.
                return false;
            }
            Object nodo = spec;
            for (String pezzo : ref.substring(2).split("/")) {
                if (!(nodo instanceof Map<?, ?> mappa) || !mappa.containsKey(pezzo)) {
                    return false;
                }
                nodo = ((Map<String, Object>) mappa).get(pezzo);
            }
            return true;
        }
    }

    @Nested
    @DisplayName("completezza della documentazione")
    class Completezza {

        @Test
        @DisplayName("ogni operazione ha summary, description, operationId e tags")
        void operazioni_tutteDocumentate() {
            // when: si guardano i campi che la regola 4 pretende
            List<String> incomplete = new ArrayList<>();
            for (var op : operazioni()) {
                List<String> mancanti = new ArrayList<>();
                for (String campo : List.of("summary", "description", "operationId", "tags")) {
                    if (op.getValue().get(campo) == null) {
                        mancanti.add(campo);
                    }
                }
                if (!mancanti.isEmpty()) {
                    incomplete.add(op.getKey() + " -> manca " + String.join(", ", mancanti));
                }
            }

            // then: nessuna scheda nuda. E' la regola 4 resa verificabile: senza un
            // controllo, "documentata" e' un'intenzione che regge finche' qualcuno ha
            // fretta
            assertThat(incomplete).as("operazioni incomplete").isEmpty();
        }

        @Test
        @DisplayName("ogni operazione dichiara un esito di successo e il 500")
        void operazioni_dichiaranoSuccessoEdErroreGenerico() {
            List<String> incomplete = new ArrayList<>();
            for (var op : operazioni()) {
                Set<String> codici = codici(op.getValue());
                if (codici.stream().noneMatch(c -> c.startsWith("2"))) {
                    incomplete.add(op.getKey() + " -> nessuna risposta 2xx");
                }
                if (!codici.contains("500")) {
                    incomplete.add(op.getKey() + " -> nessuna risposta 500");
                }
            }

            // then: il 500 va dichiarato ovunque perche' ovunque puo' succedere; un
            // contratto che promette solo gli esiti belli descrive un'applicazione
            // che non esiste
            assertThat(incomplete).as("operazioni con risposte incomplete").isEmpty();
        }

        @Test
        @DisplayName("ogni operazione dice cosa contiene 'data'")
        void operazioni_dichiaranoIlTipoDentroLaBusta() {
            // when: si cerca la menzione di 'data' nella descrizione, ma solo nelle
            // operazioni che la busta la restituiscono davvero
            List<String> mute = new ArrayList<>();
            for (var op : operazioni()) {
                if (!restituisceLaBusta(op.getValue())) {
                    continue;
                }
                String testo = String.valueOf(op.getValue().get("description"));
                if (!testo.contains("'data'") && !testo.contains("\"data\"")) {
                    mute.add(op.getKey());
                }
            }

            // then: e' la contropartita esplicita della regola 10. La busta ha 'data'
            // dichiarato come object — OpenAPI non ha i generics, quindi o si tiene una
            // busta sola non tipizzata (scelta fatta) o si genera un envelope per
            // endpoint — e il prezzo di quella scelta e' che il tipo concreto vada
            // scritto a parole nella descrizione. Questo test e' cio' che impedisce al
            // prezzo di non essere pagato: senza, chi legge Swagger vede 'data: object'
            // e non sa cosa aspettarsi
            assertThat(mute).as("operazioni che non dicono cosa c'e' in 'data'").isEmpty();
        }

        /**
         * Se un'operazione risponda con la busta del progetto.
         *
         * <p><b>Non tutte lo fanno, dal 2026-09-02</b>, ed e' il motivo per cui questo
         * controllo esiste: il feed iCal di una camera restituisce {@code text/calendar},
         * perche' a leggerlo e' Booking e un JSON con dentro un iCal non lo saprebbe usare
         * nessuno. E' l'unica eccezione dichiarata alla regola 10.
         *
         * <p><b>Il filtro guarda il tipo di contenuto e non un elenco di rotte</b>: un
         * elenco andrebbe aggiornato a mano e prima o poi non lo sarebbe, mentre questo
         * distingue da solo le due famiglie. Una rotta nuova che risponde JSON resta
         * obbligata a dire cosa c'e' in 'data'.
         */
        @SuppressWarnings("unchecked")
        private boolean restituisceLaBusta(Map<String, Object> operazione) {
            Map<String, Object> risposte = (Map<String, Object>) operazione.get("responses");
            if (risposte == null) {
                return false;
            }
            Map<String, Object> ok = (Map<String, Object>) risposte.get("200");
            if (ok == null) {
                ok = (Map<String, Object>) risposte.get("201");
            }
            if (ok == null) {
                return false;
            }
            Map<String, Object> contenuto = (Map<String, Object>) ok.get("content");
            return contenuto != null && contenuto.containsKey("application/json");
        }
    }

    @Nested
    @DisplayName("limiti che il contratto condivide col codice")
    class LimitiCondivisi {

        @Test
        @DisplayName("il tetto di 'mediaIds' e' lo stesso numero massimo di foto per tipologia")
        void mediaIds_maxItems_coincideConLaCostante() {
            // when: si legge il tetto che il contratto impone alla richiesta di riordino
            Object dichiarato = dentro("components", "schemas", "MediaCameraOrdineRequest",
                    "properties", "mediaIds", "maxItems");

            // then: deve essere lo stesso numero che il Service applica quando si
            // aggiunge una foto. Sono lo stesso limite visto dai due lati — di qua chi
            // riordina, di la' chi inserisce — e finora a tenerli allineati c'era solo
            // una frase nel javadoc della costante che diceva "vanno cambiati insieme".
            // Una promessa senza il codice che la mantiene e' esattamente cio' che la
            // regola 17 vieta: il giorno che il tetto sale a 40, senza questo test lo
            // spec continua a rifiutare a 30 e il 400 arriva da un limite che nessuno
            // ricordava di aver scritto
            assertThat(dichiarato)
                    .as("maxItems di MediaCameraOrdineRequest.mediaIds")
                    .isEqualTo(MediaCameraServiceImpl.MASSIMO_FOTO_PER_TIPOLOGIA);
        }

        @Test
        @DisplayName("il tetto di 'soggiornoMinimo' e' il massimo di notti di un soggiorno")
        void soggiornoMinimo_maximum_coincideConLaCostante() {
            // when: si legge il tetto che il contratto impone al soggiorno minimo di un
            // periodo tariffario
            Object dichiarato = dentro("components", "schemas", "PeriodoTariffarioRequest",
                    "properties", "soggiornoMinimo", "maximum");

            // then: deve essere il numero massimo di notti che un soggiorno puo' durare.
            // Sono lo stesso limite visto dai due lati: un soggiorno minimo piu' alto del
            // massimo consentito renderebbe quel periodo **invendibile**, perche' nessuna
            // richiesta potrebbe soddisfarlo — e il 400 arriverebbe al cliente per una
            // configurazione che l'albergatore aveva salvato senza obiezioni
            assertThat(dichiarato)
                    .as("maximum di PeriodoTariffarioRequest.soggiornoMinimo")
                    .isEqualTo(DurataSoggiorno.MASSIMO_NOTTI);
        }

        @Test
        @DisplayName("le due rotte che applicano il tetto sulla durata lo scrivono nella descrizione")
        void tettoDurata_scrittoNelleDescrizioni() {
            // given: il tetto non e' esprimibile in nessuno schema — dipende dalla
            // differenza fra due campi — quindi vive nel Service e si dichiara a parole
            // (regola 21). A parole vuol dire in una descrizione, e una descrizione
            // nessun generatore la controlla
            String numero = String.valueOf(DurataSoggiorno.MASSIMO_NOTTI);

            String creazione = (String) dentro("paths", "/api/prenotazioni", "post", "description");
            String ricerca = (String) dentro("paths", "/api/disponibilita", "get", "description");

            // then: tutte e due lo nominano. E' l'unico modo perche' il giorno che il
            // tetto cambia non restino due descrizioni che promettono il numero vecchio
            // — cioe' esattamente la promessa senza codice che la regola 17 vieta,
            // presa dal lato opposto: qui il codice c'e' ed e' la prosa a mentire
            assertThat(creazione)
                    .as("descrizione di POST /api/prenotazioni")
                    .contains(numero);
            assertThat(ricerca)
                    .as("descrizione di GET /api/disponibilita")
                    .contains(numero);
        }
    }

    /**
     * Scende nello spec seguendo le chiavi date. Fallisce dicendo <b>dove</b> si e'
     * fermato: senza, uno schema rinominato darebbe un NullPointerException muto
     * invece del nome del pezzo che non c'e' piu'.
     */
    @SuppressWarnings("unchecked")
    private static Object dentro(String... chiavi) {
        Object nodo = spec;
        StringBuilder percorso = new StringBuilder();
        for (String chiave : chiavi) {
            percorso.append('/').append(chiave);
            assertThat(nodo).as("lo spec deve avere il percorso %s", percorso).isInstanceOf(Map.class);
            nodo = ((Map<String, Object>) nodo).get(chiave);
        }
        return nodo;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> codici(Map<String, Object> operazione) {
        Object risposte = operazione.get("responses");
        return risposte instanceof Map<?, ?> mappa
                ? new LinkedHashSet<>(((Map<String, Object>) mappa).keySet())
                : Set.of();
    }
}
