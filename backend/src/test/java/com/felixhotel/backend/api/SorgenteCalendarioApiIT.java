package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.SorgenteCalendarioRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il giro completo: un canale che pubblica un calendario, e una camera che da noi smette
 * di essere vendibile.
 *
 * <p><b>Qui sta il valore del branch</b>, e per una ragione che gli unitari non possono
 * coprire: la catena e' lunga — HTTP, lettura del formato, cancellazione, scrittura,
 * vincoli del database, query della disponibilita' — e ognuno di quei pezzi e' gia' provato
 * da solo. Quel che nessuno prova, se non questo, e' che <b>messi in fila facciano quel che
 * serve</b>: una camera venduta su Booking che sparisce dalla disponibilita'.
 *
 * <p><b>Il canale e' un server HTTP vero</b>, in memoria e su una porta effimera. Un finto
 * non servirebbe: la rotta di sincronizzazione manuale esiste proprio per provare che
 * l'indirizzo configurato funzioni, e provarla senza rete vorrebbe dire provare tutto
 * tranne quello.
 *
 * <p><b>Ogni test si crea la propria tipologia e la propria camera</b>, come i blocchi:
 * l'isolamento e' per id, e il database non viene ripulito fra un test e l'altro.
 */
@DisplayName("API delle sorgenti di calendario")
class SorgenteCalendarioApiIT extends IntegrationTestBase {

    private static final String SORGENTI = "/api/sorgenti-calendario";
    private static final String SINCRONIZZA = SORGENTI + "/sincronizza";
    private static final String BLOCCHI = "/api/blocchi";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";
    private static final String DISPONIBILITA = "/api/disponibilita";

    private static final DateTimeFormatter ICS = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private CreatoreStaff creatoreStaff;

    /** Il canale finto. Quel che risponde lo decide ogni test scrivendo in {@link #corpo}. */
    private HttpServer canale;

    private final AtomicReference<String> corpo = new AtomicReference<>("");

    @BeforeEach
    void avviaIlCanale() throws IOException {
        canale = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        canale.createContext("/calendario.ics", this::rispondi);
        canale.start();
    }

    @AfterEach
    void fermaIlCanale() {
        canale.stop(0);
    }

    @Test
    @DisplayName("una camera venduta sul canale sparisce dalla disponibilita'")
    void sincronizza_cameraVendutaAltrove_riduceLaDisponibilita() throws Exception {
        // given: una tipologia con due camere, quindi due disponibili
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(200);

        disponibiliAtteso(tipologia, arrivo, 2);

        // when: il canale dice di aver venduto quella camera in quelle notti
        pubblica(evento("booking-1", arrivo, arrivo.plusDays(2)));
        creaSorgente(admin, camera);
        sincronizza(admin);

        // then: ne resta una. E' tutto il branch in una riga — senza questo, leggere il
        // calendario sarebbe un'operazione che non cambia niente
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("il blocco che ne nasce nomina la camera e dice da dove viene")
    void sincronizza_scriveUnBloccoDelCanale() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(210);

        pubblica(evento("booking-1", arrivo, arrivo.plusDays(3)));
        creaSorgente(admin, camera);
        sincronizza(admin);

        // Nomina la camera, ed e' l'unica cosa che nominarla serve a fare: il check-in
        // non assegnera' piu' la stanza che il canale ha venduto
        mockMvc.perform(get(BLOCCHI)
                        .param("cameraId", String.valueOf(camera))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].origine").value("CANALE_ESTERNO"))
                .andExpect(jsonPath("$.data[0].camera.id").value(camera))
                .andExpect(jsonPath("$.data[0].dataInizio").value(arrivo.toString()))
                .andExpect(jsonPath("$.data[0].dataFine").value(arrivo.plusDays(3).toString()));
    }

    @Test
    @DisplayName("un'occupazione sparita dal calendario libera la camera al giro dopo")
    void sincronizza_occupazioneSparita_liberaLaCamera() throws Exception {
        // given: una camera venduta, poi disdetta sul canale
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(220);

        pubblica(evento("booking-1", arrivo, arrivo.plusDays(2)));
        creaSorgente(admin, camera);
        sincronizza(admin);
        disponibiliAtteso(tipologia, arrivo, 0);

        // when: il canale non la elenca piu'
        pubblica("");
        sincronizza(admin);

        // then: torna vendibile. E' il senso di "vivono quanto il calendario", e la prova
        // che la cancellazione preventiva non lascia indietro righe morte
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("il giro non porta via i blocchi manuali")
    void sincronizza_nonToccaIBlocchiManuali() throws Exception {
        // given: una manutenzione inserita alla reception su una camera, e un canale che
        // vende un'altra camera della stessa tipologia
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long inManutenzione = creaCamera(admin, tipologia);
        long venduta = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(230);

        creaBloccoManuale(admin, tipologia, inManutenzione, arrivo, arrivo.plusDays(2));

        pubblica(evento("booking-1", arrivo, arrivo.plusDays(2)));
        creaSorgente(admin, venduta);
        sincronizza(admin);

        // then: due blocchi e non uno. Senza la colonna della sorgente, il giro del canale
        // guarderebbe l'origine e porterebbe via la manutenzione di qualcun altro
        mockMvc.perform(get(BLOCCHI)
                        .param("tipologiaCameraId", String.valueOf(tipologia))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("un'occupazione che urta un blocco manuale si salta e si segnala")
    void sincronizza_sovrapposizione_segnalaSenzaFallire() throws Exception {
        // given: la camera e' gia' ferma per manutenzione proprio in quelle notti
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(240);

        creaBloccoManuale(admin, tipologia, camera, arrivo, arrivo.plusDays(4));

        pubblica(evento("booking-1", arrivo.plusDays(1), arrivo.plusDays(3)));
        creaSorgente(admin, camera);

        // when: il vincolo di esclusione del V15 rifiuterebbe la riga, quindi si salta
        // invece di far esplodere il giro
        sincronizza(admin);

        // then: e lo si dice, perche' una camera venduta due volte e' un fatto che
        // qualcuno deve sapere
        esitoDella(admin, camera)
                .andExpect(jsonPath("$.data[0].ultimoEsito").value("CONFLITTI"))
                .andExpect(jsonPath("$.data[0].ultimoMessaggio")
                        .value(containsString("ha gia' un altro blocco")));

        // La manutenzione resta l'unico blocco: l'occupazione del canale non e' entrata
        mockMvc.perform(get(BLOCCHI)
                        .param("cameraId", String.valueOf(camera))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].origine").value("MANUALE"));
    }

    @Test
    @DisplayName("un canale irraggiungibile diventa un esito, non un giro fallito")
    void sincronizza_canaleGiu_annotaLErrore() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        creaSorgente(admin, camera);
        canale.stop(0);

        // Il giro e' stato fatto: la rotta risponde 200 anche se quel canale non ha
        // risposto, perche' un 500 direbbe che la richiesta non e' stata eseguita — che e'
        // falso — e nasconderebbe le sorgenti che invece hanno funzionato
        sincronizza(admin);

        esitoDella(admin, camera)
                .andExpect(jsonPath("$.data[0].ultimoEsito").value("ERRORE"))
                .andExpect(jsonPath("$.data[0].ultimoMessaggio").isNotEmpty());
    }

    @Test
    @DisplayName("i blocchi precedenti restano quando il canale non risponde")
    void sincronizza_canaleGiu_nonLiberaLeCamere() throws Exception {
        // given: una camera venduta e regolarmente bloccata
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(250);

        pubblica(evento("booking-1", arrivo, arrivo.plusDays(2)));
        creaSorgente(admin, camera);
        sincronizza(admin);
        disponibiliAtteso(tipologia, arrivo, 0);

        // when: il canale smette di rispondere
        canale.stop(0);
        sincronizza(admin);

        // then: la camera resta bloccata. Un canale che non risponde non ha disdetto
        // niente, e liberarla sarebbe il danno peggiore fra i due
        disponibiliAtteso(tipologia, arrivo, 0);
    }

    @Test
    @DisplayName("il nostro stesso feed rimandato indietro non occupa niente")
    void sincronizza_ecoDelNostroFeed_ignorato() throws Exception {
        // given: il canale ripubblica l'evento con l'UID che gli abbiamo dato noi, che e'
        // quel che fa davvero chi importa il nostro calendario e riesporta il proprio
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(260);

        pubblica(evento(arrivo.format(ICS) + "-" + arrivo.plusDays(2).format(ICS) + "-1@felix-hotel",
                arrivo, arrivo.plusDays(2)));
        creaSorgente(admin, camera);

        // when / then: nessun blocco. Senza questo filtro l'anello si chiuderebbe — una
        // nostra prenotazione tornerebbe come blocco, la camera risulterebbe occupata due
        // volte e ogni giro segnalerebbe un overbooking inventato da noi
        sincronizza(admin);

        mockMvc.perform(get(BLOCCHI)
                        .param("cameraId", String.valueOf(camera))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("togliere la sorgente rimette in vendita le sue camere")
    void elimina_togliePureIBlocchi() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(270);

        pubblica(evento("booking-1", arrivo, arrivo.plusDays(2)));
        long sorgente = creaSorgente(admin, camera);
        sincronizza(admin);
        disponibiliAtteso(tipologia, arrivo, 0);

        // when
        mockMvc.perform(delete(SORGENTI + "/" + sorgente)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // then: il CASCADE del V17. Smettere di leggere un canale vuol dire che quel
        // canale non ci dice piu' niente, non che l'ultima cosa detta resta vera per sempre
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("lo stesso indirizzo due volte sulla stessa camera e' 409")
    void crea_doppione_risponde409() throws Exception {
        // Leggerlo due volte toglierebbe due unita' mentre la stanza e' una
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        creaSorgente(admin, camera);

        mockMvc.perform(post(SORGENTI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SorgenteCalendarioRequest()
                                .cameraId(camera)
                                .nome("Booking")
                                .url(indirizzoDelCanale()))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("un indirizzo che non e' http e' 400")
    void crea_schemaNonAmmesso_risponde400() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        mockMvc.perform(post(SORGENTI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SorgenteCalendarioRequest()
                                .cameraId(camera)
                                .nome("Booking")
                                .url("file:///etc/passwd"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("http")));
    }

    @Test
    @DisplayName("registrarla non scarica niente, nemmeno se il canale e' giu'")
    void crea_canaleGiu_risponde201() throws Exception {
        // Un canale lento o momentaneamente giu' non deve impedire di salvare una
        // configurazione giusta: l'indirizzo si prova al primo giro
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        canale.stop(0);

        mockMvc.perform(post(SORGENTI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SorgenteCalendarioRequest()
                                .cameraId(camera)
                                .nome("Booking")
                                .url(indirizzoDelCanale()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ultimoEsito").doesNotExist())
                .andExpect(jsonPath("$.data.ultimaSincronizzazione").doesNotExist());
    }

    @Test
    @DisplayName("lo STAFF legge ma non configura, il cliente non vede niente")
    void permessi_sonoQuelliDichiarati() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        mockMvc.perform(get(SORGENTI))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(SORGENTI).header("Authorization", "Bearer " + tokenCliente()))
                .andExpect(status().isForbidden());

        // Lo STAFF legge: deve poter capire perche' una camera risulta occupata
        mockMvc.perform(get(SORGENTI).header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isOk());

        // ...ma non decide da quali canali ci si fa dire cosa e' vendibile
        mockMvc.perform(post(SORGENTI)
                        .header("Authorization", "Bearer " + tokenStaff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SorgenteCalendarioRequest()
                                .cameraId(camera)
                                .nome("Booking")
                                .url(indirizzoDelCanale()))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(SINCRONIZZA).header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- supporto

    /** Quel che il canale rispondera' da qui in avanti. */
    private void pubblica(String eventi) {
        corpo.set(("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Un canale qualsiasi//IT
                """ + eventi + "END:VCALENDAR\n").replace("\n", "\r\n"));
    }

    private String evento(String uid, LocalDate inizio, LocalDate fine) {
        return "BEGIN:VEVENT\nUID:" + uid
                + "\nDTSTART;VALUE=DATE:" + inizio.format(ICS)
                + "\nDTEND;VALUE=DATE:" + fine.format(ICS)
                + "\nEND:VEVENT\n";
    }

    private void rispondi(HttpExchange scambio) throws IOException {
        byte[] byteDelCorpo = corpo.get().getBytes(StandardCharsets.UTF_8);
        scambio.getResponseHeaders().add("Content-Type", "text/calendar");
        scambio.sendResponseHeaders(200, byteDelCorpo.length);

        try (OutputStream uscita = scambio.getResponseBody()) {
            uscita.write(byteDelCorpo);
        }
    }

    private String indirizzoDelCanale() {
        return "http://127.0.0.1:" + canale.getAddress().getPort() + "/calendario.ics";
    }

    private long creaSorgente(String tokenAdmin, long camera) throws Exception {
        String risposta = mockMvc.perform(post(SORGENTI)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SorgenteCalendarioRequest()
                                .cameraId(camera)
                                .nome("Booking")
                                .url(indirizzoDelCanale()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    /**
     * Fa partire il giro.
     *
     * <p><b>Legge tutte le sorgenti del database</b>, comprese quelle lasciate dai test
     * precedenti — il database non viene ripulito, e quelle puntano a porte ormai chiuse.
     * E' la ragione per cui qui non si verifica <b>nessun contatore del riepilogo</b>:
     * {@code inErrore} e {@code conConflitti} sono somme su tutto l'albergo, e un test che
     * si aspettasse un numero preciso fallirebbe a seconda di quali altri test siano girati
     * prima. Quel che ogni test puo' controllare e' la <i>propria</i> riga, e la si guarda
     * con {@link #esitoDella}. I contatori sono provati dove sono deterministici, cioe'
     * nell'unitario del Service.
     */
    private void sincronizza(String tokenAdmin) throws Exception {
        mockMvc.perform(post(SINCRONIZZA)
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk());
    }

    /**
     * L'elenco filtrato sulla camera del test, che ne contiene una sola: ogni test si crea
     * la propria camera, quindi {@code $.data[0]} e' la sua sorgente e non quella di
     * qualcun altro.
     */
    private org.springframework.test.web.servlet.ResultActions esitoDella(String tokenAdmin, long camera)
            throws Exception {
        return mockMvc.perform(get(SORGENTI)
                        .param("cameraId", String.valueOf(camera))
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private void creaBloccoManuale(String tokenAdmin, long tipologia, long camera,
                                   LocalDate inizio, LocalDate fine) throws Exception {
        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new com.felixhotel.backend.dto.BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .cameraId(camera)
                                .dataInizio(inizio)
                                .dataFine(fine)
                                .note("Bagno in rifacimento"))))
                .andExpect(status().isCreated());
    }

    /**
     * Quante camere la ricerca dichiara libere per quella tipologia in quelle notti.
     * Scorre tutte le pagine per la stessa ragione del gemello in
     * {@code BloccoDisponibilitaApiIT}: la suite crea qualche centinaio di tipologie, e
     * guardare solo la prima pagina passa in locale e fallisce in CI.
     */
    private void disponibiliAtteso(long tipologia, LocalDate arrivo, int atteso) throws Exception {
        for (int pagina = 0; ; pagina++) {
            String risposta = mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(2).toString())
                            .param("page", String.valueOf(pagina))
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var corpoRisposta = objectMapper.readTree(risposta);
            for (var riga : corpoRisposta.path("data")) {
                if (riga.path("tipologia").path("id").asLong() == tipologia) {
                    org.assertj.core.api.Assertions.assertThat(
                                    riga.path("camereDisponibili").asInt())
                            .as("camere disponibili della tipologia %d", tipologia)
                            .isEqualTo(atteso);
                    return;
                }
            }
            if (pagina + 1 >= corpoRisposta.path("page").path("totalPages").asInt()) {
                throw new AssertionError(
                        "la tipologia " + tipologia + " non compare in nessuna pagina della disponibilita'");
            }
        }
    }

    private long creaTipologia(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private long creaCamera(String tokenAdmin, long tipologia) throws Exception {
        String risposta = mockMvc.perform(post(CAMERE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(tipologia))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaAdmin(email);
        return auth.ottieniToken(email);
    }

    private String tokenStaff() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaStaff(email);
        return auth.ottieniToken(email);
    }

    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        auth.registraAccount(cliente);
        return auth.ottieniToken(cliente.getEmail());
    }
}
