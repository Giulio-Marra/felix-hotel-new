package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.CanalePrenotazione;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione delle prenotazioni.
 *
 * <p>E' il primo IT del progetto che esercita cose mai provate prima:
 * <ul>
 *   <li>una <b>macchina a stati</b> invece di un CRUD: la stessa chiamata
 *       risponde 200 o 409 a seconda di com'era messa la riga prima;</li>
 *   <li>un permesso che <b>dipende dai dati e non solo dal ruolo</b> — "e'
 *       tua?" — cioe' il primo endpoint senza {@code @PreAuthorize} in cui
 *       l'autorizzazione sta comunque tutta nel Service;</li>
 *   <li>una risorsa che <b>si comporta in due modi secondo chi chiama</b>: gli
 *       stessi due campi sono obbligatori per il personale e vietati al
 *       cliente;</li>
 *   <li>un valore <b>calcolato</b> e non ricevuto (l'importo) e una regola che
 *       dipende da <b>quante altre righe</b> esistono (la disponibilita').</li>
 * </ul>
 *
 * <p><b>Ogni test si costruisce la propria tipologia e le proprie camere</b>, e
 * qui non e' solo buona educazione come altrove: la disponibilita' e' un
 * conteggio su tutte le prenotazioni di una tipologia, quindi due test che ne
 * condividessero una si toglierebbero il posto a vicenda. Con una tipologia
 * per test il conto e' isolato per costruzione.
 */
@DisplayName("API delle prenotazioni")
class PrenotazioneApiIT extends IntegrationTestBase {

    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";
    private static final String ME = "/api/auth/me";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    /**
     * Serve ai soli test sugli stati che nessun endpoint sa ancora produrre —
     * CHECK_IN e CHECK_OUT, che arriveranno col check-in.
     *
     * <p>Passare dal repository e' l'unico modo di metterceli, e vale la pena
     * farlo: quei due stati <b>contano gia' nel calcolo della disponibilita'</b>,
     * quindi senza un test che li ci metta la parte piu' delicata della query
     * resterebbe un'affermazione senza prova.
     */
    @Autowired
    private PrenotazioneRepository prenotazioneRepository;

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

    /** Token di un cliente registrato dal frontoffice (ruolo USER). */
    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        auth.registraAccount(cliente);
        return auth.ottieniToken(cliente.getEmail());
    }

    /**
     * L'id del cliente che possiede quel token, letto da {@code /api/auth/me}.
     *
     * <p>Serve alle prenotazioni registrate dal personale, che devono dire a chi
     * intestarle. Si passa dall'endpoint vero invece che da una query: e' lo
     * stesso modo in cui un frontend lo saprebbe.
     */
    private long idCliente(String tokenCliente) throws Exception {
        String risposta = mockMvc.perform(get(ME)
                        .header("Authorization", "Bearer " + tokenCliente))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

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

    /** Aggiunge {@code quante} camere alla tipologia: e' cio' che rende disponibile una tipologia. */
    private void creaCamere(String tokenAdmin, long idTipologia, int quante) throws Exception {
        for (int i = 0; i < quante; i++) {
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia))))
                    .andExpect(status().isCreated());
        }
    }

    /** Una tipologia con {@code quante} camere, pronta per essere prenotata. */
    private long tipologiaPrenotabile(String tokenAdmin, int quante) throws Exception {
        long idTipologia = creaTipologia(tokenAdmin);
        creaCamere(tokenAdmin, idTipologia, quante);
        return idTipologia;
    }

    private long creaPrenotazione(String token, PrenotazioneRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(PRENOTAZIONI)
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
    @DisplayName("POST /api/prenotazioni")
    class Creazione {

        @Test
        @DisplayName("da un cliente risponde 201 con la busta completa e il totale calcolato")
        void creazione_daCliente_risponde201() throws Exception {
            // given: una tipologia da 120 euro con una camera
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when: tre notti
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    // then: la busta standard, lo stato di partenza e l'importo che il client
                    // non ha mandato — 120 x 3, calcolato qui e fotografato adesso
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.stato").value("IN_ATTESA"))
                    .andExpect(jsonPath("$.data.canale").value("ONLINE"))
                    .andExpect(jsonPath("$.data.importoTotale").value(360.00))
                    .andExpect(jsonPath("$.data.numeroOspiti").value(2))
                    .andExpect(jsonPath("$.data.utente.email").exists())
                    .andExpect(jsonPath("$.data.tipologia.id").value(idTipologia))
                    // gestitaDa e' null: nessuno del personale l'ha presa
                    .andExpect(jsonPath("$.data.gestitaDa").doesNotExist())
                    .andExpect(jsonPath("$.data.motivoCancellazione").doesNotExist());
        }

        @Test
        @DisplayName("senza token risponde 401")
        void creazione_senzaToken_risponde401() throws Exception {
            // given: nessun path delle prenotazioni e' in permitAll

            // when/then
            mockMvc.perform(post(PRENOTAZIONI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(1L))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("da un cliente che indica utenteId risponde 400")
        void creazione_daClienteConUtenteId_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when: prova a intestarla a un altro
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia).utenteId(1L))))
                    // then: rifiutata a voce alta, non ignorata in silenzio
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("dal personale intesta al cliente e registra chi l'ha presa")
        void creazione_daPersonale_registraIlGestore() throws Exception {
            // given: uno staff, un cliente e una tipologia prenotabile
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idCliente = idCliente(cliente);
            String staff = tokenStaff();

            // when: la registra al telefono
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .utenteId(idCliente)
                                    .canale(CanalePrenotazione.TELEFONO))))
                    // then: due persone diverse sulla stessa riga — di chi e' e chi l'ha presa
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.utente.id").value(idCliente))
                    .andExpect(jsonPath("$.data.canale").value("TELEFONO"))
                    .andExpect(jsonPath("$.data.gestitaDa.nome").value("Anna"))
                    // lo staff non porta l'email nella sintesi, al contrario del cliente:
                    // li' serve a contattarlo, qui non servirebbe a niente
                    .andExpect(jsonPath("$.data.gestitaDa.email").doesNotExist());
        }

        @Test
        @DisplayName("dal personale senza canale risponde 400")
        void creazione_daPersonaleSenzaCanale_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idCliente = idCliente(cliente);
            String staff = tokenStaff();

            // when/then: il canale e' obbligatorio per chi registra
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia).utenteId(idCliente))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con partenza prima dell'arrivo risponde 400 e non 500")
        void creazione_conDateInvertite_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when: partenza prima dell'arrivo
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(LocalDate.now().plusDays(10))
                                    .dataCheckOut(LocalDate.now().plusDays(7)))))
                    // then: 400. Il CHECK del database c'e' comunque, ma se fosse lui a
                    // parlare il client si sentirebbe rispondere che il guasto e' nostro
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("con arrivo nel passato risponde 400")
        void creazione_conArrivoPassato_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when/then
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(LocalDate.now().minusDays(1))
                                    .dataCheckOut(LocalDate.now().plusDays(2)))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con piu' ospiti della capienza risponde 400")
        void creazione_conTroppiOspiti_risponde400() throws Exception {
            // given: la tipologia di serie ospita due persone
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when/then: il tetto dipende dalla tipologia, quindi non lo puo' fermare lo
            // schema — che accetta qualsiasi numero fino a 50
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia).numeroOspiti(3))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con zero ospiti lo ferma la validazione dello schema, con la mappa dei campi")
        void creazione_conZeroOspiti_risponde400ConMappa() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();

            // when: zero ospiti viola il minimum dello schema
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia).numeroOspiti(0))))
                    // then: qui 'data' porta la mappa campo -> messaggio, che e' l'unica
                    // eccezione alla regola "data null se errore"
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.numeroOspiti").exists());
        }

        @Test
        @DisplayName("con tipologia inesistente risponde 400 e non 404")
        void creazione_conTipologiaInesistente_risponde400() throws Exception {
            // given
            String cliente = tokenCliente();

            // when/then: il 404 di questi endpoint significa "questa prenotazione non
            // esiste", e riusarlo per un id nel corpo confonderebbe due errori diversi
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(999_999_999L))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("disponibilita'")
    class Disponibilita {

        @Test
        @DisplayName("con l'unica camera gia' confermata negli stessi giorni risponde 409")
        void creazione_senzaCamereLibere_risponde409() throws Exception {
            // given: una tipologia con UNA camera, e una prenotazione gia' confermata
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/conferma")
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk());

            // when: un secondo cliente chiede gli stessi giorni
            String secondo = tokenCliente();
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + secondo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    // then: 409 — la richiesta e' ben formata, e' il mondo che non la
                    // permette
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("una IN_ATTESA non toglie il posto a nessuno")
        void creazione_conCarrelloAltrui_risponde201() throws Exception {
            // given: una camera sola e un primo cliente che ha solo riempito il carrello
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            // when: un secondo cliente chiede gli stessi giorni
            String secondo = tokenCliente();
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + secondo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    // then: passa. Il carrello e' un appunto personale, e a prendersi la
                    // camera sara' quello dei due che conferma per primo
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("il giorno di partenza non e' occupato: chi arriva quel giorno passa")
        void creazione_arrivoNelGiornoDiPartenzaAltrui_risponde201() throws Exception {
            // given: una camera sola, occupata dal giorno 7 al giorno 10
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/conferma")
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk());

            // when: un secondo cliente arriva il 10, il giorno in cui il primo parte
            String secondo = tokenCliente();
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + secondo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(LocalDate.now().plusDays(10))
                                    .dataCheckOut(LocalDate.now().plusDays(12)))))
                    // then: passa. E' la disuguaglianza stretta nella query: con un <= si
                    // perderebbe una notte vendibile ad ogni cambio di ospite
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("una CHECK_IN occupa come una CONFERMATA")
        void creazione_conSoggiornoInCorso_risponde409() throws Exception {
            // given: una camera sola e una prenotazione portata a CHECK_IN dal
            // repository — nessun endpoint sa ancora produrre quello stato, ma la query
            // di disponibilita' lo conta gia' e questo test e' l'unico posto che lo prova
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            Prenotazione inCorso = prenotazioneRepository.findById(idPrima).orElseThrow();
            inCorso.setStato(StatoPrenotazione.CHECK_IN);
            prenotazioneRepository.save(inCorso);

            // when: un altro cliente chiede gli stessi giorni
            String secondo = tokenCliente();
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + secondo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    // then: 409. Una stanza con qualcuno dentro non e' libera, ed e' il
                    // motivo per cui CHECK_IN sta nell'elenco degli stati che occupano
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("una annullata restituisce il posto")
        void creazione_dopoAnnullamento_risponde201() throws Exception {
            // given: una camera sola, prenotata e confermata da un primo cliente
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/conferma")
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk());

            // when: il primo annulla, poi arriva un secondo
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/annullamento")
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk());

            String secondo = tokenCliente();
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + secondo)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    // then: la camera e' tornata prenotabile
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET /api/prenotazioni")
    class Lettura {

        @Test
        @DisplayName("a un cliente mostra solo le proprie")
        void elenco_daCliente_mostraSoloLeProprie() throws Exception {
            // given: due clienti, una prenotazione a testa sulla stessa tipologia
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 2);

            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            String secondo = tokenCliente();
            creaPrenotazione(secondo, dati.prenotazioneRequest(idTipologia));

            // when: il primo guarda il proprio elenco
            String risposta = mockMvc.perform(get(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.pageNumber").value(0))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // then: c'e' la sua e basta. L'ambito non e' un parametro che ha mandato:
            // e' il recinto, e lo decide il token
            var elenco = objectMapper.readTree(risposta).path("data");
            assertThat(elenco).hasSize(1);
            assertThat(elenco.get(0).path("id").asLong()).isEqualTo(idPrima);
        }

        @Test
        @DisplayName("al personale mostra anche quelle degli altri")
        void elenco_daPersonale_mostraTutte() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when: uno staff apre l'elenco
            mockMvc.perform(get(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .param("stato", "IN_ATTESA"))
                    // then: vede roba che non e' sua. Non si asserisce quante siano — il
                    // database e' condiviso e altre ne hanno lasciate altri test — ma che
                    // l'elenco non sia vuoto, che e' la differenza che conta
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").exists());
        }

        @Test
        @DisplayName("con uno stato inesistente risponde 400")
        void elenco_conStatoInventato_risponde400() throws Exception {
            // given/when/then: uno stato fuori dall'elenco e' un valore che non esiste,
            // non un filtro che non trova niente
            mockMvc.perform(get(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + tokenCliente())
                            .param("stato", "PAGATA"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("la prenotazione di un altro cliente risponde 404 e non 403")
        void dettaglio_diUnAltroCliente_risponde404() throws Exception {
            // given: una prenotazione del primo cliente
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            // when: la chiede un altro cliente
            String secondo = tokenCliente();
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrima)
                            .header("Authorization", "Bearer " + secondo))
                    // then: 404, la stessa risposta di un id inventato. Un 403 direbbe
                    // "esiste ma non e' tua", cioe' lascerebbe enumerare gli id provandoli
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("al personale mostra la prenotazione di chiunque")
        void dettaglio_daPersonale_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when/then
            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione)
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(idPrenotazione));
        }
    }

    @Nested
    @DisplayName("PUT /api/prenotazioni/{id}/conferma")
    class Conferma {

        @Test
        @DisplayName("da IN_ATTESA risponde 200 e porta a CONFERMATA")
        void conferma_daInAttesa_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when/then
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.stato").value("CONFERMATA"));
        }

        @Test
        @DisplayName("ripetuta risponde 409: non e' idempotente")
        void conferma_ripetuta_risponde409() throws Exception {
            // given: una prenotazione gia' confermata
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk());

            // when/then: 409. E' la differenza rispetto al cambio di stato di una camera,
            // che invece si puo' ripetere senza conseguenze
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("se il posto e' finito nel frattempo risponde 409 e la lascia IN_ATTESA")
        void conferma_senzaPosto_lasciaInAttesa() throws Exception {
            // given: una camera sola e due carrelli sugli stessi giorni — la situazione
            // che il doppio controllo esiste per governare
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            String secondo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));
            long idSeconda = creaPrenotazione(secondo, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/conferma")
                            .header("Authorization", "Bearer " + primo))
                    .andExpect(status().isOk());

            // when: prova a confermare anche il secondo
            mockMvc.perform(put(PRENOTAZIONI + "/" + idSeconda + "/conferma")
                            .header("Authorization", "Bearer " + secondo))
                    // then: 409 — chi ha messo nel carrello per primo non ha precedenza su
                    // chi conferma per primo
                    .andExpect(status().isConflict());

            // then: e la sua prenotazione non e' andata perduta, e' ancora IN_ATTESA
            mockMvc.perform(get(PRENOTAZIONI + "/" + idSeconda)
                            .header("Authorization", "Bearer " + secondo))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stato").value("IN_ATTESA"));
        }

        @Test
        @DisplayName("su una prenotazione altrui risponde 404")
        void conferma_altrui_risponde404() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String primo = tokenCliente();
            long idPrima = creaPrenotazione(primo, dati.prenotazioneRequest(idTipologia));

            // when/then: non basta indovinare l'id
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrima + "/conferma")
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PUT /api/prenotazioni/{id}/annullamento")
    class Annullamento {

        @Test
        @DisplayName("con motivo risponde 200 e lo registra insieme all'istante")
        void annullamento_conMotivo_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.annullamentoRequest("Cambio di programma"))))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stato").value("ANNULLATA"))
                    .andExpect(jsonPath("$.data.motivoCancellazione").value("Cambio di programma"))
                    .andExpect(jsonPath("$.data.dataCancellazione").exists());
        }

        @Test
        @DisplayName("senza corpo risponde 200 lo stesso")
        void annullamento_senzaCorpo_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when: nessun body, che nello spec e' legittimo
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + cliente))
                    // then: annullata comunque, senza motivo. Obbligare a scriverne uno
                    // produrrebbe solo stringhe come "annullata"
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stato").value("ANNULLATA"))
                    .andExpect(jsonPath("$.data.motivoCancellazione").doesNotExist());
        }

        @Test
        @DisplayName("ripetuto risponde 409")
        void annullamento_ripetuto_risponde409() throws Exception {
            // given: una prenotazione gia' annullata
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk());

            // when/then
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("dal personale su una prenotazione altrui risponde 200")
        void annullamento_daPersonale_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 1);
            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia));

            // when/then: e' il caso del cliente che telefona per disdire
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.annullamentoRequest("Disdetta telefonica"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.stato").value("ANNULLATA"));
        }
    }
}
