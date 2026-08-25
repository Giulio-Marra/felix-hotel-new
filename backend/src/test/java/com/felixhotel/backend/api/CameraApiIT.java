package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dell'inventario delle camere fisiche.
 *
 * <p>E' il primo IT del progetto che esercita cose mai provate prima:
 * <ul>
 *   <li>una risorsa <b>senza nessuna lettura pubblica</b>: tutti i suoi path
 *       ricadono nel default {@code anyRequest().authenticated()}, e un anonimo
 *       si ferma con 401 gia' sulla GET;</li>
 *   <li><b>tre livelli di permesso invece di due</b>, cioe' il primo
 *       {@code hasAnyRole} del progetto e la prima volta che il ruolo STAFF
 *       protegge qualcosa: legge e cambia stato, ma non crea ne' cancella;</li>
 *   <li><b>filtri</b> su un endpoint di lista, con la differenza fra un filtro
 *       che non trova niente (pagina vuota) e un filtro che non ha senso (400);</li>
 *   <li>un <b>enum</b> che attraversa spec, entity e colonna con un CHECK,
 *       cioe' tre elenchi che devono restare allineati.</li>
 * </ul>
 */
@DisplayName("API dell'inventario camere")
class CameraApiIT extends IntegrationTestBase {

    private static final String CAMERE = "/api/camere";
    private static final String TIPOLOGIE = "/api/tipologie-camera";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    /**
     * Serve a inserire una prenotazione che referenzi una camera, per provare che
     * eliminarla dia 409. Si passa da SQL diretto perche' l'entity
     * {@code Prenotazione} non esiste ancora in Java; la tabella e la sua chiave
     * esterna pero' ci sono dal V1, ed e' il vincolo che si vuole verificare.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaAdmin(email);
        return ottieniToken(email);
    }

    /** Token del personale non amministratore: il ruolo che qui, per la prima volta, conta. */
    private String tokenStaff() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaStaff(email);
        return ottieniToken(email);
    }

    /** Token di un cliente registrato dal frontoffice (ruolo USER). */
    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        registraAccount(cliente);
        return ottieniToken(cliente.getEmail());
    }

    /** Crea una tipologia dall'endpoint vero e ne restituisce l'id: serve a ogni camera. */
    private long creaTipologia(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    /** Crea una camera dall'endpoint vero e ne restituisce l'id. */
    private long creaCamera(String tokenAdmin, CameraRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(CAMERE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    @Nested
    @DisplayName("GET /api/camere")
    class Elenco {

        @Test
        @DisplayName("da anonimo risponde 401")
        void elenco_daAnonimo_risponde401() throws Exception {
            // when: si prova a leggere l'inventario senza token
            mockMvc.perform(get(CAMERE))
                    // then: 401. E' la differenza col catalogo: qui non c'e' niente di
                    // pubblico, quindi nessun path compare fra i permitAll e tutti
                    // ricadono nel default autenticato
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void elenco_conTokenUtente_risponde403() throws Exception {
            // when: un cliente autenticato prova a vedere le stanze
            mockMvc.perform(get(CAMERE).header("Authorization", "Bearer " + tokenCliente()))
                    // then: 403. E' il primo endpoint del progetto in cui un cliente si
                    // sente negare una **lettura**: prenota una tipologia, non la stanza,
                    // e sapere quali camere sono in manutenzione non gli serve a niente
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("da STAFF risponde 200 con la busta paginata e la tipologia in sintesi")
        void elenco_daStaff_risponde200() throws Exception {
            // given: una camera in inventario
            String tokenAdmin = tokenAdmin();
            long idTipologia = creaTipologia(tokenAdmin);
            creaCamera(tokenAdmin, dati.cameraRequest(idTipologia));

            // when: la legge un membro del personale, non un amministratore
            mockMvc.perform(get(CAMERE)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .param("size", "100"))
                    // then: 200 con la busta paginata completa. La tipologia c'e' ma
                    // ridotta a id e nome: un elenco di camere non deve trascinarsi
                    // dietro prezzo, descrizione e dotazioni per ogni riga
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.page.pageNumber").value(0))
                    .andExpect(jsonPath("$.data[0].numero").exists())
                    .andExpect(jsonPath("$.data[0].stato").exists())
                    .andExpect(jsonPath("$.data[0].tipologia.nome").exists())
                    .andExpect(jsonPath("$.data[0].tipologia.prezzoNotte").doesNotExist())
                    .andExpect(jsonPath("$.data[0].tipologia.dotazioni").doesNotExist());
        }

        @Test
        @DisplayName("filtra per tipologia e per stato, anche insieme")
        void elenco_conFiltri_restituisceSoloLeCamereGiuste() throws Exception {
            // given: due tipologie, e tre camere distribuite fra loro e fra due stati
            String token = tokenAdmin();
            long tipologiaA = creaTipologia(token);
            long tipologiaB = creaTipologia(token);

            long liberaA = creaCamera(token, dati.cameraRequest(tipologiaA));
            long manutenzioneA = creaCamera(token,
                    dati.cameraRequest(tipologiaA).stato(StatoCamera.MANUTENZIONE));
            long liberaB = creaCamera(token, dati.cameraRequest(tipologiaB));

            // when/then: filtrando per tipologia si vedono le due di A e non quella di B
            assertThat(idsFiltrati(token, "tipologiaCameraId", String.valueOf(tipologiaA)))
                    .containsExactlyInAnyOrder(liberaA, manutenzioneA)
                    .doesNotContain(liberaB);

            // when/then: filtrando per stato si vede solo quella in manutenzione — fra
            // le camere di questo test, perche' l'inventario e' condiviso dalla suite
            assertThat(idsFiltrati(token, "stato", "MANUTENZIONE"))
                    .contains(manutenzioneA)
                    .doesNotContain(liberaA, liberaB);

            // when/then: e i due filtri si combinano, invece di sostituirsi
            assertThat(idsCombinati(token, tipologiaA, "LIBERA"))
                    .containsExactly(liberaA)
                    .doesNotContain(manutenzioneA, liberaB);
        }

        @Test
        @DisplayName("con una tipologia inesistente restituisce una pagina vuota e non un errore")
        void elenco_conTipologiaInesistente_rispondePaginaVuota() throws Exception {
            // when: si filtra per una tipologia che non esiste
            mockMvc.perform(get(CAMERE)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .param("tipologiaCameraId", "999999999"))
                    // then: 200 con zero elementi. "Non ne ho di quel tipo" e' una
                    // risposta legittima a una domanda ben posta: un 404 direbbe che
                    // l'elenco non esiste, che e' falso
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }

        @Test
        @DisplayName("con uno stato che non esiste risponde 400")
        void elenco_conStatoNonValido_risponde400() throws Exception {
            // when: si filtra per uno stato fuori dall'enum
            mockMvc.perform(get(CAMERE)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .param("stato", "DA_SVERNICIARE"))
                    // then: 400 e non una pagina vuota. La differenza col caso sopra e'
                    // che li' la domanda era sensata e la risposta era "nessuna"; qui la
                    // domanda non e' formulabile — quel valore non esiste
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        /** Gli id delle camere restituite filtrando su un solo parametro. */
        private List<Long> idsFiltrati(String token, String parametro, String valore)
                throws Exception {
            String risposta = mockMvc.perform(get(CAMERE)
                            .header("Authorization", "Bearer " + token)
                            .param(parametro, valore)
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            return estraiIds(risposta);
        }

        /** Gli id delle camere restituite filtrando su tipologia e stato insieme. */
        private List<Long> idsCombinati(String token, long idTipologia, String stato)
                throws Exception {
            String risposta = mockMvc.perform(get(CAMERE)
                            .header("Authorization", "Bearer " + token)
                            .param("tipologiaCameraId", String.valueOf(idTipologia))
                            .param("stato", stato)
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            return estraiIds(risposta);
        }

        private List<Long> estraiIds(String risposta) {
            List<Long> ids = new ArrayList<>();
            for (JsonNode elemento : objectMapper.readTree(risposta).path("data")) {
                ids.add(elemento.path("id").asLong());
            }
            return ids;
        }
    }

    @Nested
    @DisplayName("POST /api/camere")
    class Creazione {

        @Test
        @DisplayName("da STAFF risponde 403")
        void creazione_daStaff_risponde403() throws Exception {
            // given: una tipologia a cui appoggiare la camera
            long idTipologia = creaTipologia(tokenAdmin());

            // when: prova a creare una camera chi puo' solo leggerne lo stato
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia))))
                    // then: 403. E' la riga che separa i due ruoli: aprire una stanza
                    // nuova cambia la struttura dell'albergo, non il suo stato. Senza
                    // questo test, un hasAnyRole scritto per sbaglio anche qui passerebbe
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("da ADMIN crea la camera LIBERA e risponde 201")
        void creazione_daAdmin_risponde201() throws Exception {
            // given: un amministratore e una tipologia
            String token = tokenAdmin();
            long idTipologia = creaTipologia(token);
            CameraRequest richiesta = dati.cameraRequest(idTipologia);

            // when: la crea senza dire niente sullo stato
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201, e la camera nasce LIBERA
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.numero").value(richiesta.getNumero()))
                    .andExpect(jsonPath("$.data.stato").value("LIBERA"))
                    .andExpect(jsonPath("$.data.tipologia.id").value(idTipologia));
        }

        @Test
        @DisplayName("con lo stesso numero a maiuscole diverse risponde 409")
        void creazione_conNumeroDuplicato_risponde409() throws Exception {
            // given: una camera "12A" gia' in inventario
            String token = tokenAdmin();
            long idTipologia = creaTipologia(token);
            String numero = dati.numeroCameraUnivoco() + "A";
            creaCamera(token, dati.cameraRequest(idTipologia).numero(numero));

            // when: se ne crea un'altra col numero tutto minuscolo
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia)
                                    .numero(numero.toLowerCase()))))
                    // then: 409. E' il test che protegge la V4: col solo UNIQUE di colonna
                    // ereditato dal V1 sarebbero entrate due righe per la stessa stanza,
                    // e con le prenotazioni diventerebbe la stessa camera prenotata due volte
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("con una tipologia inesistente risponde 400 e non 404")
        void creazione_conTipologiaInesistente_risponde400() throws Exception {
            // when: si crea una camera che dice di appartenere a una tipologia che non c'e'
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(999999999L))))
                    // then: 400, col numero colpevole nel messaggio. Il 404 di questi
                    // endpoint significa "questa camera non esiste": usarlo anche qui
                    // renderebbe indistinguibili due errori con due rimedi diversi
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(
                            containsString("999999999")));
        }

        @Test
        @DisplayName("con un piano negativo risponde 400 elencando il campo")
        void creazione_conPianoNegativo_risponde400() throws Exception {
            // given: un piano sotto lo zero, che lo spec non ammette
            long idTipologia = creaTipologia(tokenAdmin());

            // when
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia).piano(-1))))
                    // then: 400 con la mappa campo -> messaggio. Lo zero e' valido (piano
                    // terra), il -1 no: gli interrati non sono rappresentabili ed e' una
                    // scelta scritta nel contratto, non una svista
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.piano").exists());
        }
    }

    @Nested
    @DisplayName("PUT /api/camere/{id}/stato")
    class CambioStato {

        @Test
        @DisplayName("da STAFF cambia lo stato e risponde 200")
        void stato_daStaff_risponde200() throws Exception {
            // given: una camera libera
            String tokenAdmin = tokenAdmin();
            long idTipologia = creaTipologia(tokenAdmin);
            long idCamera = creaCamera(tokenAdmin, dati.cameraRequest(idTipologia));

            // when: il personale la segna da pulire
            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.PULIZIA))))
                    // then: 200. E' l'operazione di ogni turno: chiederle i privilegi di
                    // amministratore vorrebbe dire che non la fa nessuno
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.stato").value("PULIZIA"))
                    .andExpect(jsonPath("$.data.id").value(idCamera));
        }

        @Test
        @DisplayName("da cliente risponde 403")
        void stato_daCliente_risponde403() throws Exception {
            // given: una camera qualsiasi
            String tokenAdmin = tokenAdmin();
            long idCamera = creaCamera(tokenAdmin, dati.cameraRequest(creaTipologia(tokenAdmin)));

            // when: prova a cambiarne lo stato un cliente
            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + tokenCliente())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.LIBERA))))
                    // then: 403 — il permesso si ferma al personale
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ripetuto due volte con lo stesso stato resta 200")
        void stato_ripetuto_restaOk() throws Exception {
            // given: una camera gia' portata in manutenzione
            String tokenAdmin = tokenAdmin();
            long idCamera = creaCamera(tokenAdmin, dati.cameraRequest(creaTipologia(tokenAdmin)));
            String tokenStaff = tokenStaff();

            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + tokenStaff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.MANUTENZIONE))))
                    .andExpect(status().isOk());

            // when: si ripete la stessa richiesta
            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + tokenStaff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.MANUTENZIONE))))
                    // then: ancora 200. L'idempotenza e' voluta: chi ripete la chiamata
                    // perche' non era sicuro che la prima fosse arrivata non sta
                    // sbagliando niente, e un 409 lo costringerebbe a leggere lo stato
                    // prima di ogni scrittura
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stato").value("MANUTENZIONE"));
        }

        @Test
        @DisplayName("accetta tutti e quattro gli stati dell'enum")
        void stato_conTuttiGliStati_liAccettaTutti() throws Exception {
            // given: una camera su cui provarli tutti
            String tokenAdmin = tokenAdmin();
            long idCamera = creaCamera(tokenAdmin, dati.cameraRequest(creaTipologia(tokenAdmin)));
            String tokenStaff = tokenStaff();

            // when/then: lo stato attraversa spec, entity e CHECK della colonna — tre
            // elenchi che devono restare allineati. Provarne uno solo lascerebbe
            // scoperti gli altri tre, e a dirlo sarebbe Postgres a runtime
            for (StatoCamera stato : StatoCamera.values()) {
                mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                                .header("Authorization", "Bearer " + tokenStaff)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(dati.cameraStatoRequest(stato))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.stato").value(stato.getValue()));
            }
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void stato_conIdInesistente_risponde404() throws Exception {
            // when: si cambia lo stato di una camera che non c'e'
            mockMvc.perform(put(CAMERE + "/999999999/stato")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.LIBERA))))
                    // then: 404 — qui la risorsa dell'URL manca davvero
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("PUT e DELETE /api/camere/{id}")
    class ModificaEdEliminazione {

        @Test
        @DisplayName("la PUT senza stato riporta la camera a LIBERA")
        void aggiornamento_senzaStato_riportaALibera() throws Exception {
            // given: una camera messa in manutenzione
            String token = tokenAdmin();
            long idTipologia = creaTipologia(token);
            CameraRequest originale = dati.cameraRequest(idTipologia);
            long idCamera = creaCamera(token, originale);

            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(StatoCamera.MANUTENZIONE))))
                    .andExpect(status().isOk());

            // when: si aggiorna il piano senza dire niente sullo stato
            mockMvc.perform(put(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia)
                                    .numero(originale.getNumero())
                                    .piano(3))))
                    // then: il piano cambia e lo stato torna LIBERA. E' il significato
                    // della PUT, ed e' anche il motivo per cui il lavoro di tutti i giorni
                    // passa dall'endpoint dedicato: da qui una dimenticanza rimette in
                    // servizio una stanza guasta
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.piano").value(3))
                    .andExpect(jsonPath("$.data.stato").value("LIBERA"));
        }

        @Test
        @DisplayName("la DELETE da STAFF risponde 403 e la camera resta")
        void eliminazione_daStaff_risponde403() throws Exception {
            // given: una camera in inventario
            String tokenAdmin = tokenAdmin();
            long idCamera = creaCamera(tokenAdmin, dati.cameraRequest(creaTipologia(tokenAdmin)));

            // when: prova a cancellarla il personale
            mockMvc.perform(delete(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + tokenStaff()))
                    // then: 403
                    .andExpect(status().isForbidden());

            // then: e la camera e' ancora li'. Verificare solo lo status direbbe che la
            // risposta e' giusta, non che la cancellazione non e' avvenuta lo stesso
            mockMvc.perform(get(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + tokenAdmin))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("la DELETE da ADMIN elimina la camera e risponde 200")
        void eliminazione_daAdmin_risponde200() throws Exception {
            // given: una camera senza prenotazioni
            String token = tokenAdmin();
            long idCamera = creaCamera(token, dati.cameraRequest(creaTipologia(token)));

            // when: l'amministratore la elimina
            mockMvc.perform(delete(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + token))
                    // then: 200 con 'data' null
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: e non si trova piu'
            mockMvc.perform(get(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("la DELETE di una camera con prenotazioni risponde 409")
        void eliminazione_conPrenotazioni_risponde409() throws Exception {
            // given: una camera a cui e' agganciata una prenotazione. La riga si scrive
            // in SQL perche' l'entity Prenotazione non esiste ancora in Java; la tabella
            // e la sua chiave esterna ci sono dal V1, ed e' quel vincolo che si prova
            String token = tokenAdmin();
            long idTipologia = creaTipologia(token);
            long idCamera = creaCamera(token, dati.cameraRequest(idTipologia));

            RegisterRequest cliente = dati.registerRequest();
            registraAccount(cliente);
            Long idUtente = jdbcTemplate.queryForObject(
                    "SELECT id FROM utente WHERE email = ?", Long.class, cliente.getEmail());

            jdbcTemplate.update("""
                            INSERT INTO prenotazione
                              (utente_id, tipologia_camera_id, camera_id, data_check_in,
                               data_check_out, numero_ospiti, canale, importo_totale)
                            VALUES (?, ?, ?, DATE '2027-01-10', DATE '2027-01-12', 2, 'ONLINE', 240.00)
                            """,
                    idUtente, idTipologia, idCamera);

            // when: si prova a eliminare la camera
            mockMvc.perform(delete(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + token))
                    // then: 409 e non 500. La chiave esterna e' senza cascata di proposito:
                    // cancellare a catena porterebbe via lo storico delle prenotazioni. Una
                    // stanza fuori uso si segna MANUTENZIONE, non si cancella
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").exists());

            // then: e la camera e' ancora al suo posto
            mockMvc.perform(get(CAMERE + "/" + idCamera)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }
}
