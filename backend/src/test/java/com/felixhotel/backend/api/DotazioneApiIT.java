package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.backend.support.StaffDiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dell'elenco delle dotazioni: richiesta HTTP reale (via
 * MockMvc) fino a Postgres e ritorno.
 *
 * <p>La forma degli endpoint e' la stessa del catalogo delle tipologie, quindi
 * qui non si ripete l'intero campionario di validazione gia' esercitato da
 * {@code TipologiaCameraApiIT} (id non numerico, size fuori scala, mappa dei
 * campi rifiutati): quei percorsi sono condivisi e provati una volta. Si prova
 * invece cio' che di questa risorsa e' <b>diverso</b> o <b>suo</b>:
 * <ul>
 *   <li>che <b>ognuno</b> dei tre endpoint di scrittura rifiuti chi non e'
 *       ADMIN — un {@code @PreAuthorize} dimenticato su uno solo dei tre
 *       sarebbe un buco che nessun altro test vedrebbe;</li>
 *   <li>che il nome sia unico <b>a meno delle maiuscole</b>, cioe' che la V3
 *       abbia davvero sostituito lo UNIQUE case-sensitive del V1;</li>
 *   <li>che eliminare una dotazione <b>assegnata a una tipologia</b> riesca,
 *       portandosi via il legame — l'opposto di quello che fa il catalogo, ed
 *       e' una scelta che va protetta da un test proprio perche' e' una
 *       differenza voluta e non un'omissione.</li>
 * </ul>
 */
@DisplayName("API delle dotazioni")
class DotazioneApiIT extends IntegrationTestBase {

    private static final String DOTAZIONI = "/api/dotazioni";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private StaffDiTest staffDiTest;

    /**
     * Serve a leggere e a riempire la tabella di legame
     * {@code tipologia_camera_dotazione} senza passare da un endpoint che (in
     * questo giro) non esiste ancora: qui interessa il comportamento della
     * chiave esterna, che c'e' dal V1 ed e' esattamente cio' che si vuole
     * verificare.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Token di un amministratore, l'unico ruolo che puo' scrivere nell'elenco. */
    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        staffDiTest.creaAdmin(email);
        return ottieniToken(email);
    }

    /** Token di un cliente registrato dal frontoffice (ruolo USER). */
    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        registraAccount(cliente);
        return ottieniToken(cliente.getEmail());
    }

    /** Crea una dotazione passando dall'endpoint vero e ne restituisce l'id. */
    private long creaDotazione(String token, DotazioneRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(DOTAZIONI)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    @Nested
    @DisplayName("GET /api/dotazioni")
    class Elenco {

        @Test
        @DisplayName("da anonimo risponde 200 con la busta paginata completa")
        void elenco_daAnonimo_rispondeConBustaPaginata() throws Exception {
            // given: almeno una dotazione in elenco
            creaDotazione(tokenAdmin(), dati.dotazioneRequest());

            // when: l'elenco si legge senza autenticarsi, come farebbe un visitatore
            mockMvc.perform(get(DOTAZIONI)
                            .param("page", "0")
                            .param("size", "1"))
                    // then: 200 e busta paginata completa in ogni sua parte
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].nome").exists())
                    .andExpect(jsonPath("$.page.pageNumber").value(0))
                    .andExpect(jsonPath("$.page.pageSize").value(1))
                    .andExpect(jsonPath("$.page.totalElements").isNumber())
                    .andExpect(jsonPath("$.page.totalPages").isNumber());
        }
    }

    @Nested
    @DisplayName("GET /api/dotazioni/{id}")
    class Dettaglio {

        @Test
        @DisplayName("da anonimo risponde 200 con la dotazione richiesta")
        void dettaglio_daAnonimo_risponde200() throws Exception {
            // given: una dotazione in elenco
            DotazioneRequest richiesta = dati.dotazioneRequest();
            long id = creaDotazione(tokenAdmin(), richiesta);

            // when: si legge senza autenticarsi
            mockMvc.perform(get(DOTAZIONI + "/" + id))
                    // then: 200 e i dati salvati
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()))
                    .andExpect(jsonPath("$.data.descrizione").value(richiesta.getDescrizione()));
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void dettaglio_conIdInesistente_risponde404() throws Exception {
            // when: si chiede una dotazione che non c'e'
            mockMvc.perform(get(DOTAZIONI + "/999999999"))
                    // then: 404 nella busta standard, 'data' vuoto
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /api/dotazioni")
    class Creazione {

        @Test
        @DisplayName("da anonimo risponde 401 e non 403")
        void creazione_daAnonimo_risponde401() throws Exception {
            // when: si prova a scrivere senza token
            mockMvc.perform(post(DOTAZIONI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest())))
                    // then: 401 — chi non si e' autenticato deve sentirsi dire di farlo,
                    // non che i suoi permessi non bastano
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void creazione_conTokenUtente_risponde403() throws Exception {
            // given: un cliente registrato dal frontoffice (ruolo USER)
            String token = tokenCliente();

            // when: prova a inserire una dotazione
            mockMvc.perform(post(DOTAZIONI)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest())))
                    // then: 403 — l'identita' e' nota e valida, e' il ruolo a non bastare
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("da ADMIN crea la dotazione e risponde 201")
        void creazione_daAdmin_risponde201() throws Exception {
            // given: un amministratore e una dotazione valida
            String token = tokenAdmin();
            DotazioneRequest richiesta = dati.dotazioneRequest();

            // when: la crea
            mockMvc.perform(post(DOTAZIONI)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201 con la risorsa appena creata, id compreso
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()));
        }

        @Test
        @DisplayName("senza descrizione risponde comunque 201")
        void creazione_senzaDescrizione_risponde201() throws Exception {
            // given: solo il nome, che e' l'unico campo obbligatorio dello schema
            DotazioneRequest richiesta = dati.dotazioneRequest().descrizione(null);

            // when: la crea
            mockMvc.perform(post(DOTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201. La descrizione e' facoltativa nel contratto e la colonna
                    // e' nullable: se qui arrivasse un 400 vorrebbe dire che i due si sono
                    // disallineati
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()))
                    .andExpect(jsonPath("$.data.descrizione").doesNotExist());
        }

        @Test
        @DisplayName("con un nome gia' in uso, anche cambiando le maiuscole, risponde 409")
        void creazione_conNomeDuplicato_risponde409() throws Exception {
            // given: una dotazione gia' in elenco
            String token = tokenAdmin();
            DotazioneRequest prima = dati.dotazioneRequest();
            creaDotazione(token, prima);

            // when: se ne crea un'altra con lo stesso nome tutto minuscolo
            mockMvc.perform(post(DOTAZIONI)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest()
                                    .nome(prima.getNome().toLowerCase()))))
                    // then: 409. E' il test che protegge la V3: col solo UNIQUE di colonna
                    // ereditato dal V1 questo duplicato sarebbe entrato, e l'elenco a scelta
                    // multipla del backoffice avrebbe mostrato due voci identiche a leggersi
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con nome e descrizione piu' lunghi delle colonne risponde 400 e non 500")
        void creazione_conCampiTroppoLunghi_risponde400() throws Exception {
            // given: entrambi i limiti del DDL superati
            DotazioneRequest richiesta = dati.dotazioneRequest()
                    .nome("N".repeat(101))
                    .descrizione("D".repeat(256));

            // when: si prova a crearla
            mockMvc.perform(post(DOTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400 con la mappa campo -> messaggio. Senza i maxLength nello
                    // spec le stringhe arriverebbero fino a Postgres, che le rifiuterebbe,
                    // e chi chiama vedrebbe un 500 per un errore suo
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.nome").exists())
                    .andExpect(jsonPath("$.data.descrizione").exists());
        }
    }

    @Nested
    @DisplayName("PUT /api/dotazioni/{id}")
    class Aggiornamento {

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void aggiornamento_conTokenUtente_risponde403() throws Exception {
            // given: una dotazione in elenco e un cliente qualunque
            long id = creaDotazione(tokenAdmin(), dati.dotazioneRequest());
            String tokenCliente = tokenCliente();

            // when: il cliente prova a modificarla
            mockMvc.perform(put(DOTAZIONI + "/" + id)
                            .header("Authorization", "Bearer " + tokenCliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest())))
                    // then: 403. Il caso e' ripetuto per ogni verbo di scrittura di
                    // proposito: la protezione e' un'annotazione per metodo, quindi
                    // dimenticarla su uno solo dei tre non la farebbe notare altrove
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("da ADMIN aggiorna i campi e risponde 200")
        void aggiornamento_daAdmin_risponde200() throws Exception {
            // given: una dotazione gia' in elenco
            String token = tokenAdmin();
            DotazioneRequest originale = dati.dotazioneRequest();
            long id = creaDotazione(token, originale);

            // when: se ne cambia la descrizione lasciando lo stesso nome
            DotazioneRequest modificata = dati.dotazioneRequest()
                    .nome(originale.getNome())
                    .descrizione("Connessione in fibra, gratuita");

            mockMvc.perform(put(DOTAZIONI + "/" + id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(modificata)))
                    // then: 200 e il nuovo valore. Riconfermare il proprio nome non deve
                    // dare 409: il controllo di unicita' esclude la dotazione stessa
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.descrizione").value("Connessione in fibra, gratuita"));
        }

        @Test
        @DisplayName("prendendo il nome di un'altra dotazione risponde 409")
        void aggiornamento_conNomeDiUnAltra_risponde409() throws Exception {
            // given: due dotazioni distinte in elenco
            String token = tokenAdmin();
            DotazioneRequest prima = dati.dotazioneRequest();
            creaDotazione(token, prima);
            long idSeconda = creaDotazione(token, dati.dotazioneRequest());

            // when: si prova a rinominare la seconda col nome della prima
            mockMvc.perform(put(DOTAZIONI + "/" + idSeconda)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest().nome(prima.getNome()))))
                    // then: 409. E' il rovescio del test precedente: li' si verifica che il
                    // proprio nome non dia conflitto, qui che quello di un'altra lo dia —
                    // senza entrambi, un controllo rotto in un verso solo passerebbe
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void aggiornamento_conIdInesistente_risponde404() throws Exception {
            // when: si aggiorna una dotazione che non c'e'
            mockMvc.perform(put(DOTAZIONI + "/999999999")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.dotazioneRequest())))
                    // then: 404 e non 409 — l'ordine dei controlli nel service conta, e
                    // questo e' il test che lo tiene fermo
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("DELETE /api/dotazioni/{id}")
    class Eliminazione {

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void eliminazione_conTokenUtente_risponde403() throws Exception {
            // given: una dotazione in elenco e un cliente qualunque
            long id = creaDotazione(tokenAdmin(), dati.dotazioneRequest());
            String tokenCliente = tokenCliente();

            // when: il cliente prova a cancellarla
            mockMvc.perform(delete(DOTAZIONI + "/" + id)
                            .header("Authorization", "Bearer " + tokenCliente))
                    // then: 403, e la dotazione e' ancora li'
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            mockMvc.perform(get(DOTAZIONI + "/" + id)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("da ADMIN elimina la dotazione e risponde 200")
        void eliminazione_daAdmin_risponde200() throws Exception {
            // given: una dotazione in elenco
            String token = tokenAdmin();
            long id = creaDotazione(token, dati.dotazioneRequest());

            // when: l'amministratore la elimina
            mockMvc.perform(delete(DOTAZIONI + "/" + id)
                            .header("Authorization", "Bearer " + token))
                    // then: 200 con 'data' null — non c'e' piu' niente da restituire. Si usa
                    // 200 e non 204 perche' la busta standard vale per ogni endpoint
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: e non si trova piu'
            mockMvc.perform(get(DOTAZIONI + "/" + id)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void eliminazione_conIdInesistente_risponde404() throws Exception {
            // when: si elimina una dotazione che non c'e'
            mockMvc.perform(delete(DOTAZIONI + "/999999999")
                            .header("Authorization", "Bearer " + tokenAdmin()))
                    // then: 404
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("assegnata a una tipologia riesce comunque e si porta via il legame")
        void eliminazione_diDotazioneAssegnata_riesceEliminandoIlLegame() throws Exception {
            // given: una dotazione assegnata a una tipologia di camera. Il legame si
            // scrive in SQL perche' l'endpoint che lo gestisce non esiste ancora; la
            // tabella e la sua chiave esterna pero' ci sono dal V1, ed e' il loro
            // comportamento che si sta verificando
            String token = tokenAdmin();
            long idDotazione = creaDotazione(token, dati.dotazioneRequest());
            long idTipologia = creaTipologiaPerLegame(token);

            jdbcTemplate.update(
                    "INSERT INTO tipologia_camera_dotazione (tipologia_camera_id, dotazione_id) VALUES (?, ?)",
                    idTipologia, idDotazione);

            // when: l'amministratore elimina la dotazione
            mockMvc.perform(delete(DOTAZIONI + "/" + idDotazione)
                            .header("Authorization", "Bearer " + token))
                    // then: 200 e non 409. E' la differenza voluta rispetto al catalogo:
                    // una tipologia usata si porterebbe via lo storico delle prenotazioni,
                    // una dotazione tolta dall'elenco e' solo un servizio che non si offre
                    // piu', e lasciarla appesa alle schede sarebbe l'unico esito sbagliato
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));

            // then: il legame se n'e' andato con lei (ON DELETE CASCADE), e la tipologia
            // e' rimasta al suo posto
            Integer legamiResidui = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tipologia_camera_dotazione WHERE dotazione_id = ?",
                    Integer.class, idDotazione);
            assertThat(legamiResidui).isZero();

            mockMvc.perform(get("/api/tipologie-camera/" + idTipologia)).andExpect(status().isOk());
        }

        /** Tipologia di camera creata dall'endpoint vero, per averne una a cui legare la dotazione. */
        private long creaTipologiaPerLegame(String token) throws Exception {
            String risposta = mockMvc.perform(post("/api/tipologie-camera")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            return objectMapper.readTree(risposta).path("data").path("id").asLong();
        }
    }
}
