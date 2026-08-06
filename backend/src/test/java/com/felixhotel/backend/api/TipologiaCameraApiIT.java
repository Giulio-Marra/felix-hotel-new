package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.backend.support.StaffDiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione del catalogo delle tipologie di camera: richiesta HTTP
 * reale (via MockMvc) fino a Postgres e ritorno.
 *
 * <p>E' il primo IT del progetto che esercita tre cose mai provate prima:
 * <ul>
 *   <li>la <b>busta paginata</b> ({@code ApiBaseResponsePaginated} +
 *       {@code toPaginatedResponse}), scritta due sessioni fa e finora non usata
 *       da nessun endpoint;</li>
 *   <li>{@code @PreAuthorize} su un Controller di <b>produzione</b>, cioe' la
 *       differenza fra "autenticato" (401 se non lo sei) e "autorizzato" (403 se
 *       lo sei ma non basta);</li>
 *   <li>un endpoint che risponde <b>404</b> e uno che risponde <b>409</b> per un
 *       vincolo del database e non per un controllo nostro.</li>
 * </ul>
 *
 * <p>Come gli altri IT, ogni test verifica <b>sia lo status HTTP sia la forma
 * della busta</b>: il codice da solo lascerebbe scoperta meta' della convenzione.
 */
@DisplayName("API del catalogo tipologie di camera")
class TipologiaCameraApiIT extends IntegrationTestBase {

    private static final String TIPOLOGIE = "/api/tipologie-camera";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private StaffDiTest staffDiTest;

    /**
     * Serve a inserire una camera che referenzi una tipologia, per provare che
     * eliminarla dia 409. Si passa da SQL diretto perche' l'entity {@code Camera}
     * non esiste ancora in Java: arrivera' col branch dell'inventario. La tabella
     * pero' c'e' gia' dal V1, e la chiave esterna con lei — che e' esattamente il
     * vincolo che si vuole verificare.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Token di un amministratore, l'unico ruolo che puo' scrivere nel catalogo. */
    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        staffDiTest.creaAdmin(email);
        return ottieniToken(email);
    }

    /** Crea una tipologia passando dall'endpoint vero e ne restituisce l'id. */
    private long creaTipologia(String token, TipologiaCameraRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
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
    @DisplayName("GET /api/tipologie-camera")
    class Elenco {

        @Test
        @DisplayName("da anonimo risponde 200 con la busta paginata completa")
        void elenco_daAnonimo_rispondeConBustaPaginata() throws Exception {
            // given: almeno una tipologia in catalogo
            creaTipologia(tokenAdmin(), dati.tipologiaCameraRequest());

            // when: il catalogo si legge senza autenticarsi, come farebbe un visitatore
            mockMvc.perform(get(TIPOLOGIE)
                            .param("page", "0")
                            .param("size", "1"))
                    // then: 200 e busta paginata — e' la prima volta che 'page' viene
                    // riempito davvero da un endpoint, quindi si controllano tutti e
                    // quattro i metadati e non solo la presenza dell'oggetto
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].nome").exists())
                    .andExpect(jsonPath("$.data[0].prezzoNotte").exists())
                    .andExpect(jsonPath("$.page.pageNumber").value(0))
                    .andExpect(jsonPath("$.page.pageSize").value(1))
                    .andExpect(jsonPath("$.page.totalElements").isNumber())
                    .andExpect(jsonPath("$.page.totalPages").isNumber());
        }

        @Test
        @DisplayName("con size oltre il massimo consentito risponde 400")
        void elenco_conSizeOltreIlMassimo_risponde400() throws Exception {
            // when: si chiede una pagina piu' grande del tetto dichiarato nello spec
            mockMvc.perform(get(TIPOLOGIE).param("size", "5000"))
                    // then: 400 nella busta standard. Il tetto e' una difesa, non un
                    // suggerimento: senza, una sola richiesta si porterebbe via l'intera
                    // tabella. E deve arrivare come 400 e non come 500 — e' chi chiama ad
                    // aver sbagliato, e un 500 direbbe il contrario
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").exists())
                    // 'data' porta la mappa campo -> messaggio come per il body, e la
                    // chiave e' il nome che il client ha scritto nella query string: il
                    // percorso completo sarebbe "elencaTipologieCamera.size", cioe' il
                    // nome di un metodo Java in una risposta pubblica
                    .andExpect(jsonPath("$.data.size").exists());
        }
    }

    @Nested
    @DisplayName("GET /api/tipologie-camera/{id}")
    class Dettaglio {

        @Test
        @DisplayName("da anonimo risponde 200 con la tipologia richiesta")
        void dettaglio_daAnonimo_risponde200() throws Exception {
            // given: una tipologia in catalogo
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();
            long id = creaTipologia(tokenAdmin(), richiesta);

            // when: si apre la scheda senza autenticarsi
            mockMvc.perform(get(TIPOLOGIE + "/" + id))
                    // then: 200 e i dati salvati, prezzo compreso
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()))
                    .andExpect(jsonPath("$.data.capienzaMax").value(2))
                    .andExpect(jsonPath("$.data.prezzoNotte").value(120.00));
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void dettaglio_conIdInesistente_risponde404() throws Exception {
            // when: si chiede una tipologia che non c'e'
            mockMvc.perform(get(TIPOLOGIE + "/999999999"))
                    // then: 404 nella busta standard, 'data' vuoto
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con id fuori dai valori ammessi risponde 400")
        void dettaglio_conIdNonAmmesso_risponde400() throws Exception {
            // when: un id che non puo' esistere, perche' le chiavi partono da 1
            mockMvc.perform(get(TIPOLOGIE + "/0"))
                    // then: 400 e non 404 ne' 500. E' l'altro percorso della validazione
                    // dei parametri — qui il vincolo e' sul path e non sulla query — e
                    // senza il suo handler sarebbe un 500 come lo era il size fuori scala
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("con id non numerico risponde 400")
        void dettaglio_conIdNonNumerico_risponde400() throws Exception {
            // when: l'id non e' un numero
            mockMvc.perform(get(TIPOLOGIE + "/non-un-numero"))
                    // then: 400 e non 404 — la richiesta e' malformata, non punta a una
                    // risorsa che potrebbe esistere
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Nested
    @DisplayName("POST /api/tipologie-camera")
    class Creazione {

        @Test
        @DisplayName("da anonimo risponde 401 e non 403")
        void creazione_daAnonimo_risponde401() throws Exception {
            // when: si prova a scrivere nel catalogo senza token
            mockMvc.perform(post(TIPOLOGIE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    // then: 401 — chi non si e' autenticato deve sentirsi dire di farlo,
                    // non che i suoi permessi non bastano. La differenza la fa
                    // l'authenticationEntryPoint in SecurityConfig
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void creazione_conTokenUtente_risponde403() throws Exception {
            // given: un cliente registrato dal frontoffice (ruolo USER)
            RegisterRequest cliente = dati.registerRequest();
            registraAccount(cliente);
            String token = ottieniToken(cliente.getEmail());

            // when: prova a inserire una tipologia
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    // then: 403 — qui l'identita' e' nota e valida, e' il ruolo a non
                    // bastare. E' il primo @PreAuthorize di produzione del progetto
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con un token da personale non amministratore risponde 403")
        void creazione_conTokenStaff_risponde403() throws Exception {
            // given: un account del personale con ruolo STAFF, non ADMIN
            String email = dati.emailUnivoca();
            staffDiTest.creaStaff(email);
            String token = ottieniToken(email);

            // when: prova a inserire una tipologia
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    // then: 403. Il caso e' distinto da quello del cliente di proposito:
                    // "hasRole('ADMIN')" non vuol dire "chiunque lavori qui", e senza
                    // questo test un ruolo scritto per sbaglio come STAFF passerebbe
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("da ADMIN crea la tipologia e risponde 201")
        void creazione_daAdmin_risponde201() throws Exception {
            // given: un amministratore e una tipologia valida
            String token = tokenAdmin();
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();

            // when: la crea
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201 con la risorsa appena creata, id compreso
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()))
                    .andExpect(jsonPath("$.data.prezzoNotte").value(120.00));
        }

        @Test
        @DisplayName("con un nome gia' in uso, anche cambiando le maiuscole, risponde 409")
        void creazione_conNomeDuplicato_risponde409() throws Exception {
            // given: una tipologia gia' a catalogo
            String token = tokenAdmin();
            TipologiaCameraRequest prima = dati.tipologiaCameraRequest();
            creaTipologia(token, prima);

            // when: se ne crea un'altra con lo stesso nome tutto minuscolo
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest()
                                    .nome(prima.getNome().toLowerCase()))))
                    // then: 409. Le maiuscole non fanno un nome diverso: per chi legge il
                    // sito "Doppia" e "doppia" sono la stessa camera, e un UNIQUE
                    // case-sensitive lascerebbe entrare il duplicato
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con capienza e prezzo fuori dai limiti risponde 400 elencando i campi")
        void creazione_conCampiFuoriDaiLimiti_risponde400ConDettaglioCampi() throws Exception {
            // given: due vincoli dello spec violati insieme
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest()
                    .capienzaMax(0)
                    .prezzoNotte(new BigDecimal("-1"));

            // when: si prova a crearla
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400 con la mappa campo -> messaggio, come per la registrazione:
                    // e' l'unica risposta d'errore che valorizza 'data'
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.capienzaMax").exists())
                    .andExpect(jsonPath("$.data.prezzoNotte").exists());
        }

        @Test
        @DisplayName("con un nome piu' lungo della colonna risponde 400 e non 500")
        void creazione_conNomeTroppoLungo_risponde400() throws Exception {
            // given: un nome oltre i 100 caratteri della colonna
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest()
                    .nome("N".repeat(101));

            // when: si prova a crearla
            mockMvc.perform(post(TIPOLOGIE)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400. Senza il maxLength nello spec la stringa arriverebbe fino
                    // a Postgres, che la rifiuterebbe, e chi chiama vedrebbe un 500 per un
                    // errore suo. I limiti nel contratto ricalcano il DDL apposta
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.nome").exists());
        }
    }

    @Nested
    @DisplayName("PUT /api/tipologie-camera/{id}")
    class Aggiornamento {

        @Test
        @DisplayName("da ADMIN aggiorna i campi e risponde 200")
        void aggiornamento_daAdmin_risponde200() throws Exception {
            // given: una tipologia gia' a catalogo
            String token = tokenAdmin();
            TipologiaCameraRequest originale = dati.tipologiaCameraRequest();
            long id = creaTipologia(token, originale);

            // when: se ne cambia prezzo e descrizione lasciando lo stesso nome
            TipologiaCameraRequest modificata = dati.tipologiaCameraRequest()
                    .nome(originale.getNome())
                    .descrizione("Camera doppia con vista sul mare")
                    .prezzoNotte(new BigDecimal("145.00"));

            mockMvc.perform(put(TIPOLOGIE + "/" + id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(modificata)))
                    // then: 200 e i nuovi valori. Riconfermare il proprio nome non deve
                    // dare 409: il controllo di unicita' esclude la tipologia stessa
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.id").value(id))
                    .andExpect(jsonPath("$.data.descrizione").value("Camera doppia con vista sul mare"))
                    .andExpect(jsonPath("$.data.prezzoNotte").value(145.00));
        }

        @Test
        @DisplayName("prendendo il nome di un'altra tipologia risponde 409")
        void aggiornamento_conNomeDiUnAltra_risponde409() throws Exception {
            // given: due tipologie distinte a catalogo
            String token = tokenAdmin();
            TipologiaCameraRequest prima = dati.tipologiaCameraRequest();
            creaTipologia(token, prima);
            long idSeconda = creaTipologia(token, dati.tipologiaCameraRequest());

            // when: si prova a rinominare la seconda col nome della prima
            mockMvc.perform(put(TIPOLOGIE + "/" + idSeconda)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest().nome(prima.getNome()))))
                    // then: 409. E' il rovescio del test sull'aggiornamento riuscito:
                    // li' si verifica che il proprio nome non dia conflitto, qui che quello
                    // di un'altra lo dia — senza entrambi, un controllo di unicita' rotto
                    // in un verso solo passerebbe inosservato
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("con un prezzo a piu' di due decimali risponde 400")
        void aggiornamento_conPrezzoNonRappresentabile_risponde400() throws Exception {
            // given: una tipologia a catalogo
            String token = tokenAdmin();
            TipologiaCameraRequest originale = dati.tipologiaCameraRequest();
            long id = creaTipologia(token, originale);

            // when: le si assegna un prezzo con tre decimali
            mockMvc.perform(put(TIPOLOGIE + "/" + id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest()
                                    .nome(originale.getNome())
                                    .prezzoNotte(new BigDecimal("120.999")))))
                    // then: 400, e non un 200 con un prezzo arrotondato di nascosto
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            // then: il prezzo originale e' rimasto quello. Verificare solo lo status
            // direbbe che la risposta e' giusta, non che la scrittura non e' avvenuta
            mockMvc.perform(get(TIPOLOGIE + "/" + id))
                    .andExpect(jsonPath("$.data.prezzoNotte").value(120.00));
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void aggiornamento_conIdInesistente_risponde404() throws Exception {
            // when: si aggiorna una tipologia che non c'e'
            mockMvc.perform(put(TIPOLOGIE + "/999999999")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    // then: 404
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("da anonimo risponde 401")
        void aggiornamento_daAnonimo_risponde401() throws Exception {
            // when: si aggiorna senza token
            mockMvc.perform(put(TIPOLOGIE + "/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.tipologiaCameraRequest())))
                    // then: 401 prima ancora di guardare se la tipologia esiste. Le
                    // scritture non sono fra i permitAll, quindi ci si ferma nella filter
                    // chain — e un anonimo non deve poter scoprire quali id esistono
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("DELETE /api/tipologie-camera/{id}")
    class Eliminazione {

        @Test
        @DisplayName("da ADMIN elimina la tipologia, che poi non si trova piu'")
        void eliminazione_daAdmin_risponde200EPoi404() throws Exception {
            // given: una tipologia non usata da nessuno
            String token = tokenAdmin();
            long id = creaTipologia(token, dati.tipologiaCameraRequest());

            // when: la si elimina
            mockMvc.perform(delete(TIPOLOGIE + "/" + id)
                            .header("Authorization", "Bearer " + token))
                    // then: 200 con 'data' vuoto — non c'e' piu' niente da restituire.
                    // Non 204, perche' la busta standard vale per ogni endpoint e un 204
                    // non puo' avere corpo
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: ed e' sparita davvero. Senza questa seconda chiamata il test
            // proverebbe solo che l'endpoint risponde, non che cancella
            mockMvc.perform(get(TIPOLOGIE + "/" + id))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("se una camera la usa ancora risponde 409 e non la elimina")
        void eliminazione_conCameraCollegata_risponde409() throws Exception {
            // given: una tipologia con una camera fisica assegnata
            String token = tokenAdmin();
            long id = creaTipologia(token, dati.tipologiaCameraRequest());

            jdbcTemplate.update(
                    "INSERT INTO camera (numero, piano, tipologia_camera_id, stato) VALUES (?, ?, ?, 'LIBERA')",
                    "camera-" + id, 1, id);

            // when: si prova a eliminare la tipologia
            mockMvc.perform(delete(TIPOLOGIE + "/" + id)
                            .header("Authorization", "Bearer " + token))
                    // then: 409 e non 500. La chiave esterna e' senza cascata di proposito
                    // — cancellare a catena porterebbe via lo storico delle prenotazioni —
                    // quindi il caso e' previsto e va detto a chi chiama in modo che possa
                    // rimediare, non nascosto dietro un errore generico
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").exists());

            // then: e la tipologia e' ancora al suo posto
            mockMvc.perform(get(TIPOLOGIE + "/" + id))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void eliminazione_conTokenUtente_risponde403() throws Exception {
            // given: una tipologia e un cliente qualsiasi
            long id = creaTipologia(tokenAdmin(), dati.tipologiaCameraRequest());

            RegisterRequest cliente = dati.registerRequest();
            registraAccount(cliente);
            String tokenCliente = ottieniToken(cliente.getEmail());

            // when: il cliente prova a cancellarla
            mockMvc.perform(delete(TIPOLOGIE + "/" + id)
                            .header("Authorization", "Bearer " + tokenCliente))
                    // then: 403
                    .andExpect(status().isForbidden());

            // then: e la tipologia e' ancora li'. Verificare solo lo status direbbe che la
            // risposta e' giusta, non che la cancellazione non e' avvenuta lo stesso
            mockMvc.perform(get(TIPOLOGIE + "/" + id))
                    .andExpect(status().isOk());
        }
    }
}
