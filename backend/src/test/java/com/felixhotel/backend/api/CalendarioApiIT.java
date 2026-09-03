package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.BloccoRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione del calendario iCal.
 *
 * <p><b>Quel che si prova qui e non altrove</b>: che il feed sia raggiungibile
 * <i>davvero</i> senza autenticarsi — cioe' che la rotta pubblica sia elencata in
 * {@code SecurityConfig} — e che quel che esce sia iCal e non la busta JSON del progetto.
 * Sono le due cose che un unitario non puo' vedere, perche' vivono nella catena di
 * sicurezza e nel convertitore delle risposte.
 *
 * <p>Il <i>contenuto</i> del calendario — quale camera risulti occupata, con che periodi
 * — lo provano {@code DistribuzioneOccupazioneTest} e {@code CalendarioIcsTest}, che lo
 * fanno molto meglio perche' non devono costruire un albergo per ogni caso.
 */
@DisplayName("API del calendario iCal")
class CalendarioApiIT extends IntegrationTestBase {

    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";
    private static final String BLOCCHI = "/api/blocchi";

    private static final DateTimeFormatter ICAL = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Test
    @DisplayName("il feed si scarica senza autenticarsi, ed e' iCal")
    void feed_senzaAutenticazione_restituisceIcal() throws Exception {
        // given: una camera pubblicata
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        String url = generaIndirizzo(admin, camera);
        String token = tokenDa(url);

        // when / then: nessun header di autorizzazione. E' il punto: Booking non ha modo
        // di autenticarsi, quindi se questa rotta non fosse fra i permitAll il canale
        // riceverebbe un 401 e nessuno saprebbe perche'
        String ics = mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ics)
                .as("il feed deve essere iCal, non la busta JSON del progetto")
                .startsWith("BEGIN:VCALENDAR")
                .doesNotContain("\"status\"");
    }

    @Test
    @DisplayName("il tipo di contenuto e' text/calendar")
    void feed_haIlTipoDiContenutoGiusto() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        String token = tokenDa(generaIndirizzo(admin, camera));

        // then: e' l'unica rotta del progetto che non risponde application/json, e senza
        // il tipo giusto un canale scarica un file che poi non sa aprire
        mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("text/calendar"));
    }

    @Test
    @DisplayName("spubblicare una camera fa sparire il suo feed")
    void spubblica_ilFeedNonEsistePiu() throws Exception {
        // given: una camera pubblicata, il cui indirizzo funziona
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        String token = tokenDa(generaIndirizzo(admin, camera));

        mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isOk());

        // when
        mockMvc.perform(delete(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        // then: **non e' la stessa cosa che rigenerare.** Rigenerare invalida il vecchio
        // indirizzo ma ne crea uno nuovo altrettanto valido; qui di indirizzi validi non
        // ne resta nessuno, che e' quel che serve a chi ha pubblicato per sbaglio
        mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("spubblicare una camera mai pubblicata risponde comunque 200")
    void spubblica_cameraMaiPubblicata_risponde200() throws Exception {
        // Chiedere che smetta una cosa che gia' non succede non e' un errore: il risultato
        // voluto — nessun indirizzo attivo — e' esattamente quello che si ottiene, e un
        // 404 costringerebbe chi chiama a distinguere due casi che per lui sono lo stesso
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        mockMvc.perform(delete(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("spubblicare e' degli ADMIN, come pubblicare")
    void spubblica_daStaff_risponde403() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        mockMvc.perform(delete(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un blocco compare nel calendario della sua camera")
    void feed_conBlocco_loRiporta() throws Exception {
        // given: una camera bloccata per manutenzione
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate inizio = LocalDate.now().plusDays(20);

        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .cameraId(camera)
                                .dataInizio(inizio)
                                .dataFine(inizio.plusDays(3)))))
                .andExpect(status().isCreated());

        String token = tokenDa(generaIndirizzo(admin, camera));

        // when / then: e' il giro intero — blocco, distribuzione, formato — che nessuno
        // dei due unitari prova insieme
        String ics = mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ics)
                .contains("DTSTART;VALUE=DATE:" + inizio.format(ICAL))
                .contains("DTEND;VALUE=DATE:" + inizio.plusDays(3).format(ICAL));
    }

    @Test
    @DisplayName("una camera libera ha un calendario vuoto ma valido")
    void feed_senzaOccupazioni_restituisceUnCalendarioVuoto() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        String token = tokenDa(generaIndirizzo(admin, camera));

        // then: nessun evento, ma l'involucro c'e'. Un file vuoto davvero farebbe
        // fallire il canale invece di dirgli "questa camera e' tutta libera"
        String ics = mockMvc.perform(get("/api/calendario/" + token + ".ics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(ics).startsWith("BEGIN:VCALENDAR").doesNotContain("BEGIN:VEVENT");
    }

    @Test
    @DisplayName("un indirizzo inventato e' 404")
    void feed_conTokenInesistente_risponde404() throws Exception {
        mockMvc.perform(get("/api/calendario/questo-non-esiste.ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("rigenerare l'indirizzo invalida il precedente")
    void generaIndirizzo_dueVolte_invalidaIlPrimo() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        String primo = tokenDa(generaIndirizzo(admin, camera));

        // when
        String secondo = tokenDa(generaIndirizzo(admin, camera));

        // then: e' tutto il motivo per cui l'indirizzo e' un token salvato e non
        // qualcosa derivato dall'id — se finisce dove non doveva, si cambia
        assertThat(secondo).isNotEqualTo(primo);
        mockMvc.perform(get("/api/calendario/" + primo + ".ics"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/calendario/" + secondo + ".ics"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("generare l'indirizzo e' solo degli ADMIN")
    void generaIndirizzo_daStaffOCliente_rispondeVietato() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);

        // pubblicare la disponibilita' verso l'esterno non e' un'operazione di turno,
        // ed e' anche l'unico gesto che possa invalidare un feed gia' configurato
        mockMvc.perform(put(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + tokenCliente()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(CAMERE + "/" + camera + "/calendario"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- supporto

    private String generaIndirizzo(String tokenAdmin, long camera) throws Exception {
        String risposta = mockMvc.perform(put(CAMERE + "/" + camera + "/calendario")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("url").asString();
    }

    /** Il token dentro l'indirizzo restituito: e' l'ultimo pezzo, senza estensione. */
    private String tokenDa(String url) {
        return url.substring(url.lastIndexOf('/') + 1).replace(".ics", "");
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
