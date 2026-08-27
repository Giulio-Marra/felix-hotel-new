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
            creaCamera(tokenAdmin, idTipologia);
        }
    }

    /**
     * Una camera sola, restituendone l'id.
     *
     * <p>Serve al check-in, che al contrario di tutto il resto ha bisogno di
     * sapere <b>quale</b> stanza e' stata creata: per nominarla in una richiesta,
     * per guardarne lo stato operativo dopo, o per assegnarla a due prenotazioni
     * diverse e vedere che la seconda venga rifiutata.
     */
    private long creaCamera(String tokenAdmin, long idTipologia) throws Exception {
        String risposta = mockMvc.perform(post(CAMERE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(idTipologia))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    /**
     * Lo stato operativo di una camera, letto dall'endpoint vero.
     *
     * <p>E' il modo di verificare l'<b>effetto collaterale</b> del check-in senza
     * andarselo a leggere dal database: se lo si guardasse col repository si
     * proverebbe che il service ha scritto qualcosa, non che l'applicazione lo
     * racconta.
     */
    private String statoCamera(String tokenStaff, long idCamera) throws Exception {
        String risposta = mockMvc.perform(get(CAMERE + "/" + idCamera)
                        .header("Authorization", "Bearer " + tokenStaff))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("stato").asText();
    }

    /**
     * Una prenotazione confermata che <b>comincia oggi</b>, cioe' la sola su cui
     * si possa registrare un arrivo.
     *
     * <p>Il resto dell'IT prenota nel futuro, che e' il caso valido per creazione
     * e conferma; per il check-in il futuro e' proprio il caso da rifiutare, e
     * questi test girano sull'orologio vero — nel contesto Spring non c'e'
     * nessun {@code OrologioPilotato}, che vive solo negli unitari.
     */
    private long confermataDiOggi(long idTipologia) throws Exception {
        String cliente = tokenCliente();
        long id = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia)
                .dataCheckIn(LocalDate.now())
                .dataCheckOut(LocalDate.now().plusDays(3)));
        conferma(cliente, id);
        return id;
    }

    /** Una tipologia con {@code quante} camere, pronta per essere prenotata. */
    private long tipologiaPrenotabile(String tokenAdmin, int quante) throws Exception {
        long idTipologia = creaTipologia(tokenAdmin);
        creaCamere(tokenAdmin, idTipologia, quante);
        return idTipologia;
    }

    /** Conferma una prenotazione col token di chi puo' farlo. */
    private void conferma(String token, long idPrenotazione) throws Exception {
        mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Prenota e conferma in un colpo: e' il modo in cui si occupa davvero una camera. */
    private void occupa(String tokenAdmin, long idTipologia, LocalDate arrivo, LocalDate partenza)
            throws Exception {
        String cliente = tokenCliente();
        long id = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(partenza));
        conferma(cliente, id);
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
        @DisplayName("tre soggiorni brevi in fila non bloccano un soggiorno lungo: si conta la notte peggiore")
        void creazione_conSoggiorniConsecutivi_risponde201() throws Exception {
            // given: DUE camere, e tre confermate una di seguito all'altra — 1->2, 2->3,
            // 3->4. Sono consecutive e non si sovrappongono fra loro (il giorno di
            // partenza non e' occupato), quindi stanno tutte e tre in UNA camera sola e
            // la seconda resta libera tutte le notti
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 2);

            LocalDate primoGiorno = LocalDate.now().plusDays(7);
            occupa(admin, idTipologia, primoGiorno, primoGiorno.plusDays(1));
            occupa(admin, idTipologia, primoGiorno.plusDays(1), primoGiorno.plusDays(2));
            occupa(admin, idTipologia, primoGiorno.plusDays(2), primoGiorno.plusDays(3));

            // when: qualcuno chiede tutte e quattro le notti insieme
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + tokenCliente())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(primoGiorno)
                                    .dataCheckOut(primoGiorno.plusDays(4)))))
                    // then: 201. Contando le prenotazioni che toccano il periodo sarebbero
                    // tre su due camere, cioe' un 409 — ed e' esattamente il difetto che
                    // questo branch e' venuto a correggere. La domanda giusta e' quante
                    // camere servono nella notte PEGGIORE, e in nessuna notte ne servono
                    // piu' di una
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201));
        }

        @Test
        @DisplayName("la notte peggiore e' quella che decide, anche quando cade in mezzo al periodo")
        void creazione_conPuntaInMezzoAlPeriodo_risponde409() throws Exception {
            // given: DUE camere. Due confermate che si accavallano fra loro, ma solo nel
            // mezzo del periodo che verra' chiesto: 2->4 e 3->5
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin, 2);

            LocalDate primoGiorno = LocalDate.now().plusDays(7);
            occupa(admin, idTipologia, primoGiorno.plusDays(1), primoGiorno.plusDays(3));
            occupa(admin, idTipologia, primoGiorno.plusDays(2), primoGiorno.plusDays(4));

            // when: qualcuno chiede dal primo giorno al quinto
            mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + tokenCliente())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(primoGiorno)
                                    .dataCheckOut(primoGiorno.plusDays(5)))))
                    // then: 409. La prima notte e' libera e l'ultima pure, ma nella notte
                    // fra il terzo e il quarto giorno le due confermate coesistono e
                    // riempiono l'albergo. E' il caso simmetrico del test qui sopra: il
                    // massimo va cercato dentro il periodo, non solo al suo inizio
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
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
    @DisplayName("PUT /api/prenotazioni/{id}/check-in")
    class Arrivo {

        @Test
        @DisplayName("dal personale assegna una camera, la porta a OCCUPATA e passa a CHECK_IN")
        void checkIn_dalPersonale_assegnaEOccupa() throws Exception {
            // given: una tipologia con una camera sola e una prenotazione che comincia oggi
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            long idCamera = creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);
            String staff = tokenStaff();

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + staff))
                    // then: la busta porta lo stato nuovo e la camera assegnata, che fino a
                    // un momento fa era null
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Check-in registrato"))
                    .andExpect(jsonPath("$.data.stato").value("CHECK_IN"))
                    .andExpect(jsonPath("$.data.camera.id").value(idCamera))
                    .andExpect(jsonPath("$.data.camera.numero").isNotEmpty())
                    .andExpect(jsonPath("$.data.camera.tipologia.id").value(idTipologia));

            // e l'effetto collaterale c'e' davvero: la stanza risulta occupata anche a
            // chi la guarda dall'inventario, che e' l'unico posto da cui se ne accorge
            // chi non ha visto passare questa richiesta
            assertThat(statoCamera(staff, idCamera)).isEqualTo("OCCUPATA");
        }

        @Test
        @DisplayName("da un cliente risponde 403 anche sulla propria prenotazione")
        void checkIn_daUnCliente_risponde403() throws Exception {
            // given: il cliente e' il titolare, quindi "e' tua?" direbbe di si'
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);

            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia)
                    .dataCheckIn(LocalDate.now())
                    .dataCheckOut(LocalDate.now().plusDays(3)));
            conferma(cliente, idPrenotazione);

            // when/then: 403 e non 404, ed e' la differenza rispetto a tutti gli altri
            // endpoint delle prenotazioni. Qui la domanda torna a essere "che ruolo hai":
            // non e' il cliente a consegnarsi le chiavi, e dirglielo non rivela niente
            // che non sappia gia'
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("senza token risponde 401")
        void checkIn_senzaToken_risponde401() throws Exception {
            mockMvc.perform(put(PRENOTAZIONI + "/1/check-in"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("con una camera indicata assegna quella e non un'altra")
        void checkIn_conCameraIndicata_assegnaQuella() throws Exception {
            // given: due camere disponibili, e chi sta al banco vuole la seconda
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);
            long voluta = creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + voluta + "}"))
                    // then: senza il corpo il service avrebbe scelto la prima in ordine di
                    // numero, che qui non e' questa
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.camera.id").value(voluta));
        }

        @Test
        @DisplayName("l'upgrade e' permesso: camera di un'altra tipologia, tipologia e importo invariati")
        void checkIn_conCameraDiAltraTipologia_assegnaSenzaCambiarePrezzo() throws Exception {
            // given: il cliente ha comprato la tipologia A, al banco gli danno una stanza
            // della tipologia B
            String admin = tokenAdmin();
            long comprata = creaTipologia(admin);
            creaCamera(admin, comprata);
            long altra = creaTipologia(admin);
            long cameraAltra = creaCamera(admin, altra);
            long idPrenotazione = confermataDiOggi(comprata);

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + cameraAltra + "}"))
                    // then: la camera e' dell'altra tipologia, ma cosa il cliente ha comprato
                    // e quanto ha pagato non cambiano. E' anche il motivo per cui la camera
                    // porta con se' la propria tipologia: e' qui che le due si vedono diverse
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.camera.id").value(cameraAltra))
                    .andExpect(jsonPath("$.data.camera.tipologia.id").value(altra))
                    .andExpect(jsonPath("$.data.tipologia.id").value(comprata));
        }

        @Test
        @DisplayName("due prenotazioni sovrapposte ricevono due camere diverse")
        void checkIn_diDueSovrapposte_assegnaCamereDiverse() throws Exception {
            // given: due camere, due prenotazioni negli stessi giorni
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamere(admin, idTipologia, 2);
            long prima = confermataDiOggi(idTipologia);
            long seconda = confermataDiOggi(idTipologia);
            String staff = tokenStaff();

            // when
            long cameraPrima = idCameraAssegnata(staff, prima);
            long cameraSeconda = idCameraAssegnata(staff, seconda);

            // then: e' la prova che l'assegnazione guarda le prenotazioni gia' arrivate e
            // non solo lo stato operativo. Senza quel controllo la seconda riceverebbe la
            // stessa stanza — che nel frattempo e' OCCUPATA, quindi in realta' la
            // filtrerebbe anche lo stato: il test vale perche' le due condizioni
            // insieme non lasciano scampo, ed e' il risultato che conta
            assertThat(cameraPrima).isNotEqualTo(cameraSeconda);
        }

        @Test
        @DisplayName("con la camera indicata gia' impegnata in quei giorni risponde 409")
        void checkIn_conCameraGiaImpegnata_risponde409() throws Exception {
            // given: una camera gia' assegnata a un ospite arrivato, e una seconda
            // prenotazione che la chiede
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            long idCamera = creaCamera(admin, idTipologia);
            creaCamera(admin, idTipologia);
            String staff = tokenStaff();

            long prima = confermataDiOggi(idTipologia);
            mockMvc.perform(put(PRENOTAZIONI + "/" + prima + "/check-in")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + idCamera + "}"))
                    .andExpect(status().isOk());

            long seconda = confermataDiOggi(idTipologia);

            // when/then
            mockMvc.perform(put(PRENOTAZIONI + "/" + seconda + "/check-in")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + idCamera + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("una camera rimessa LIBERA a mano con dentro un ospite non viene riassegnata")
        void checkIn_conStatoRimessoAMano_nonRiassegnaLaStanzaAbitata() throws Exception {
            // given: due camere, cosi' che una seconda prenotazione negli stessi giorni
            // si possa creare — con una sola sarebbe la disponibilita' a fermarla, che e'
            // un'altra regola e non quella sotto esame. Un ospite entra nella prima, poi
            // qualcuno sbagliando la riporta a LIBERA dall'inventario: quell'endpoint e'
            // idempotente e senza macchina a stati di proposito, quindi niente glielo
            // impedisce
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamere(admin, idTipologia, 2);
            String staff = tokenStaff();

            long primo = confermataDiOggi(idTipologia);
            long abitata = idCameraAssegnata(staff, primo);

            mockMvc.perform(put(CAMERE + "/" + abitata + "/stato")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(
                                    com.felixhotel.backend.dto.StatoCamera.LIBERA))))
                    .andExpect(status().isOk());

            long secondo = confermataDiOggi(idTipologia);

            // when/then: chiedere proprio quella stanza da' 409, e non perche' lo stato
            // lo vieti — adesso dice LIBERA. A vietarlo e' la prenotazione in CHECK_IN su
            // quelle notti, che e' l'unica fonte che una persona non puo' sbagliare a
            // mano. Senza quel controllo qui ci sarebbe un 200, e due clienti avrebbero
            // la stessa chiave
            mockMvc.perform(put(PRENOTAZIONI + "/" + secondo + "/check-in")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + abitata + "}"))
                    .andExpect(status().isConflict());

            // e lasciando scegliere il service viene assegnata l'altra, non quella: e' lo
            // stesso controllo visto dall'altra query, quella che cerca fra le assegnabili
            assertThat(idCameraAssegnata(staff, secondo)).isNotEqualTo(abitata);
        }

        @Test
        @DisplayName("chi parte in anticipo libera la stanza per le notti che restano")
        void checkIn_dopoUnaPartenzaAnticipata_riassegnaLaStessaStanza() throws Exception {
            // given: due camere, per la stessa ragione del test qui sopra. Il primo
            // ospite arriva oggi per tre notti e riparte subito: la sua prenotazione
            // resta CHECK_OUT e copre ancora domani e dopodomani
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamere(admin, idTipologia, 2);
            String staff = tokenStaff();

            long primo = confermataDiOggi(idTipologia);
            long liberata = idCameraAssegnata(staff, primo);
            mockMvc.perform(put(PRENOTAZIONI + "/" + primo + "/check-out")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            long secondo = confermataDiOggi(idTipologia);

            // when/then: quella stessa stanza si puo' chiedere di nuovo. E' il motivo per
            // cui il controllo guarda CHECK_IN e non tutti gli stati che occupano:
            // CHECK_OUT consuma disponibilita' — e' storia — ma non tiene fisicamente la
            // stanza. Con l'elenco largo questo darebbe 409 e la camera resterebbe
            // bloccata a vuoto per le notti di un ospite che se n'e' gia' andato
            mockMvc.perform(put(PRENOTAZIONI + "/" + secondo + "/check-in")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": " + liberata + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.camera.id").value(liberata));
        }

        @Test
        @DisplayName("con una camera che non esiste risponde 400")
        void checkIn_conCameraInesistente_risponde400() throws Exception {
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraId\": 99999999}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("prima del giorno di arrivo risponde 409")
        void checkIn_primaDellArrivo_risponde409() throws Exception {
            // given: una prenotazione confermata che comincia fra una settimana
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);

            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia)
                    .dataCheckIn(LocalDate.now().plusDays(7))
                    .dataCheckOut(LocalDate.now().plusDays(10)));
            conferma(cliente, idPrenotazione);

            // when/then: metterebbe OCCUPATA una stanza per una settimana, togliendola a
            // tutti gli arrivi veri di quei giorni
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("senza nessuna camera assegnabile risponde 409 pur essendo confermata")
        void checkIn_conLUnicaCameraInManutenzione_risponde409() throws Exception {
            // given: la tipologia ha una camera sola, e stamattina si e' rotta
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            long idCamera = creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);

            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(
                                    com.felixhotel.backend.dto.StatoCamera.MANUTENZIONE))))
                    .andExpect(status().isOk());

            // when/then: la conferma era passata perche' la disponibilita' conta tutte le
            // camere della tipologia, stato operativo compreso — una stanza rotta oggi
            // non dice niente su un soggiorno di novembre. Oggi pero' la chiave va data
            // su una stanza che esiste davvero, e non ce n'e'
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("ripetuto risponde 409: non e' idempotente")
        void checkIn_ripetuto_risponde409() throws Exception {
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamere(admin, idTipologia, 2);
            long idPrenotazione = confermataDiOggi(idTipologia);
            String staff = tokenStaff();

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            // then: la seconda chiamata non e' innocua come rimandare a una camera lo
            // stato che ha gia' — assegnerebbe una seconda stanza allo stesso ospite
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isConflict());
        }

        /** Registra l'arrivo lasciando scegliere il service, e restituisce l'id della camera toccata. */
        private long idCameraAssegnata(String tokenStaff, long idPrenotazione) throws Exception {
            String risposta = mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            return objectMapper.readTree(risposta).path("data").path("camera").path("id").asLong();
        }
    }

    @Nested
    @DisplayName("PUT /api/prenotazioni/{id}/check-out")
    class Partenza {

        @Test
        @DisplayName("da CHECK_IN riporta la camera a LIBERA e lascia scritto dove ha dormito")
        void checkOut_daCheckIn_liberaLaCamera() throws Exception {
            // given: un ospite dentro
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            long idCamera = creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);
            String staff = tokenStaff();

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-out")
                            .header("Authorization", "Bearer " + staff))
                    // then: lo stato cambia, la camera <b>resta</b>. Cancellarla renderebbe
                    // impossibile rispondere a "chi c'era nella 101 a settembre"
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Check-out registrato"))
                    .andExpect(jsonPath("$.data.stato").value("CHECK_OUT"))
                    .andExpect(jsonPath("$.data.camera.id").value(idCamera));

            assertThat(statoCamera(staff, idCamera)).isEqualTo("LIBERA");
        }

        @Test
        @DisplayName("una camera segnata MANUTENZIONE durante il soggiorno resta fuori servizio")
        void checkOut_conCameraInManutenzione_nonLaRimetteInServizio() throws Exception {
            // given: l'ospite e' dentro e segnala un guasto, che qualcuno registra
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            long idCamera = creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);
            String staff = tokenStaff();

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            mockMvc.perform(put(CAMERE + "/" + idCamera + "/stato")
                            .header("Authorization", "Bearer " + staff)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraStatoRequest(
                                    com.felixhotel.backend.dto.StatoCamera.MANUTENZIONE))))
                    .andExpect(status().isOk());

            // when
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-out")
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());

            // then: la partenza di un ospite non e' una buona ragione per rimettere in
            // servizio una stanza rotta, e senza questo controllo la segnalazione
            // sparirebbe senza che nessuno lo sapesse
            assertThat(statoCamera(staff, idCamera)).isEqualTo("MANUTENZIONE");
        }

        @Test
        @DisplayName("su una prenotazione mai arrivata risponde 409")
        void checkOut_senzaCheckIn_risponde409() throws Exception {
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);
            long idPrenotazione = confermataDiOggi(idTipologia);

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-out")
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("da un cliente risponde 403 anche sulla propria prenotazione")
        void checkOut_daUnCliente_risponde403() throws Exception {
            String admin = tokenAdmin();
            long idTipologia = creaTipologia(admin);
            creaCamera(admin, idTipologia);

            String cliente = tokenCliente();
            long idPrenotazione = creaPrenotazione(cliente, dati.prenotazioneRequest(idTipologia)
                    .dataCheckIn(LocalDate.now())
                    .dataCheckOut(LocalDate.now().plusDays(3)));
            conferma(cliente, idPrenotazione);

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isOk());

            // then: come per il check-in, e per la stessa ragione — non e' l'ospite a
            // chiudere il proprio conto
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-out")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isForbidden());
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
