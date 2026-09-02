package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.BloccoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dei blocchi di disponibilita'.
 *
 * <p><b>E' qui che sta il valore del branch</b>, e non nell'unitario: il CRUD e' corto e
 * prevedibile, mentre le due cose che potevano davvero rompersi vivono nel database — la
 * query della disponibilita', che adesso deve contare anche i blocchi, e il vincolo di
 * esclusione che impedisce di bloccare due volte la stessa camera negli stessi giorni.
 * Nessuna delle due si puo' provare con dei finti.
 *
 * <p><b>Ogni test si crea la propria tipologia</b>, come fanno le tariffe e al contrario
 * delle aliquote: un blocco appartiene a una tipologia, quindi l'isolamento e' per id e
 * non serve inventarsi anni diversi.
 */
@DisplayName("API dei blocchi di disponibilita'")
class BloccoDisponibilitaApiIT extends IntegrationTestBase {

    private static final String BLOCCHI = "/api/blocchi";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";
    private static final String DISPONIBILITA = "/api/disponibilita";
    private static final String PRENOTAZIONI = "/api/prenotazioni";

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Test
    @DisplayName("un blocco toglie una camera alla disponibilita'")
    void blocco_riduceLaDisponibilita() throws Exception {
        // given: una tipologia con due camere, quindi due disponibili
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        creaCamera(admin, tipologia);
        creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(30);

        disponibiliAtteso(tipologia, arrivo, 2);

        // when: se ne blocca una
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .dataInizio(arrivo)
                .dataFine(arrivo.plusDays(2))
                .note("Bagno in rifacimento"));

        // then: ne resta una. E' la meta' che conta di tutto il branch — senza questa,
        // il blocco sarebbe una riga che non fa niente
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("un blocco fuori dal periodo cercato non toglie niente")
    void blocco_fuoriPeriodo_nonRiduce() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(40);

        // when: il blocco finisce il giorno in cui il soggiorno comincia
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .dataInizio(arrivo.minusDays(3))
                .dataFine(arrivo));

        // then: la camera e' vendibile. E' il confine, ed e' il test che conta piu' del
        // suo gemello: un blocco scritto con le disuguaglianze storte toglierebbe una
        // notte di troppo, e nessuno se ne accorgerebbe guardando i casi centrali
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("tolto il blocco, la camera torna vendibile")
    void blocco_rimosso_riapreLaVendita() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(50);
        long blocco = creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .dataInizio(arrivo)
                .dataFine(arrivo.plusDays(2)));

        disponibiliAtteso(tipologia, arrivo, 0);

        // when
        mockMvc.perform(delete(BLOCCHI + "/" + blocco)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // then
        disponibiliAtteso(tipologia, arrivo, 1);
    }

    @Test
    @DisplayName("la stessa camera bloccata due volte sugli stessi giorni e' 409")
    void blocco_sovrapposto_risponde409() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate inizio = LocalDate.now().plusDays(60);
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .cameraId(camera)
                .dataInizio(inizio)
                .dataFine(inizio.plusDays(5)));

        // when / then: e' il test che guarda il vincolo di esclusione del V15, non il
        // controllo del Service. Due blocchi sulla stessa stanza toglierebbero due
        // camere mentre la stanza e' una, e l'albergo risulterebbe pieno prima di esserlo
        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .cameraId(camera)
                                .dataInizio(inizio.plusDays(2))
                                .dataFine(inizio.plusDays(7)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("sovrappone")));
    }

    @Test
    @DisplayName("due blocchi consecutivi che si toccano convivono")
    void blocco_adiacente_risponde201() throws Exception {
        // given: il primo finisce il giorno in cui comincia il secondo
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate inizio = LocalDate.now().plusDays(70);
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .cameraId(camera)
                .dataInizio(inizio)
                .dataFine(inizio.plusDays(3)));

        // when / then: e' il confine del vincolo, e vale piu' del test qui sopra —
        // scritto con disuguaglianze non strette, l'esclusione rifiuterebbe anche due
        // manutenzioni consecutive, che sono il caso normale
        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .cameraId(camera)
                                .dataInizio(inizio.plusDays(3))
                                .dataFine(inizio.plusDays(6)))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("due blocchi anonimi sugli stessi giorni convivono: sono due unita'")
    void blocco_anonimoDuplicato_risponde201() throws Exception {
        // given: nessuna camera nominata. E' il caso che il branch dell'iCal produrra' in
        // continuazione — due camere vendute su Booking negli stessi giorni
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        LocalDate inizio = LocalDate.now().plusDays(80);
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .dataInizio(inizio)
                .dataFine(inizio.plusDays(2)));

        // when / then: il vincolo e' parziale apposta, e questo test e' l'unica cosa che
        // lo dimostra
        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .dataInizio(inizio)
                                .dataFine(inizio.plusDays(2)))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("il check-in non assegna una camera bloccata")
    void checkIn_conCameraBloccata_scegliUnAltra() throws Exception {
        // given: due camere, una bloccata proprio nelle notti del soggiorno
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long bloccata = creaCamera(admin, tipologia);
        creaCamera(admin, tipologia);
        LocalDate oggi = LocalDate.now();

        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .cameraId(bloccata)
                .dataInizio(oggi)
                .dataFine(oggi.plusDays(3)));

        long prenotazione = prenotazioneConfermata(admin, tipologia, oggi, oggi.plusDays(2));
        registraOspiti(admin, prenotazione);

        // when
        String risposta = mockMvc.perform(put(PRENOTAZIONI + "/" + prenotazione + "/check-in")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // then: la camera assegnata non e' quella bloccata. Senza la condizione aggiunta
        // a trovaAssegnabili, il check-in avrebbe scelto proprio la stanza che qualcuno
        // aveva chiuso — e l'errore si sarebbe visto solo al banco
        long assegnata = objectMapper.readTree(risposta).path("data").path("camera").path("id").asLong();
        org.assertj.core.api.Assertions.assertThat(assegnata)
                .as("il check-in non deve assegnare la camera bloccata")
                .isNotEqualTo(bloccata);
    }

    @Test
    @DisplayName("una camera di un'altra tipologia e' 400")
    void blocco_conCameraDiAltraTipologia_risponde400() throws Exception {
        // given: due tipologie, e si prova a bloccare la camera della seconda dichiarando
        // la prima
        String admin = tokenAdmin();
        long prima = creaTipologia(admin);
        long seconda = creaTipologia(admin);
        long cameraDellaSeconda = creaCamera(admin, seconda);
        LocalDate inizio = LocalDate.now().plusDays(90);

        // when / then: il database non lo puo' vedere — un CHECK non legge un'altra
        // tabella — quindi se non lo prendesse il Service quel blocco toglierebbe una
        // unita' alla tipologia sbagliata
        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(prima)
                                .cameraId(cameraDellaSeconda)
                                .dataInizio(inizio)
                                .dataFine(inizio.plusDays(2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tipologia")));
    }

    @Test
    @DisplayName("un periodo di zero notti e' 400")
    void blocco_conDateUguali_risponde400() throws Exception {
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        LocalDate giorno = LocalDate.now().plusDays(100);

        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .dataInizio(giorno)
                                .dataFine(giorno))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("il cliente non li vede, e l'anonimo prende 401")
    void blocchi_senzaPermesso_rispondeVietato() throws Exception {
        mockMvc.perform(get(BLOCCHI))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BLOCCHI).header("Authorization", "Bearer " + tokenCliente()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("lo STAFF puo' bloccare, non solo l'ADMIN")
    void blocco_daStaff_risponde201() throws Exception {
        // given: chiudere la stanza col bagno rotto e' una decisione di turno, e chi sta
        // al banco la deve poter prendere senza cercare un amministratore. E' la
        // differenza con tariffe e aliquote, dove scrivere e' solo degli ADMIN
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        LocalDate inizio = LocalDate.now().plusDays(110);

        mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + tokenStaff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new BloccoRequest()
                                .tipologiaCameraId(tipologia)
                                .dataInizio(inizio)
                                .dataFine(inizio.plusDays(2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.origine").value("MANUALE"));
    }

    @Test
    @DisplayName("l'elenco regge ogni combinazione di filtri, valorizzati e no")
    void elenco_conOgniCombinazioneDiFiltri_risponde200() throws Exception {
        // given
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long camera = creaCamera(admin, tipologia);
        LocalDate inizio = LocalDate.now().plusDays(120);
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .cameraId(camera)
                .dataInizio(inizio)
                .dataFine(inizio.plusDays(3)));

        // when / then: sette chiamate che sembrano ridondanti e non lo sono. Il difetto
        // che questo test esiste per prendere non si vede chiamando l'elenco in un modo
        // solo: **un parametro nullo e uno valorizzato producono piani diversi**, e la
        // query falliva con gli uni e passava con gli altri. La prima stesura della suite
        // chiamava solo la forma senza filtri, e infatti il 500 l'ha trovato curl.
        for (String query : new String[]{
                "",
                "?da=" + inizio,
                "?a=" + inizio.plusDays(10),
                "?da=" + inizio + "&a=" + inizio.plusDays(10),
                "?tipologiaCameraId=" + tipologia,
                "?cameraId=" + camera,
                "?tipologiaCameraId=" + tipologia + "&cameraId=" + camera
                        + "&da=" + inizio + "&a=" + inizio.plusDays(10)}) {
            mockMvc.perform(get(BLOCCHI + query)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("il filtro sul periodo prende le sovrapposizioni, non i contenimenti")
    void elenco_conPeriodo_prendeLeSovrapposizioni() throws Exception {
        // given: un blocco che comincia prima della finestra cercata e finisce dentro
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        LocalDate finestra = LocalDate.now().plusDays(140);
        creaBlocco(admin, dati -> dati
                .tipologiaCameraId(tipologia)
                .dataInizio(finestra.minusDays(3))
                .dataFine(finestra.plusDays(1)));

        // when / then: deve comparire. Cercando i blocchi *contenuti* nel periodo lo si
        // nasconderebbe proprio a chi sta cercando di capire perche' non puo' vendere
        mockMvc.perform(get(BLOCCHI)
                        .param("tipologiaCameraId", String.valueOf(tipologia))
                        .param("da", finestra.toString())
                        .param("a", finestra.plusDays(5).toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---------------------------------------------------------------- supporto

    /**
     * Quante camere la ricerca dichiara libere per quella tipologia in quelle notti.
     *
     * <p><b>Scorre tutte le pagine, e non e' pignoleria.</b> La ricerca di disponibilita'
     * restituisce <i>tutte</i> le tipologie dell'albergo, venti per pagina, e la suite ne
     * crea qualche centinaio: la prima stesura di questo metodo guardava solo la prima
     * pagina, passava sulla mia macchina e falliva in CI con "la tipologia 193 non compare
     * nella disponibilita'". E' il difetto che la CI esiste per prendere — cio' che
     * funziona solo dove e' stato scritto — e la differenza era l'ordine con cui i test
     * erano girati, cioe' quante tipologie esistevano prima di questa.
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

            var corpo = objectMapper.readTree(risposta);
            for (var riga : corpo.path("data")) {
                if (riga.path("tipologia").path("id").asLong() == tipologia) {
                    org.assertj.core.api.Assertions.assertThat(
                                    riga.path("camereDisponibili").asInt())
                            .as("camere disponibili della tipologia %d", tipologia)
                            .isEqualTo(atteso);
                    return;
                }
            }
            if (pagina + 1 >= corpo.path("page").path("totalPages").asInt()) {
                throw new AssertionError(
                        "la tipologia " + tipologia + " non compare in nessuna pagina della disponibilita'");
            }
        }
    }

    private long creaBlocco(String token, java.util.function.UnaryOperator<BloccoRequest> costruisci)
            throws Exception {
        String risposta = mockMvc.perform(post(BLOCCHI)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(costruisci.apply(new BloccoRequest()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
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

    private long prenotazioneConfermata(String tokenAdmin, long tipologia,
                                        LocalDate arrivo, LocalDate partenza) throws Exception {
        String cliente = tokenCliente();
        PrenotazioneRequest richiesta = dati.prenotazioneRequest(tipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(partenza)
                .numeroOspiti(1);

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + cliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(risposta).path("data").path("id").asLong();

        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/conferma")
                        .header("Authorization", "Bearer " + cliente))
                .andExpect(status().isOk());
        return id;
    }

    /** Il check-in pretende gli ospiti al completo: qui ne serve uno. */
    private void registraOspiti(String token, long prenotazione) throws Exception {
        mockMvc.perform(post(PRENOTAZIONI + "/" + prenotazione + "/ospiti")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.ospiteRequest())))
                .andExpect(status().isCreated());
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
