package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione della tassa di soggiorno.
 *
 * <p><b>L'isolamento qui e' un problema vero</b>, al contrario dei periodi
 * tariffari: quelli appartengono a una tipologia, quindi ogni test si crea la
 * propria e non vede quelle degli altri, mentre le aliquote sono <b>dell'albergo</b>
 * — cioe' una tabella sola per l'intera suite, con un vincolo che vieta la
 * sovrapposizione. Due test che scrivessero aliquote sulle stesse date si
 * romperebbero a vicenda.
 *
 * <p>Il rimedio e' {@link #anno(int)}: ogni test lavora su un <b>anno suo</b>,
 * scritto nel nome del metodo che lo usa. Non e' elegante quanto l'isolamento per
 * id, ma e' l'unico possibile quando la risorsa e' per definizione unica — e ha il
 * pregio di essere visibile: chi aggiunge un test qui vede subito che deve
 * scegliersi un anno libero.
 */
@DisplayName("API della tassa di soggiorno")
class TassaSoggiornoApiIT extends IntegrationTestBase {

    private static final String ALIQUOTE = "/api/tassa-soggiorno/aliquote";
    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Nested
    @DisplayName("Aliquote")
    class Aliquote {

        @Test
        @DisplayName("l'ADMIN crea un'aliquota e la ritrova nell'elenco")
        void crea_daAdmin_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();

            // when
            mockMvc.perform(post(ALIQUOTE)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest()
                                    .dataInizio(anno(2040))
                                    .dataFine(anno(2040).plusMonths(11)))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("Aliquota creata"))
                    .andExpect(jsonPath("$.data.importoPerPersonaNotte").value(2.00))
                    .andExpect(jsonPath("$.data.nottiMassimeTassate").value(5))
                    .andExpect(jsonPath("$.data.etaEsenzione").value(12));

            // then
            mockMvc.perform(get(ALIQUOTE)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalElements").isNumber());
        }

        @Test
        @DisplayName("due aliquote sulle stesse date rispondono 409, e lo dice il database")
        void crea_conDateSovrapposte_risponde409() throws Exception {
            // given: un'aliquota per il 2041
            String admin = tokenAdmin();
            creaAliquota(admin, anno(2041), anno(2041).plusMonths(11));

            // when / then: e' il test che vale davvero, perche' guarda il vincolo di
            // esclusione del V11 e non solo il controllo del Service — "un giorno, un
            // importo" e' l'invariante su cui sta in piedi tutto il calcolo
            mockMvc.perform(post(ALIQUOTE)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest()
                                    .dataInizio(anno(2041).plusMonths(6))
                                    .dataFine(anno(2041).plusMonths(18)))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("sovrappongono")));
        }

        @Test
        @DisplayName("due aliquote consecutive che non si toccano convivono")
        void crea_conDateAdiacenti_risponde201() throws Exception {
            // given: la prima finisce il 30 giugno
            String admin = tokenAdmin();
            LocalDate inizio = anno(2042);
            creaAliquota(admin, inizio, inizio.plusMonths(6).minusDays(1));

            // when / then: la seconda comincia il giorno dopo. Gli estremi sono inclusi
            // tutti e due, quindi il confine e' esattamente qui e vale la pena provarlo:
            // un vincolo scritto con disuguaglianze strette lascerebbe passare anche la
            // sovrapposizione di un giorno
            mockMvc.perform(post(ALIQUOTE)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest()
                                    .dataInizio(inizio.plusMonths(6))
                                    .dataFine(inizio.plusMonths(11)))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("riconfermare le proprie date in una PUT non e' un conflitto")
        void aggiorna_conLeStesseDate_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            LocalDate inizio = anno(2043);
            long id = creaAliquota(admin, inizio, inizio.plusMonths(11));

            // when / then: senza l'esclusione dell'aliquota stessa, correggere il solo
            // importo sarebbe impossibile
            mockMvc.perform(put(ALIQUOTE + "/" + id)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest()
                                    .dataInizio(inizio)
                                    .dataFine(inizio.plusMonths(11))
                                    .importoPerPersonaNotte(new BigDecimal("3.50")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.importoPerPersonaNotte").value(3.50));
        }

        @Test
        @DisplayName("i campi facoltativi omessi in una PUT spariscono dalla risposta")
        void aggiorna_senzaTettoNeEta_liAzzera() throws Exception {
            // given
            String admin = tokenAdmin();
            LocalDate inizio = anno(2044);
            long id = creaAliquota(admin, inizio, inizio.plusMonths(11));

            // when / then: e' quel che una PUT promette. I campi spariscono invece di
            // comparire a null, come ovunque nel progetto: i DTO generati omettono i
            // campi vuoti
            mockMvc.perform(put(ALIQUOTE + "/" + id)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest()
                                    .dataInizio(inizio)
                                    .dataFine(inizio.plusMonths(11))
                                    .nottiMassimeTassate(null)
                                    .etaEsenzione(null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nottiMassimeTassate").doesNotExist())
                    .andExpect(jsonPath("$.data.etaEsenzione").doesNotExist());
        }

        @Test
        @DisplayName("l'ADMIN elimina un'aliquota")
        void elimina_daAdmin_risponde200() throws Exception {
            String admin = tokenAdmin();
            LocalDate inizio = anno(2045);
            long id = creaAliquota(admin, inizio, inizio.plusMonths(11));

            mockMvc.perform(delete(ALIQUOTE + "/" + id)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());

            mockMvc.perform(put(ALIQUOTE + "/" + id)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("su un'aliquota che non esiste risponde 404")
        void aggiorna_suAliquotaInesistente_risponde404() throws Exception {
            mockMvc.perform(put(ALIQUOTE + "/999999")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Permessi sulle aliquote")
    class PermessiAliquote {

        @Test
        @DisplayName("lo STAFF legge ma non scrive")
        void aliquote_daStaff_leggeMaNonScrive() throws Exception {
            // given / when / then: chi sta al banco deve poter rispondere a "quant'e' la
            // tassa?", ma trascrivere il regolamento comunale e' un'altra cosa
            String staff = tokenStaff();

            mockMvc.perform(get(ALIQUOTE)
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            mockMvc.perform(post(ALIQUOTE)
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.aliquotaRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("un cliente non vede nemmeno l'elenco")
        void aliquote_daCliente_risponde403() throws Exception {
            // given / when / then: il listino intero non va in vetrina — la domanda del
            // cliente ha gia' la sua rotta sulla propria prenotazione
            mockMvc.perform(get(ALIQUOTE)
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("senza token risponde 401, non 403")
        void aliquote_senzaToken_risponde401() throws Exception {
            mockMvc.perform(get(ALIQUOTE))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Il conto di una prenotazione")
    class Conto {

        @Test
        @DisplayName("una famiglia con un bambino: paga il genitore, non il figlio")
        void calcola_conAdultoEBambino_tassaSoloLAdulto() throws Exception {
            // given: un'aliquota da 2 euro che esenta sotto i 12, e un soggiorno di tre
            // notti con un genitore e un bambino
            String admin = tokenAdmin();
            LocalDate arrivo = LocalDate.now();
            creaAliquota(admin, arrivo.minusYears(1), arrivo.plusYears(1));

            long idPrenotazione = prenotazioneConfermata(admin, arrivo, arrivo.plusDays(3));
            registraOspite(admin, idPrenotazione, dati.ospiteRequest());
            registraOspite(admin, idPrenotazione, dati.ospiteMinorenneRequest()
                    .dataNascita(arrivo.minusYears(6)));

            // when: 3 notti x 2 euro per il solo adulto
            String risposta = mockMvc.perform(
                            get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                                    .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Tassa di soggiorno calcolata"))
                    .andExpect(jsonPath("$.data.totale").value(6.00))
                    .andExpect(jsonPath("$.data.nottiSoggiorno").value(3))
                    .andExpect(jsonPath("$.data.nottiNonCoperte").value(0))
                    .andExpect(jsonPath("$.data.ospiti.length()").value(2))
                    .andExpect(jsonPath("$.data.ospiti[0].importo").value(6.00))
                    .andExpect(jsonPath("$.data.ospiti[0].esenzioneEta").value(false))
                    .andExpect(jsonPath("$.data.ospiti[1].importo").value(0.00))
                    .andExpect(jsonPath("$.data.ospiti[1].esenzioneEta").value(true))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // then: **gli importi hanno due decimali anche a zero**, e questo si guarda
            // sul testo del JSON perche' jsonPath confronta i numeri — per lui 0 e 0.00
            // sono lo stesso valore. Il difetto c'era davvero: il giro di curl del
            // 2026-09-01 ha mostrato "importo":6.00 accanto a "importo":0 nella stessa
            // risposta, cioe' due formati per la stessa colonna di un conto
            org.assertj.core.api.Assertions.assertThat(risposta)
                    .contains("\"importo\":6.00")
                    .contains("\"importo\":0.00")
                    .doesNotContain("\"importo\":0,");
        }

        @Test
        @DisplayName("il dettaglio non porta il numero di documento")
        void calcola_nelDettaglio_nonCEIlDocumento() throws Exception {
            // given
            String admin = tokenAdmin();
            LocalDate arrivo = LocalDate.now();
            long idPrenotazione = prenotazioneConfermata(admin, arrivo, arrivo.plusDays(3));
            registraOspite(admin, idPrenotazione, dati.ospiteRequest());

            // when / then: e' la ragione per cui questa rotta puo' vederla anche il
            // cliente, mentre il registro degli ospiti resta chiuso al personale. Se un
            // domani il campo comparisse, e' questo test a doverlo dire
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ospiti[0].nome").value("Mario"))
                    .andExpect(jsonPath("$.data.ospiti[0].numeroDocumento").doesNotExist())
                    .andExpect(jsonPath("$.data.ospiti[0].tipoDocumento").doesNotExist())
                    .andExpect(jsonPath("$.data.ospiti[0].dataNascita").doesNotExist());
        }

        @Test
        @DisplayName("un'esenzione dichiarata sull'ospite azzera il suo conto")
        void calcola_conMotivoDichiarato_esente() throws Exception {
            // given: un residente
            String admin = tokenAdmin();
            LocalDate arrivo = LocalDate.now();
            long idPrenotazione = prenotazioneConfermata(admin, arrivo, arrivo.plusDays(3));
            registraOspite(admin, idPrenotazione, dati.ospiteRequest()
                    .motivoEsenzione(com.felixhotel.backend.dto.MotivoEsenzione.RESIDENTE));

            // when / then: il motivo fa il giro intero — scritto sull'ospite, riletto dal
            // calcolo, restituito nel conto
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totale").value(0.00))
                    .andExpect(jsonPath("$.data.ospiti[0].motivoEsenzione").value("RESIDENTE"))
                    .andExpect(jsonPath("$.data.ospiti[0].nottiTassate").value(0));
        }

        @Test
        @DisplayName("il cliente vede il conto della propria prenotazione")
        void calcola_daProprietario_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);
            String tokenCliente = auth.ottieniToken(cliente.getEmail());

            LocalDate arrivo = LocalDate.now();
            long idPrenotazione = prenotazioneConfermataDi(admin, tokenCliente,
                    arrivo, arrivo.plusDays(3));

            // when / then: e' un importo che paghera' lui, e nascondergli quanto sara' non
            // proteggerebbe nessuno
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + tokenCliente))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("la prenotazione di un altro cliente risponde 404, non 403")
        void calcola_daAltroCliente_risponde404() throws Exception {
            // given
            String admin = tokenAdmin();
            LocalDate arrivo = LocalDate.now();
            long idPrenotazione = prenotazioneConfermata(admin, arrivo, arrivo.plusDays(3));

            // when / then: un 403 direbbe "esiste, ma non e' tua". E' la stessa regola di
            // ogni altra rotta sotto /api/prenotazioni
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("senza nessuna aliquota il totale e' zero e le notti risultano scoperte")
        void calcola_senzaAliquote_rispondeZero() throws Exception {
            // given: un soggiorno nel 2050, dove nessun test scrive aliquote
            String admin = tokenAdmin();
            LocalDate arrivo = anno(2050);
            long idPrenotazione = prenotazioneConfermata(admin, arrivo, arrivo.plusDays(3));

            // when / then: non e' un errore — un comune senza tassa esiste — ed e'
            // 'nottiNonCoperte' a distinguerlo da "sono tutti esenti"
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totale").value(0.00))
                    .andExpect(jsonPath("$.data.nottiNonCoperte").value(3));
        }

        @Test
        @DisplayName("su una prenotazione ancora IN_ATTESA il totale e' zero e gli ospiti vuoti")
        void calcola_suPrenotazioneInAttesa_rispondeZero() throws Exception {
            // given: un carrello non confermato, su cui gli ospiti non si possono ancora
            // registrare
            String admin = tokenAdmin();
            LocalDate arrivo = anno(2051);
            long idTipologia = tipologiaPrenotabile(admin);
            long idPrenotazione = creaPrenotazione(admin, tokenCliente(), idTipologia,
                    arrivo, arrivo.plusDays(3));

            // when / then: la lettura non ha finestra di stato, come quella del registro
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione + "/tassa-soggiorno")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totale").value(0.00))
                    .andExpect(jsonPath("$.data.ospiti.length()").value(0));
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404")
        void calcola_suPrenotazioneInesistente_risponde404() throws Exception {
            mockMvc.perform(get(PRENOTAZIONI + "/999999/tassa-soggiorno")
                            .header("Authorization", "Bearer " + tokenAdmin()))
                    .andExpect(status().isNotFound());
        }
    }

    // ---- infrastruttura ----------------------------------------------------------

    /**
     * Il primo gennaio dell'anno indicato.
     *
     * <p>E' il modo con cui i test di questo file si tengono fuori dai piedi a
     * vicenda: le aliquote sono dell'albergo, quindi la tabella e' una sola per la
     * suite e il vincolo di esclusione non perdona. Anni lontani anche dai
     * soggiorni "di oggi" che i test del conto usano.
     */
    private LocalDate anno(int anno) {
        return LocalDate.of(anno, 1, 1);
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

    private long creaAliquota(String tokenAdmin, LocalDate inizio, LocalDate fine) throws Exception {
        String risposta = mockMvc.perform(post(ALIQUOTE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.aliquotaRequest().dataInizio(inizio).dataFine(fine))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    /** Una tipologia con una camera dentro: la camera serve alla conferma. */
    private long tipologiaPrenotabile(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long idTipologia = objectMapper.readTree(risposta).path("data").path("id").asLong();

        mockMvc.perform(post("/api/camere")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(idTipologia))))
                .andExpect(status().isCreated());

        return idTipologia;
    }

    private long prenotazioneConfermata(String tokenAdmin, LocalDate arrivo,
                                        LocalDate partenza) throws Exception {
        return prenotazioneConfermataDi(tokenAdmin, tokenCliente(), arrivo, partenza);
    }

    private long prenotazioneConfermataDi(String tokenAdmin, String tokenCliente,
                                          LocalDate arrivo, LocalDate partenza) throws Exception {
        long idTipologia = tipologiaPrenotabile(tokenAdmin);
        long id = creaPrenotazione(tokenAdmin, tokenCliente, idTipologia, arrivo, partenza);

        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/conferma")
                        .header("Authorization", "Bearer " + tokenCliente))
                .andExpect(status().isOk());

        return id;
    }

    private long creaPrenotazione(String tokenAdmin, String tokenCliente, long idTipologia,
                                  LocalDate arrivo, LocalDate partenza) throws Exception {
        PrenotazioneRequest richiesta = dati.prenotazioneRequest(idTipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(partenza);

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private void registraOspite(String token, long idPrenotazione,
                                com.felixhotel.backend.dto.OspiteRequest richiesta) throws Exception {
        mockMvc.perform(post(PRENOTAZIONI + "/" + idPrenotazione + "/ospiti")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated());
    }
}
