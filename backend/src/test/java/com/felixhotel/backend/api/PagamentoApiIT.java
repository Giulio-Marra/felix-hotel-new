package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.MetodoPagamento;
import com.felixhotel.backend.dto.PagamentoRequest;
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
import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione del registro dei pagamenti.
 *
 * <p><b>Qui si guarda il cablaggio</b>: chi passa dai filtri di sicurezza, che forma ha la
 * busta, che la migration del V19 regga davvero una riga scritta dall'applicazione. I rami
 * del calcolo — la caparra, il residuo, i rifiuti — stanno in
 * {@code PagamentoServiceImplTest}, dove costano millisecondi invece di un contesto Spring.
 *
 * <p><b>La percentuale della caparra e' una riga sola per tutta la suite</b>, come le
 * aliquote della tassa di soggiorno: sta in {@code impostazioni_hotel}, che il V8 tiene a
 * un record unico. Il rimedio qui e' piu' semplice che la' — <b>ogni test che dipende
 * dalla percentuale se la imposta subito prima di leggerla</b>, nello stesso metodo — e
 * chi aggiunge un test lo deve sapere: leggere un riepilogo senza aver appena scritto la
 * percentuale vuol dire dipendere da quel che ha lasciato il test precedente.
 */
@DisplayName("API dei pagamenti")
class PagamentoApiIT extends IntegrationTestBase {

    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String IMPOSTAZIONI = "/api/impostazioni";

    /** Tre notti a 120: e' il soggiorno di ogni prova, e fa 360 tondi. */
    private static final BigDecimal PREZZO_NOTTE = new BigDecimal("120.00");

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Nested
    @DisplayName("POST /api/prenotazioni/{id}/pagamenti")
    class Registra {

        @Test
        @DisplayName("lo staff registra un incasso e lo ritrova nel riepilogo")
        void registra_daStaff_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();
            String staff = tokenStaff();
            long idPrenotazione = prenotazioneConfermata(admin);
            impostaCaparra(admin, "30.00");

            // when
            mockMvc.perform(post(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(bonifico("108.00"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("Pagamento registrato"))
                    .andExpect(jsonPath("$.data.importo").value(108.00))
                    .andExpect(jsonPath("$.data.metodo").value("BONIFICO"))
                    .andExpect(jsonPath("$.data.riferimento").value("CRO 1234567890"))
                    // chi ha incassato lo scrive il registro, non chi chiama
                    .andExpect(jsonPath("$.data.registratoDa.id").isNumber());

            // then: il riepilogo dice il conto intero
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.importoTotale").value(360.00))
                    .andExpect(jsonPath("$.data.caparraDovuta").value(108.00))
                    .andExpect(jsonPath("$.data.incassato").value(108.00))
                    .andExpect(jsonPath("$.data.residuo").value(252.00))
                    .andExpect(jsonPath("$.data.saldata").value(false))
                    .andExpect(jsonPath("$.data.pagamenti.length()").value(1));
        }

        @Test
        @DisplayName("due versamenti si sommano, e il secondo salda")
        void registra_caparraESaldo_saldaLaPrenotazione() throws Exception {
            // given: e' il flusso vero di un soggiorno — caparra prima, saldo all'arrivo
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            impostaCaparra(admin, "30.00");
            registra(admin, idPrenotazione, bonifico("108.00"));

            // when
            registra(admin, idPrenotazione, contanti("252.00"));

            // then
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.incassato").value(360.00))
                    .andExpect(jsonPath("$.data.residuo").value(0))
                    .andExpect(jsonPath("$.data.saldata").value(true))
                    .andExpect(jsonPath("$.data.pagamenti.length()").value(2));
        }

        @Test
        @DisplayName("un versamento oltre il residuo e' 409 e non tocca il registro")
        void registra_oltreIlResiduo_risponde409() throws Exception {
            // given: 360 di soggiorno e la digitazione sbagliata che capita davvero
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when
            mockMvc.perform(post(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(bonifico("3600.00"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: niente e' stato scritto
            impostaCaparra(admin, "0.00");
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(jsonPath("$.data.incassato").value(0))
                    .andExpect(jsonPath("$.data.pagamenti.length()").value(0));
        }

        @Test
        @DisplayName("un importo a zero e' 400: lo ferma il contratto, prima del Service")
        void registra_importoZero_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: exclusiveMinimum nello spec, cioe' la validazione del DTO
            mockMvc.perform(post(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(bonifico("0.00"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.importo").exists());
        }

        @Test
        @DisplayName("un cliente non registra incassi nemmeno sulla propria prenotazione")
        void registra_daCliente_risponde403() throws Exception {
            // given
            String admin = tokenAdmin();
            String cliente = tokenCliente();
            long idPrenotazione = prenotazioneConfermataDi(admin, cliente);

            // when/then: 403 e non 401 — l'account e' autenticato, e' il ruolo a non
            // bastare. Lo ferma @PreAuthorize prima ancora del Service
            mockMvc.perform(post(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(bonifico("108.00"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("senza token e' 401")
        void registra_senzaToken_risponde401() throws Exception {
            // given
            long idPrenotazione = prenotazioneConfermata(tokenAdmin());

            // when/then
            mockMvc.perform(post(pagamentiDi(idPrenotazione))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(bonifico("108.00"))))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/prenotazioni/{id}/pagamenti")
    class Elenca {

        @Test
        @DisplayName("una prenotazione senza versamenti e' 200 con l'elenco vuoto")
        void elenca_senzaPagamenti_risponde200() throws Exception {
            // given: "non ha ancora pagato niente" e' una risposta, non un errore — ed e'
            // anzi la lettura che serve a chi deve chiedere la caparra
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            impostaCaparra(admin, "50.00");

            // when/then
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Pagamenti della prenotazione"))
                    .andExpect(jsonPath("$.data.caparraDovuta").value(180.00))
                    .andExpect(jsonPath("$.data.incassato").value(0))
                    .andExpect(jsonPath("$.data.residuo").value(360.00))
                    .andExpect(jsonPath("$.data.pagamenti").isArray())
                    .andExpect(jsonPath("$.data.pagamenti.length()").value(0));
        }

        @Test
        @DisplayName("il cliente vede i propri pagamenti")
        void elenca_daClienteProprietario_risponde200() throws Exception {
            // given: sono i suoi soldi
            String admin = tokenAdmin();
            String cliente = tokenCliente();
            long idPrenotazione = prenotazioneConfermataDi(admin, cliente);
            impostaCaparra(admin, "0.00");
            registra(admin, idPrenotazione, contanti("60.00"));

            // when/then
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.incassato").value(60.00))
                    .andExpect(jsonPath("$.data.pagamenti[0].metodo").value("CONTANTI"));
        }

        @Test
        @DisplayName("la prenotazione di un altro cliente e' 404 e non 403")
        void elenca_daClienteEstraneo_risponde404() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermataDi(admin, tokenCliente());

            // when/then: un 403 direbbe "esiste, ma non e' tua"
            mockMvc.perform(get(pagamentiDi(idPrenotazione))
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("una prenotazione che non esiste e' 404")
        void elenca_prenotazioneInesistente_risponde404() throws Exception {
            mockMvc.perform(get(pagamentiDi(999_999L))
                            .header("Authorization", "Bearer " + tokenAdmin()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("La percentuale della caparra")
    class Caparra {

        @Test
        @DisplayName("l'ADMIN la configura e torna nelle impostazioni")
        void aggiorna_percentuale_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();

            // when
            impostaCaparra(admin, "25.50");

            // then: e' una colonna NUMERIC(5,2), e i due decimali arrivano fino in fondo
            mockMvc.perform(get(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.percentualeCaparra").value(25.50));
        }

        @Test
        @DisplayName("oltre cento e' 400: lo ferma il contratto")
        void aggiorna_percentualeOltreCento_risponde400() throws Exception {
            // given/when/then: piu' del totale non e' una caparra
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.impostazioniHotelRequest()
                                    .percentualeCaparra(new BigDecimal("101")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.percentualeCaparra").exists());
        }

        @Test
        @DisplayName("con tre decimali e' 400: la colonna ne tiene due")
        void aggiorna_percentualeConTreDecimali_risponde400() throws Exception {
            // given/when/then: Postgres troncherebbe in silenzio, cioe' la risposta
            // direbbe un numero e il database ne conserverebbe un altro
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.impostazioniHotelRequest()
                                    .percentualeCaparra(new BigDecimal("30.005")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("due decimali")));
        }
    }

    // ---------------------------------------------------------------- supporto

    private String pagamentiDi(long idPrenotazione) {
        return PRENOTAZIONI + "/" + idPrenotazione + "/pagamenti";
    }

    private PagamentoRequest bonifico(String importo) {
        return new PagamentoRequest()
                .importo(new BigDecimal(importo))
                .metodo(MetodoPagamento.BONIFICO)
                .riferimento("CRO 1234567890")
                .incassatoIl(OffsetDateTime.now().minusDays(1));
    }

    private PagamentoRequest contanti(String importo) {
        return new PagamentoRequest()
                .importo(new BigDecimal(importo))
                .metodo(MetodoPagamento.CONTANTI);
    }

    private void registra(String token, long idPrenotazione, PagamentoRequest richiesta) throws Exception {
        mockMvc.perform(post(pagamentiDi(idPrenotazione))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated());
    }

    /**
     * Scrive la percentuale della caparra sull'unica riga delle impostazioni.
     *
     * <p>Va chiamata <b>nello stesso test</b> che poi legge un riepilogo: la riga e' una
     * per tutta la suite, quindi fidarsi di quel che c'e' vuol dire dipendere dall'ordine
     * di esecuzione.
     */
    private void impostaCaparra(String tokenAdmin, String percentuale) throws Exception {
        mockMvc.perform(put(IMPOSTAZIONI)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.impostazioniHotelRequest()
                                .percentualeCaparra(new BigDecimal(percentuale)))))
                .andExpect(status().isOk());
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

    /** Una prenotazione confermata di tre notti a 120: 360 in tutto. */
    private long prenotazioneConfermata(String tokenAdmin) throws Exception {
        return prenotazioneConfermataDi(tokenAdmin, tokenCliente());
    }

    private long prenotazioneConfermataDi(String tokenAdmin, String tokenCliente) throws Exception {
        long idTipologia = tipologiaPrenotabile(tokenAdmin);
        LocalDate arrivo = dati.dataArrivoDefault();

        PrenotazioneRequest richiesta = dati.prenotazioneRequest(idTipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(arrivo.plusDays(3));

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long id = objectMapper.readTree(risposta).path("data").path("id").asLong();

        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/conferma")
                        .header("Authorization", "Bearer " + tokenCliente))
                .andExpect(status().isOk());

        return id;
    }

    /**
     * Una tipologia con una camera dentro e il prezzo fissato a 120.
     *
     * <p><b>Il prezzo non e' quello univoco della fabbrica</b>, al contrario del nome:
     * qui i conti si verificano sui numeri, e un prezzo che cambia ad ogni esecuzione
     * renderebbe impossibile scrivere 360 in un'asserzione.
     */
    private long tipologiaPrenotabile(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest().prezzoNotte(PREZZO_NOTTE))))
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
}
