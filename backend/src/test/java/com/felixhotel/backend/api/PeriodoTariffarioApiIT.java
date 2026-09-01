package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.dto.PrezzoGiorno;
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
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione del calendario dei prezzi di una tipologia.
 *
 * <p><b>L'isolamento e' quello ordinario</b>, al contrario di
 * {@code DisponibilitaApiIT}: ogni test si crea la propria tipologia e ci guarda
 * dentro, quindi le tariffe degli altri test non entrano mai nella risposta —
 * l'elenco e' indirizzato per id di tipologia, non sul catalogo intero.
 *
 * <p><b>Le date sono lontane nel futuro e diverse test per test.</b> Non e' una
 * precauzione contro gli altri test — la tipologia e' propria — ma contro
 * l'unica cosa che qui puo' cambiare da un'esecuzione all'altra: il giorno in
 * cui la suite gira. I test che verificano un prezzo per giorno della settimana
 * partono da una data di cui si sa il giorno, e lo si ottiene chiedendolo a
 * {@link LocalDate}, non contandolo a mano.
 */
@DisplayName("API dei periodi tariffari")
class PeriodoTariffarioApiIT extends IntegrationTestBase {

    private static final String TIPOLOGIE = "/api/tipologie-camera";

    @Autowired
    private CreatoreStaff creatoreStaff;

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

    /** Una tipologia vuota su cui appendere i periodi. */
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

    private String tariffe(long idTipologia) {
        return TIPOLOGIE + "/" + idTipologia + "/tariffe";
    }

    /** Crea un periodo e restituisce il suo id. */
    private long creaPeriodo(String tokenAdmin, long idTipologia,
                             PeriodoTariffarioRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(tariffe(idTipologia))
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private LocalDate fraSettimane(int settimane) {
        return LocalDate.now().plusWeeks(settimane);
    }

    @Nested
    @DisplayName("POST /api/tipologie-camera/{id}/tariffe")
    class Creazione {

        @Test
        @DisplayName("un ADMIN crea un periodo e la risposta lo restituisce intero")
        void crea_daAdmin_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(30);

            // when
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(20))
                                    .soggiornoMinimo(3))))
                    // then: 201 e la busta standard, col periodo dentro 'data'
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("Periodo tariffario creato"))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.nome").value("Alta stagione"))
                    .andExpect(jsonPath("$.data.dataInizio").value(inizio.toString()))
                    .andExpect(jsonPath("$.data.prezzoNotte").value(180.00))
                    .andExpect(jsonPath("$.data.soggiornoMinimo").value(3))
                    .andExpect(jsonPath("$.data.prezziGiorno").isEmpty());
        }

        @Test
        @DisplayName("un periodo di un giorno solo e' legittimo: e' la notte di Capodanno")
        void crea_conUnaNotteSola_risponde201() throws Exception {
            // given: data di inizio e di fine coincidenti
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate notte = fraSettimane(31);

            // when/then: gli estremi sono compresi tutti e due, quindi questa e' la
            // notte di 'notte' — chi arriva quel giorno e parte il successivo
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(notte, notte))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.dataFine").value(notte.toString()));
        }

        @Test
        @DisplayName("con la fine prima dell'inizio risponde 400, non 500 dal CHECK del database")
        void crea_conDateInvertite_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(32);

            // when/then: il CHECK del V9 esiste, ma e' la rete — arrivarci darebbe un
            // 500 su un errore che era di chi chiama
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.minusDays(1)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("due periodi sovrapposti sulla stessa tipologia sono 409, e il messaggio dice con chi")
        void crea_conDateSovrapposte_risponde409() throws Exception {
            // given: un periodo che copre dieci giorni
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(33);
            creaPeriodo(admin, tipologia, dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                    .nome("Ponte di primavera"));

            // when: un secondo periodo che ne tocca l'ultimo giorno
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(
                                    inizio.plusDays(10), inizio.plusDays(20)))))
                    // then: 409. Un giorno, un prezzo — senza, "quanto costa quella notte"
                    // avrebbe due risposte. Il nome nel messaggio serve a chi configura:
                    // ha sotto gli occhi delle etichette, non degli id
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Ponte di primavera")));
        }

        @Test
        @DisplayName("periodi identici su due tipologie diverse convivono: l'alta stagione e' la stessa per tutte")
        void crea_stesseDateSuTipologieDiverse_risponde201() throws Exception {
            // given: due tipologie e le stesse identiche date
            String admin = tokenAdmin();
            long prima = creaTipologia(admin);
            long seconda = creaTipologia(admin);
            LocalDate inizio = fraSettimane(34);
            creaPeriodo(admin, prima, dati.periodoTariffarioRequest(inizio, inizio.plusDays(10)));

            // when/then: nessun conflitto. Il vincolo di esclusione del V9 e' su
            // (tipologia, intervallo), non sul solo intervallo, ed e' il caso normale
            mockMvc.perform(post(tariffe(seconda))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10)))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("lo stesso giorno della settimana due volte e' 400")
        void crea_conGiornoDuplicato_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(35);

            // when: sabato compare due volte con due prezzi
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                                    .prezziGiorno(List.of(
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                                    .prezzo(new BigDecimal("200.00")),
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                                    .prezzo(new BigDecimal("210.00")))))))
                    // then: 400 e non 409 — non c'e' nessuno stato con cui la richiesta
                    // confligge, e' la richiesta a contraddirsi
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("un prezzo con tre decimali e' 400 invece di essere arrotondato di nascosto")
        void crea_conTreDecimali_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(36);

            // when/then: la colonna e' NUMERIC(10,2) e Postgres troncherebbe in
            // silenzio, cioe' la stessa risorsa direbbe due prezzi diversi a seconda di
            // quando la si chiede
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(3))
                                    .prezzoNotte(new BigDecimal("180.005")))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("su una tipologia che non esiste risponde 404")
        void crea_suTipologiaInesistente_risponde404() throws Exception {
            // given
            String admin = tokenAdmin();
            LocalDate inizio = fraSettimane(37);

            // when/then
            mockMvc.perform(post(tariffe(999_999L))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(3)))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("uno STAFF non puo' creare tariffe: decidere i prezzi non e' un'operazione di turno")
        void crea_daStaff_risponde403() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(38);

            // when/then: 403. Lo STAFF le legge — deve poterle dire al telefono — ma
            // deciderle e' una scelta commerciale, come pubblicare le foto
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(3)))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/tipologie-camera/{id}/tariffe")
    class Elenco {

        @Test
        @DisplayName("l'elenco e' ordinato per data di inizio, non per quando sono stati creati")
        void elenco_ordinaPerDataInizio() throws Exception {
            // given: due periodi creati al contrario di come stanno nel calendario
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate autunno = fraSettimane(45);
            LocalDate primavera = fraSettimane(39);
            creaPeriodo(admin, tipologia, dati.periodoTariffarioRequest(autunno, autunno.plusDays(5))
                    .nome("Autunno"));
            creaPeriodo(admin, tipologia, dati.periodoTariffarioRequest(primavera, primavera.plusDays(5))
                    .nome("Primavera"));

            // when/then: e' un calendario, e chi lo guarda vuole vedere l'anno scorrere
            mockMvc.perform(get(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].nome").value("Primavera"))
                    .andExpect(jsonPath("$.data[1].nome").value("Autunno"))
                    .andExpect(jsonPath("$.page.totalElements").value(2));
        }

        @Test
        @DisplayName("una tipologia senza periodi da' una pagina vuota, non un 404")
        void elenco_senzaPeriodi_rispondePaginaVuota() throws Exception {
            // given: una tipologia appena creata
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            // when/then: 200 con data vuoto. Vuol dire che si vende al prezzo di
            // listino, ed e' un'informazione diversa da "questa tipologia non esiste"
            mockMvc.perform(get(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }

        @Test
        @DisplayName("su una tipologia che non esiste risponde 404 invece di una pagina vuota")
        void elenco_suTipologiaInesistente_risponde404() throws Exception {
            // when/then: senza questo controllo un id sbagliato e un calendario non
            // ancora configurato darebbero la stessa risposta
            mockMvc.perform(get(tariffe(999_999L))
                            .header("Authorization", "Bearer " + tokenAdmin()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("uno STAFF legge le tariffe: deve poterle dire al telefono")
        void elenco_daStaff_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            // when/then
            mockMvc.perform(get(tariffe(tipologia))
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("un cliente non vede il listino: e' un documento commerciale")
        void elenco_daCliente_risponde403() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            // when/then: 403. La domanda del cliente sui prezzi ha gia' il suo endpoint,
            // GET /api/disponibilita, che risponde per le date che gli interessano
            mockMvc.perform(get(tariffe(tipologia))
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("senza token risponde 401: le tariffe non sono pubbliche come il catalogo")
        void elenco_senzaToken_risponde401() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);

            // when/then
            mockMvc.perform(get(tariffe(tipologia)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT e DELETE /api/tipologie-camera/{id}/tariffe/{tariffaId}")
    class ModificaEdEliminazione {

        @Test
        @DisplayName("la PUT sostituisce i prezzi per giorno invece di aggiungerli")
        void aggiorna_sostituisceIPrezziGiorno() throws Exception {
            // given: un periodo con due giorni cari
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(40);
            long periodo = creaPeriodo(admin, tipologia,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                            .prezziGiorno(List.of(
                                    new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.FRIDAY)
                                            .prezzo(new BigDecimal("210.00")),
                                    new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                            .prezzo(new BigDecimal("210.00")))));

            // when: se ne manda uno solo, e diverso
            mockMvc.perform(put(tariffe(tipologia) + "/" + periodo)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                                    .prezziGiorno(List.of(
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SUNDAY)
                                                    .prezzo(new BigDecimal("190.00")))))))
                    // then: venerdi' e sabato non ci sono piu' e tornano a costare il
                    // prezzo base. Senza orphanRemoval resterebbero in tabella a far
                    // prezzo, cioe' l'operazione non sarebbe ripetibile
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.prezziGiorno.length()").value(1))
                    .andExpect(jsonPath("$.data.prezziGiorno[0].giorno").value("SUNDAY"))
                    .andExpect(jsonPath("$.data.prezziGiorno[0].prezzo").value(190.00));
        }

        @Test
        @DisplayName("i prezzi per giorno escono ordinati da lunedi' a domenica, non in alfabetico")
        void aggiorna_ordinaIGiorniComeLaSettimana() throws Exception {
            // given: tre giorni mandati in ordine sparso, e scelti apposta perche' in
            // alfabetico verrebbero FRIDAY, MONDAY, SUNDAY — cioe' l'ordine sbagliato
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(41);

            // when
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                                    .prezziGiorno(List.of(
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SUNDAY)
                                                    .prezzo(new BigDecimal("190.00")),
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.FRIDAY)
                                                    .prezzo(new BigDecimal("210.00")),
                                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.MONDAY)
                                                    .prezzo(new BigDecimal("150.00")))))))
                    // then: la settimana, non il dizionario. E' il motivo per cui
                    // l'ordine lo mette il mapper e non un order by: l'enum e' persistito
                    // come stringa, e il database saprebbe ordinare solo i nomi
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.prezziGiorno[0].giorno").value("MONDAY"))
                    .andExpect(jsonPath("$.data.prezziGiorno[1].giorno").value("FRIDAY"))
                    .andExpect(jsonPath("$.data.prezziGiorno[2].giorno").value("SUNDAY"));
        }

        @Test
        @DisplayName("riconfermare a un periodo le proprie date non e' una sovrapposizione")
        void aggiorna_conLeStesseDate_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(42);
            long periodo = creaPeriodo(admin, tipologia,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(10)));

            // when/then: se stesso non si conta, altrimenti correggere il solo prezzo
            // sarebbe impossibile
            mockMvc.perform(put(tariffe(tipologia) + "/" + periodo)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10))
                                    .prezzoNotte(new BigDecimal("199.00")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.prezzoNotte").value(199.00));
        }

        @Test
        @DisplayName("un periodo di un'altra tipologia da' 404 invece di essere modificato")
        void aggiorna_conPeriodoDiAltraTipologia_risponde404() throws Exception {
            // given: il periodo appartiene alla prima tipologia, l'URL nomina la seconda
            String admin = tokenAdmin();
            long prima = creaTipologia(admin);
            long seconda = creaTipologia(admin);
            LocalDate inizio = fraSettimane(43);
            long periodo = creaPeriodo(admin, prima,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(5)));

            // when/then: la tipologia fa parte della chiave di ricerca, non e' un
            // controllo aggiunto dopo
            mockMvc.perform(put(tariffe(seconda) + "/" + periodo)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(5)))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("eliminare un periodo lo toglie dall'elenco insieme ai suoi prezzi per giorno")
        void elimina_toglieIlPeriodo() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(44);
            long periodo = creaPeriodo(admin, tipologia,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(5))
                            .prezziGiorno(List.of(
                                    new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                            .prezzo(new BigDecimal("210.00")))));

            // when
            mockMvc.perform(delete(tariffe(tipologia) + "/" + periodo)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: l'elenco e' vuoto, e quelle date tornano al prezzo di listino —
            // non diventano non prenotabili
            mockMvc.perform(get(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("le date liberate da un'eliminazione tornano riusabili")
        void elimina_liberaLeDate() throws Exception {
            // given: un periodo che occupa un intervallo
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(46);
            long periodo = creaPeriodo(admin, tipologia,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(10)));

            // when: lo si cancella e se ne crea un altro sulle stesse date
            mockMvc.perform(delete(tariffe(tipologia) + "/" + periodo)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            // then: nessun 409. E' la prova che il vincolo di esclusione se ne va con la
            // riga, invece di restare a occupare l'intervallo
            mockMvc.perform(post(tariffe(tipologia))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.periodoTariffarioRequest(inizio, inizio.plusDays(10)))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("uno STAFF non puo' eliminare un periodo")
        void elimina_daStaff_risponde403() throws Exception {
            // given
            String admin = tokenAdmin();
            long tipologia = creaTipologia(admin);
            LocalDate inizio = fraSettimane(47);
            long periodo = creaPeriodo(admin, tipologia,
                    dati.periodoTariffarioRequest(inizio, inizio.plusDays(5)));

            // when/then
            mockMvc.perform(delete(tariffe(tipologia) + "/" + periodo)
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isForbidden());
        }
    }
}
