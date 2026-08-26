package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione della galleria fotografica delle tipologie di camera.
 *
 * <p>E' il primo IT del progetto che esercita due cose nuove:
 * <ul>
 *   <li>un <b>sottopercorso pubblico</b>: {@code /api/tipologie-camera/*}{@code /media}
 *       e' il primo path annidato ad essere elencato fra i {@code permitAll}, ed
 *       e' quello che rende verificabile la scelta di non aver mai usato un
 *       {@code /**} — le dotazioni stanno allo stesso livello e restano chiuse;</li>
 *   <li>una <b>sequenza</b>: fin qui gli elenchi erano ordinati dal database per
 *       un campo che il client non decideva (un nome, un numero). Qui l'ordine e'
 *       un dato scritto da chi amministra, e va verificato che sopravviva al giro
 *       completo — lo si impone con una chiamata e lo si rilegge con un'altra.</li>
 * </ul>
 *
 * <p>Buona parte dei test qui sotto passa da <b>due</b> chiamate proprio per
 * questo: verificare la risposta della PUT direbbe solo che il service sa
 * ripetere quel che gli e' stato detto. Cio' che conta e' che la GET successiva
 * dia lo stesso ordine, cioe' che sia finito a database.
 */
@DisplayName("API della galleria delle tipologie")
class MediaCameraApiIT extends IntegrationTestBase {

    private static final String TIPOLOGIE = "/api/tipologie-camera";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaAdmin(email);
        return auth.ottieniToken(email);
    }

    /**
     * Token del personale non amministratore. Qui serve a provare un confine che
     * l'inventario delle camere aveva spostato: li' lo STAFF puo' cambiare lo
     * stato di una stanza, qui non puo' toccare le foto — pubblicare non e'
     * un'operazione di turno.
     */
    private String tokenStaff() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaStaff(email);
        return auth.ottieniToken(email);
    }

    /** Token di un cliente registrato dal frontoffice (ruolo USER). */
    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        auth.registraAccount(cliente);
        return auth.ottieniToken(cliente.getEmail());
    }

    /** Percorso della galleria di una tipologia. */
    private String media(long tipologiaId) {
        return TIPOLOGIE + "/" + tipologiaId + "/media";
    }

    /** Crea una tipologia dall'endpoint vero e ne restituisce l'id. */
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

    /** Aggiunge una foto dall'endpoint vero e ne restituisce l'id. */
    private long aggiungiFoto(String tokenAdmin, long tipologiaId, MediaCameraRequest richiesta)
            throws Exception {
        String risposta = mockMvc.perform(post(media(tipologiaId))
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    /**
     * Gli id delle foto come si leggono adesso dalla galleria, in ordine. E' la
     * verifica che conta in quasi tutti i test di questa classe: non cosa
     * risponde chi scrive, ma cosa vede chi legge dopo.
     */
    private List<Long> idInGalleria(long tipologiaId) throws Exception {
        String risposta = mockMvc.perform(get(media(tipologiaId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Long> ids = new ArrayList<>();
        for (JsonNode foto : objectMapper.readTree(risposta).path("data")) {
            ids.add(foto.path("id").asLong());
        }
        return ids;
    }

    @Nested
    @DisplayName("GET /api/tipologie-camera/{id}/media")
    class Elenco {

        @Test
        @DisplayName("da anonimo risponde 200: e' il primo sottopercorso pubblico")
        void elenco_daAnonimo_risponde200() throws Exception {
            // given: una tipologia con una foto
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when: si legge la galleria senza nessun token
            mockMvc.perform(get(media(tipologia)))
                    // then: 200. Sono le immagini della scheda di catalogo, e tenerle dietro
                    // a un login vorrebbe dire un catalogo senza fotografie
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("il permitAll copre le foto ma non le dotazioni allo stesso livello")
        void elenco_permitAll_nonSiEstendeAgliAltriSottopercorsi() throws Exception {
            // given: una tipologia qualsiasi
            long tipologia = creaTipologia(tokenAdmin());

            // when: da anonimo si chiede la galleria, poi il sottopercorso vicino
            mockMvc.perform(get(media(tipologia))).andExpect(status().isOk());

            // then: una GET sul sottopercorso accanto si ferma con 401, perche' il
            // matcher elenca /media e non /dotazioni. E' la verifica della scelta fatta
            // in SecurityConfig: i sottopercorsi si aprono uno per uno, e con un "/**"
            // questo sarebbe pubblico da adesso senza che nessuno l'abbia deciso
            mockMvc.perform(get(TIPOLOGIE + "/" + tipologia + "/dotazioni"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("su una tipologia senza foto risponde con un array vuoto")
        void elenco_senzaFoto_rispondeArrayVuoto() throws Exception {
            // given: una tipologia appena creata
            long tipologia = creaTipologia(tokenAdmin());

            // when/then: 200 con la lista vuota, non un 404: la scheda esiste, non ha
            // immagini
            mockMvc.perform(get(media(tipologia)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("su una tipologia inesistente risponde 404 e non una lista vuota")
        void elenco_conTipologiaInesistente_risponde404() throws Exception {
            // when/then: 404. E' la differenza che chi legge il catalogo deve poter
            // vedere: una scheda senza foto e una scheda che non c'e' non sono la stessa
            // cosa, e senza il controllo di esistenza risponderebbero uguale
            mockMvc.perform(get(media(999_999L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("non espone la posizione: l'ordine e' quello dell'array")
        void elenco_nonEspongonoIlCampoOrdine() throws Exception {
            // given: una foto in galleria
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when/then: la risposta ha id e url, e nient'altro. Il campo esiste in
            // database ma non esce: esporlo prometterebbe che i valori partano da zero e
            // non abbiano buchi, cosa che l'eliminazione di una foto non mantiene
            mockMvc.perform(get(media(tipologia)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").exists())
                    .andExpect(jsonPath("$.data[0].url").exists())
                    .andExpect(jsonPath("$.data[0].ordine").doesNotExist());
        }

        @Test
        @DisplayName("le foto escono nell'ordine in cui sono state aggiunte")
        void elenco_conPiuFoto_seguonoLOrdineDiInserimento() throws Exception {
            // given: tre foto aggiunte una dopo l'altra
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long terza = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when/then: nell'ordine di inserimento. Chi aggiunge non sceglie la
            // posizione, e "in fondo" e' l'unica deducibile senza chiederla
            assertThat(idInGalleria(tipologia)).containsExactly(prima, seconda, terza);
        }
    }

    @Nested
    @DisplayName("POST /api/tipologie-camera/{id}/media")
    class Aggiunta {

        @Test
        @DisplayName("da anonimo risponde 401")
        void aggiunta_daAnonimo_risponde401() throws Exception {
            // given: una tipologia esistente
            long tipologia = creaTipologia(tokenAdmin());

            // when/then: la GET e' pubblica, la POST sullo stesso path no — il permitAll
            // e' dichiarato per metodo, non per percorso
            mockMvc.perform(post(media(tipologia))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void aggiunta_conTokenUtente_risponde403() throws Exception {
            long tipologia = creaTipologia(tokenAdmin());

            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + tokenCliente())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("con un token da STAFF risponde 403: pubblicare non e' operazione di turno")
        void aggiunta_conTokenStaff_risponde403() throws Exception {
            long tipologia = creaTipologia(tokenAdmin());

            // when/then: 403 anche per il personale. E' un confine diverso da quello
            // dell'inventario, dove lo STAFF puo' cambiare lo stato di una camera:
            // decidere che faccia ha una tipologia sul sito e' pubblicare, e vale la
            // pena che lo faccia chi risponde del catalogo
            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("da ADMIN risponde 201 con la foto appena aggiunta")
        void aggiunta_daAdmin_risponde201() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            MediaCameraRequest richiesta = dati.mediaCameraRequest();

            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.url").value(richiesta.getUrl()));
        }

        @Test
        @DisplayName("con uno schema diverso da http/https risponde 400")
        void aggiunta_conSchemaNonHttp_risponde400() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            // when: si prova a registrare un indirizzo javascript:
            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest().url("javascript:alert(1)"))))
                    // then: 400 dal @Pattern dello spec. Non e' pignoleria sul formato: il
                    // valore finisce nell'attributo src di un tag img, e uno schema
                    // qualunque sarebbe uno script eseguito sulla pagina del catalogo
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.url").exists());
        }

        @Test
        @DisplayName("con url vuota risponde 400")
        void aggiunta_conUrlVuota_risponde400() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest().url(""))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.url").exists());
        }

        @Test
        @DisplayName("con la stessa url gia' in galleria risponde 409")
        void aggiunta_conUrlDuplicata_risponde409() throws Exception {
            // given: una foto gia' aggiunta
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            aggiungiFoto(admin, tipologia, richiesta);

            // when/then: la stessa immagine due volte nella stessa galleria e' un doppio
            // click, non una scelta editoriale
            mockMvc.perform(post(media(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("la stessa url in due tipologie diverse e' permessa")
        void aggiunta_conUrlUgualeSuAltraTipologia_risponde201() throws Exception {
            // given: la stessa immagine, due tipologie
            String admin = tokenAdmin();
            long prima = creaTipologia(admin);
            long seconda = creaTipologia(admin);
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            aggiungiFoto(admin, prima, richiesta);

            // when/then: 201. L'indice unico e' sulla coppia (tipologia, url) e non sulla
            // sola url: una foto della hall, o la vista dallo stesso lato dell'edificio,
            // appartengono legittimamente a piu' schede — vietarlo obbligherebbe a
            // duplicare il file per aggirare il vincolo
            mockMvc.perform(post(media(seconda))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("su una tipologia inesistente risponde 404")
        void aggiunta_conTipologiaInesistente_risponde404() throws Exception {
            mockMvc.perform(post(media(999_999L))
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaCameraRequest())))
                    // then: 404 e non 400, al contrario di quel che fa la camera con la sua
                    // tipologia: li' l'id sta nel corpo, qui nel percorso — ed e' il percorso a
                    // dire quale risorsa si sta cercando
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/tipologie-camera/{id}/media/ordine")
    class Riordino {

        private String ordine(long tipologiaId) {
            return media(tipologiaId) + "/ordine";
        }

        @Test
        @DisplayName("da ADMIN riscrive la sequenza, e la rilettura la conferma")
        void riordino_daAdmin_cambiaLOrdineLetto() throws Exception {
            // given: tre foto nell'ordine di inserimento
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long terza = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when: si chiede l'ordine inverso
            mockMvc.perform(put(ordine(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest(terza, seconda, prima))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(terza))
                    .andExpect(jsonPath("$.data[2].id").value(prima));

            // then: e soprattutto lo vede anche chi rilegge. La risposta della PUT da
            // sola direbbe solo che il service sa ripetere quel che gli e' stato detto
            assertThat(idInGalleria(tipologia)).containsExactly(terza, seconda, prima);
        }

        @Test
        @DisplayName("ripetuto con la stessa sequenza non cambia niente")
        void riordino_ripetuto_eIdempotente() throws Exception {
            // given: una galleria gia' riordinata
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when: la stessa richiesta due volte
            for (int giro = 0; giro < 2; giro++) {
                mockMvc.perform(put(ordine(tipologia))
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(dati.mediaOrdineRequest(seconda, prima))))
                        .andExpect(status().isOk());
            }

            // then: 200 tutte e due le volte e lo stesso risultato. E' l'argomento per
            // cui il riordino e' una PUT con la lista intera invece di N spostamenti:
            // ripeterla e' innocuo, ed e' cio' che permette a un client di riprovare
            assertThat(idInGalleria(tipologia)).containsExactly(seconda, prima);
        }

        @Test
        @DisplayName("con un elenco parziale risponde 400 e non riordina niente")
        void riordino_conElencoParziale_risponde400() throws Exception {
            // given: tre foto, ma se ne rimandano due — e' il caso di chi ha letto la
            // galleria prima che ne venisse aggiunta una
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long terza = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            mockMvc.perform(put(ordine(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest(seconda, prima))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            // then: la galleria e' rimasta com'era. Applicare la parte che combacia
            // avrebbe messo la terza foto dove capita, ed e' proprio quello che il 400
            // impedisce
            assertThat(idInGalleria(tipologia)).containsExactly(prima, seconda, terza);
        }

        @Test
        @DisplayName("con un id ripetuto risponde 400")
        void riordino_conIdRipetuto_risponde400() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when/then: 400. E' il contrario dell'endpoint delle dotazioni, dove un id
            // ripetuto viene assorbito: la' l'ordine non contava e il duplicato era solo
            // ridondante, qui una foto in due posizioni non e' un'istruzione eseguibile
            mockMvc.perform(put(ordine(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest(prima, prima))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con la foto di un'altra tipologia risponde 400")
        void riordino_conIdEstraneo_risponde400() throws Exception {
            // given: due gallerie separate
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long altra = creaTipologia(admin);
            long nostra = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long altrui = aggiungiFoto(admin, altra, dati.mediaCameraRequest());

            // when/then: 400 e non un'esclusione silenziosa
            mockMvc.perform(put(ordine(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest(nostra, altrui))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con un token da STAFF risponde 403")
        void riordino_conTokenStaff_risponde403() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long foto = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            mockMvc.perform(put(ordine(tipologia))
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest(foto))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("su una tipologia inesistente risponde 404")
        void riordino_conTipologiaInesistente_risponde404() throws Exception {
            mockMvc.perform(put(media(999_999L) + "/ordine")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.mediaOrdineRequest())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/tipologie-camera/{id}/media/{mediaId}")
    class Eliminazione {

        @Test
        @DisplayName("da ADMIN toglie la foto lasciando le altre nel loro ordine")
        void eliminazione_daAdmin_nonCambiaLOrdineDelleAltre() throws Exception {
            // given: tre foto
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long terza = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when: si toglie quella in mezzo
            mockMvc.perform(delete(media(tipologia) + "/" + seconda)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: le altre due restano nell'ordine di prima. Le posizioni a database
            // sono ora 0 e 2 — nessuno le ricompatta, e nessuno puo' accorgersene
            assertThat(idInGalleria(tipologia)).containsExactly(prima, terza);
        }

        @Test
        @DisplayName("dopo un'eliminazione la foto nuova va comunque in fondo")
        void eliminazione_poiAggiunta_mettaInFondo() throws Exception {
            // given: due foto, poi si toglie l'ultima
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long prima = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            long seconda = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            mockMvc.perform(delete(media(tipologia) + "/" + seconda)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            // when: se ne aggiunge un'altra
            long terza = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // then: in fondo. E' il motivo per cui la posizione nuova viene dal massimo e
            // non dal conteggio: col conteggio questa sarebbe finita a pari merito con la
            // prima invece che dopo di lei
            assertThat(idInGalleria(tipologia)).containsExactly(prima, terza);
        }

        @Test
        @DisplayName("con una foto di un'altra tipologia risponde 404 e non la cancella")
        void eliminazione_conFotoDiAltraTipologia_risponde404() throws Exception {
            // given: due gallerie, si prova a cancellare la foto dell'una dall'altra
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long altra = creaTipologia(admin);
            long altrui = aggiungiFoto(admin, altra, dati.mediaCameraRequest());

            mockMvc.perform(delete(media(tipologia) + "/" + altrui)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isNotFound());

            // then: la foto e' ancora al suo posto. E' il motivo per cui il repository non
            // ha nessuna lettura per solo id — una foto di un'altra galleria non e'
            // qualcosa da ricordarsi di rifiutare, e' qualcosa che non si trova
            assertThat(idInGalleria(altra)).containsExactly(altrui);
        }

        @Test
        @DisplayName("con un id che non esiste risponde 404")
        void eliminazione_conIdInesistente_risponde404() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            mockMvc.perform(delete(media(tipologia) + "/999999")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void eliminazione_conTokenUtente_risponde403() throws Exception {
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            long foto = aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            mockMvc.perform(delete(media(tipologia) + "/" + foto)
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("legame con la tipologia")
    class Cascata {

        @Test
        @DisplayName("eliminare la tipologia riesce e porta via le sue foto")
        void eliminazioneTipologia_conFoto_riesceEPortaViaLaGalleria() throws Exception {
            // given: una tipologia con due foto
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());
            aggiungiFoto(admin, tipologia, dati.mediaCameraRequest());

            // when: si elimina la tipologia
            mockMvc.perform(delete(TIPOLOGIE + "/" + tipologia)
                            .header("Authorization", "Bearer " + admin))
                    // then: 200 e non 409, al contrario di quel che succede se ha delle camere.
                    // La chiave esterna delle foto ha ON DELETE CASCADE: una stanza esiste
                    // anche se cambia categoria, una fotografia della categoria no
                    .andExpect(status().isOk());

            // e la galleria e' sparita con lei: 404 perche' ora manca la tipologia stessa
            mockMvc.perform(get(media(tipologia)))
                    .andExpect(status().isNotFound());
        }
    }
}
