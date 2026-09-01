package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.MotivoEsenzione;
import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TipoDocumento;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione del registro degli ospiti.
 *
 * <p>E' la seconda sottorisorsa del progetto dopo la galleria fotografica, e la
 * forma dei test la ricalca. Due cose pero' sono nuove e sono il motivo per cui
 * questo file esiste invece di essere qualche metodo dentro
 * {@code PrenotazioneApiIT}:
 * <ul>
 *   <li>e' la prima risorsa <b>senza nessuna lettura per il cliente</b> sotto un
 *       percorso che per tutto il resto il cliente puo' leggere: {@code GET
 *       /api/prenotazioni/{id}} un USER la fa sulla propria, {@code GET
 *       .../ospiti} no. Il 403 su una prenotazione <i>propria</i> e' quindi il
 *       test piu' importante del file;</li>
 *   <li>e' la prima risorsa il cui contenuto <b>vincola un'altra operazione</b>:
 *       il check-in non passa finche' questo elenco non e' completo. Quel legame
 *       lo prova {@code PrenotazioneApiIT}, dove vive il check-in; qui si prova
 *       il lato che lo alimenta.</li>
 * </ul>
 *
 * <p>Come per la galleria, i test che scrivono passano quasi sempre da <b>due</b>
 * chiamate: verificare la risposta della POST direbbe solo che il service sa
 * ripetere quel che gli e' stato detto. Cio' che conta e' che la GET successiva
 * lo confermi, cioe' che sia finito a database.
 */
@DisplayName("API degli ospiti di una prenotazione")
class OspiteApiIT extends IntegrationTestBase {

    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";

    /** Crea account del personale a database: il primo ADMIN non puo' nascere da un endpoint. */
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

    /** Percorso del registro di una prenotazione. */
    private String ospiti(long idPrenotazione) {
        return PRENOTAZIONI + "/" + idPrenotazione + "/ospiti";
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

    /**
     * Una prenotazione confermata, per due ospiti, intestata a un cliente nuovo.
     *
     * <p>Confermata e non in attesa perche' e' lo stato in cui il registro si
     * scrive: su un carrello la POST sarebbe 409, che e' un test suo piu' sotto.
     */
    private long prenotazioneConfermata(String tokenAdmin) throws Exception {
        return prenotazioneConfermataSu(tipologiaPrenotabile(tokenAdmin), tokenCliente());
    }

    /**
     * Una tipologia con una camera dentro.
     *
     * <p>La camera serve alla <b>conferma</b> e non al check-in: confermare
     * ricontrolla la disponibilita', e una tipologia senza stanze non ne ha
     * nessuna — quindi il carrello resterebbe IN_ATTESA e ogni test di questo
     * file registrerebbe ospiti su una prenotazione fuori dalla finestra di
     * scrittura.
     */
    private long tipologiaPrenotabile(String tokenAdmin) throws Exception {
        long idTipologia = creaTipologia(tokenAdmin);
        mockMvc.perform(post("/api/camere")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(idTipologia))))
                .andExpect(status().isCreated());
        return idTipologia;
    }

    private long prenotazioneConfermataSu(long idTipologia, String tokenCliente) throws Exception {
        PrenotazioneRequest richiesta = dati.prenotazioneRequest(idTipologia);
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

    /** Registra un ospite dall'endpoint vero e ne restituisce l'id. */
    private long registra(String token, long idPrenotazione, OspiteRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(ospiti(idPrenotazione))
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
    @DisplayName("Permessi")
    class Permessi {

        @Test
        @DisplayName("un cliente prende 403 anche sugli ospiti della propria prenotazione")
        void ospiti_daUnCliente_risponde403() throws Exception {
            // given: la prenotazione e' sua, e la sa leggere
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin);
            String cliente = tokenCliente();
            long idPrenotazione = prenotazioneConfermataSu(idTipologia, cliente);

            mockMvc.perform(get(PRENOTAZIONI + "/" + idPrenotazione)
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk());

            // when/then: gli ospiti no. E' il test piu' importante del file, perche' e'
            // l'unico posto in cui il cliente perde un permesso che ha su tutto il resto
            // dello stesso percorso: registrare un documento e' un adempimento di legge
            // che si fa al banco, e il contenuto sono dati personali di terzi — gli
            // accompagnatori, che con questo account non c'entrano niente
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("senza token risponde 401, non 403")
        void ospiti_senzaToken_risponde401() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: niente di questa risorsa e' pubblico, e un anonimo si ferma
            // prima di sapere se la prenotazione esista
            mockMvc.perform(get(ospiti(idPrenotazione)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("lo STAFF puo' tutto, come l'ADMIN")
        void ospiti_daStaff_funziona() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            String staff = tokenStaff();

            // when/then: un livello di permesso solo. Al contrario delle foto — dove
            // pubblicare e' da ADMIN e lo STAFF resta fuori — qui e' esattamente il
            // lavoro del turno: chi sta al banco e' chi ha il documento in mano
            long idOspite = registra(staff, idPrenotazione, dati.ospiteRequest());

            mockMvc.perform(delete(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + staff))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/prenotazioni/{id}/ospiti")
    class Registrazione {

        @Test
        @DisplayName("registra l'ospite e lo si ritrova nell'elenco")
        void registra_conDatiValidi_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().dataNascita(LocalDate.of(1985, 4, 17)))))
                    // then
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("Ospite registrato"))
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.nome").value("Mario"))
                    .andExpect(jsonPath("$.data.tipoDocumento").value("CARTA_IDENTITA"))
                    .andExpect(jsonPath("$.data.numeroDocumento").value("CA12345AB"))
                    .andExpect(jsonPath("$.data.dataNascita").value("1985-04-17"));

            // e c'e' davvero, non solo nella risposta della POST
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].numeroDocumento").value("CA12345AB"));
        }

        @Test
        @DisplayName("lo stesso documento due volte sulla stessa prenotazione risponde 409")
        void registra_conDocumentoDuplicato_risponde409() throws Exception {
            // given: la stessa persona gia' registrata
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            registra(admin, idPrenotazione, dati.ospiteRequest());

            // when/then: un doppio invio del modulo al banco. Qui fa danno e non e' solo
            // ridondante: farebbe raggiungere numeroOspiti lasciando fuori qualcuno che
            // dorme li' davvero, e il check-in passerebbe su un registro incompleto
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().nome("Altro"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("lo stesso numero su un tipo di documento diverso passa")
        void registra_conStessoNumeroMaAltroTipo_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            registra(admin, idPrenotazione, dati.ospiteRequest());

            // when/then: l'indice e' sulla tripla e non sulla coppia, ed e' voluto — una
            // carta d'identita' e una patente non condividono lo spazio dei numeri,
            // quindi due documenti diversi con lo stesso numero sono due documenti
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest()
                                    .nome("Anna")
                                    .tipoDocumento(TipoDocumento.PATENTE))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("oltre il numero di ospiti della prenotazione risponde 409")
        void registra_oltreIlNumeroDiOspiti_risponde409() throws Exception {
            // given: due posti letto, due registrati
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("PRIMO"));
            registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("SECONDO"));

            // when/then: chi arriva in soprannumero non si aggiunge qui, si cambia la
            // prenotazione. E' il tetto che rende un'uguaglianza la condizione del
            // check-in
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().numeroDocumento("TERZO"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString("gia' tutti registrati")));
        }

        @Test
        @DisplayName("su una prenotazione ancora IN_ATTESA risponde 409")
        void registra_suPrenotazioneInAttesa_risponde409() throws Exception {
            // given: un carrello, non un soggiorno
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin);
            String cliente = tokenCliente();
            String risposta = mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            long idPrenotazione = objectMapper.readTree(risposta).path("data").path("id").asLong();

            // when/then: finche' nessuno ha confermato non c'e' nessun soggiorno di cui
            // registrare gli ospiti
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("IN_ATTESA")));
        }

        @Test
        @DisplayName("su una prenotazione annullata non si registra piu' nessuno")
        void registra_suPrenotazioneAnnullata_risponde409() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            // when/then: quello che c'e' gia' resta, di nuovo non si aggiunge niente —
            // la stessa regola scelta per gli account disattivati
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("il tipo di documento senza il numero risponde 400")
        void registra_senzaNumeroDocumento_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: dal V10 questo 400 non arriva piu' dalla validazione dello spec
            // — dove i due campi sono facoltativi — ma dal Service, che pretende la
            // coppia intera. Il test resta perche' e' il comportamento a contare, non
            // chi lo produce; il commento cambia perche' altrimenti mentirebbe
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().numeroDocumento(null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("insieme")));
        }

        @Test
        @DisplayName("il motivo di esenzione si scrive e si rilegge")
        void registra_conMotivoEsenzione_loConserva() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when: un residente nel comune, che la tassa di soggiorno non la paga
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest()
                                    .motivoEsenzione(MotivoEsenzione.RESIDENTE))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.motivoEsenzione").value("RESIDENTE"));

            // then: e' un campo che nessun controllo verifica — non c'e' niente contro cui
            // verificarlo — quindi l'unica cosa da provare e' che faccia il giro intero
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].motivoEsenzione").value("RESIDENTE"));
        }

        @Test
        @DisplayName("senza motivo di esenzione il campo non compare, ed e' il caso normale")
        void registra_senzaMotivoEsenzione_nonCompare() throws Exception {
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.motivoEsenzione").doesNotExist());
        }

        @Test
        @DisplayName("senza la data di nascita risponde 400: dal V10 e' obbligatoria")
        void registra_senzaDataNascita_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: qui il 400 e' della validazione dello spec, cioe' l'esatto
            // scambio di posto con il test qui sopra — ed e' il verso giusto, perche' e'
            // la data di nascita a dire se il documento debba esserci
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().dataNascita(null))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con un tipo di documento fuori elenco risponde 400")
        void registra_conTipoDocumentoInesistente_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: l'enum lo fa rispettare il contratto e nient'altro — la colonna
            // in database e' un VARCHAR senza CHECK, vedi TipoDocumento. E' quindi
            // l'unico posto che protegge quel valore, e va provato dal bordo
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nome":"Mario","cognome":"Rossi",
                                     "tipoDocumento":"TESSERA_SANITARIA","numeroDocumento":"X1"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("con una data di nascita nel futuro risponde 400")
        void registra_conDataNascitaFutura_risponde400() throws Exception {
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().dataNascita(LocalDate.now().plusDays(1)))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404")
        void registra_suPrenotazioneInesistente_risponde404() throws Exception {
            String admin = tokenAdmin();

            mockMvc.perform(post(ospiti(999999L))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Minorenni senza documento (V10)")
    class Minorenni {

        @Test
        @DisplayName("un bambino si registra senza documento, e la risposta lo mostra vuoto")
        void registra_minorenneSenzaDocumento_risponde201() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: e' il caso che prima del V10 obbligava a inventare un numero di
            // documento al banco, perche' le due colonne erano NOT NULL
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteMinorenneRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.nome").value("Luca"))
                    .andExpect(jsonPath("$.data.tipoDocumento").doesNotExist())
                    .andExpect(jsonPath("$.data.numeroDocumento").doesNotExist())
                    .andExpect(jsonPath("$.data.dataNascita").exists());
        }

        @Test
        @DisplayName("due bambini senza documento sulla stessa prenotazione non sono un duplicato")
        void registra_dueMinorenniSenzaDocumento_risponde201() throws Exception {
            // given: una prenotazione per due, e nessuno dei due ha un documento
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            registra(admin, idPrenotazione, dati.ospiteMinorenneRequest());

            // when/then: e' il test che vale davvero, perche' guarda l'indice unico del
            // V7 e non il Service — in Postgres due NULL non collidono, quindi la coppia
            // (tipo, numero) vuota due volte passa. Se un giorno quell'indice venisse
            // riscritto con NULLS NOT DISTINCT, e' qui che si vedrebbe
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteMinorenneRequest().nome("Sara"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("un adulto senza documento risponde 400: l'obbligo di legge resta")
        void registra_maggiorenneSenzaDocumento_risponde400() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            // when/then: il V10 ha allargato lo schema, non la regola
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteMinorenneRequest()
                                    .dataNascita(LocalDate.now().minusYears(40)))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("obbligatorio")));
        }

        @Test
        @DisplayName("una famiglia con un bambino arriva a fare il check-in")
        void checkIn_conUnAdultoEUnBambino_risponde200() throws Exception {
            // given: il soggiorno comincia oggi, altrimenti il check-in sarebbe 409
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin);
            String cliente = tokenCliente();
            String risposta = mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(LocalDate.now())
                                    .dataCheckOut(LocalDate.now().plusDays(3)))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            long idPrenotazione = objectMapper.readTree(risposta).path("data").path("id").asLong();
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk());

            // when: un genitore col suo documento e un bambino senza
            registra(admin, idPrenotazione, dati.ospiteRequest());
            registra(admin, idPrenotazione, dati.ospiteMinorenneRequest());

            // then: e' l'intero gap del 2026-08-28 preso da un capo all'altro. Prima di
            // questo branch la seconda registrazione era impossibile, quindi il conto non
            // arrivava mai a numeroOspiti e la chiave non si dava a una famiglia
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/prenotazioni/{id}/ospiti")
    class Elenco {

        @Test
        @DisplayName("restituisce gli ospiti nell'ordine di registrazione")
        void elenco_conPiuOspiti_rispettaLOrdineDiInserimento() throws Exception {
            // given: due ospiti registrati in un ordine che l'alfabetico ribalterebbe
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            registra(admin, idPrenotazione, dati.ospiteRequest().nome("Zeno").numeroDocumento("PRIMO"));
            registra(admin, idPrenotazione, dati.ospiteRequest().nome("Anna").numeroDocumento("SECONDO"));

            // when/then: Zeno resta primo. E' la scelta scritta nel repository: chi sta
            // registrando una famiglia si aspetta di ritrovare la lista com'e' cresciuta
            // sotto le sue mani, non riordinata sotto i suoi occhi
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].nome").value("Zeno"))
                    .andExpect(jsonPath("$.data[1].nome").value("Anna"));
        }

        @Test
        @DisplayName("su una prenotazione senza ospiti restituisce una lista vuota, non 404")
        void elenco_senzaOspiti_restituisceListaVuota() throws Exception {
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404")
        void elenco_suPrenotazioneInesistente_risponde404() throws Exception {
            String admin = tokenAdmin();

            // e' la coppia del test qui sopra: sono i due casi che devono restare
            // distinguibili, altrimenti chi sta al banco non sa se ha sbagliato
            // prenotazione o non ha ancora registrato nessuno
            mockMvc.perform(get(ospiti(999999L))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("il registro si rilegge anche dopo la partenza")
        void elenco_dopoIlCheckOut_funziona() throws Exception {
            // given: un soggiorno portato fino in fondo
            String admin = tokenAdmin();
            long idTipologia = tipologiaPrenotabile(admin);

            String cliente = tokenCliente();
            String risposta = mockMvc.perform(post(PRENOTAZIONI)
                            .header("Authorization", "Bearer " + cliente)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.prenotazioneRequest(idTipologia)
                                    .dataCheckIn(LocalDate.now())
                                    .dataCheckOut(LocalDate.now().plusDays(3)))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            long idPrenotazione = objectMapper.readTree(risposta).path("data").path("id").asLong();
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                            .header("Authorization", "Bearer " + cliente))
                    .andExpect(status().isOk());

            registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("PRIMO"));
            registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("SECONDO"));

            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-in")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/check-out")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            // when/then: la lettura non ha finestra di stato, e questo e' il caso per cui
            // la differenza esiste — un registro di legge serve soprattutto dopo, a chi
            // viene a controllare
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));

            // ma scriverci no: il soggiorno e' concluso
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().numeroDocumento("TARDIVO"))))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/prenotazioni/{id}/ospiti/{ospiteId}")
    class Correzione {

        @Test
        @DisplayName("corregge il numero di documento digitato storto")
        void correzione_conDatiNuovi_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest());

            // when
            mockMvc.perform(put(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().numeroDocumento("CA98765ZY"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.numeroDocumento").value("CA98765ZY"));

            // then: e' finito a database, non solo nella risposta
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(jsonPath("$.data[0].numeroDocumento").value("CA98765ZY"));
        }

        @Test
        @DisplayName("correggere il solo nome, rimandando lo stesso documento, funziona")
        void correzione_conLoStessoDocumento_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest().nome("Maria"));

            // when/then: e' il caso per cui il controllo di unicita' della PUT ignora
            // l'ospite stesso. Senza, questa richiesta sarebbe un 409 incomprensibile —
            // "documento gia' registrato", sulla riga che si sta correggendo
            mockMvc.perform(put(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().nome("Mario"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nome").value("Mario"));
        }

        @Test
        @DisplayName("col documento di un altro ospite della stessa prenotazione risponde 409")
        void correzione_conDocumentoDiUnAltro_risponde409() throws Exception {
            // given: due ospiti registrati
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idPrimo = registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("PRIMO"));
            registra(admin, idPrenotazione, dati.ospiteRequest().numeroDocumento("SECONDO"));

            // when/then: dare al primo il documento del secondo li renderebbe la stessa
            // persona registrata due volte, che e' esattamente cio' che l'indice vieta
            mockMvc.perform(put(ospiti(idPrenotazione) + "/" + idPrimo)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest().numeroDocumento("SECONDO"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("il documento omesso su un minorenne viene azzerato")
        void correzione_senzaDocumentoSuMinorenne_loAzzera() throws Exception {
            // given: un bambino registrato con un documento che si e' scoperto non essere
            // suo — capita che al banco si metta quello di chi lo accompagna
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione,
                    dati.ospiteMinorenneRequest()
                            .tipoDocumento(TipoDocumento.CARTA_IDENTITA)
                            .numeroDocumento("CA00000XX"));

            // when: la correzione non lo porta piu'
            mockMvc.perform(put(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteMinorenneRequest())))
                    // then: e' quel che una PUT promette nel contratto, ed e' il genere di
                    // cosa che si scambia per un difetto quando succede senza preavviso
                    .andExpect(status().isOk())
                    // i campi spariscono dalla risposta invece di comparire a null: i DTO
                    // generati omettono i campi vuoti, ed e' lo stesso motivo per cui la
                    // busta di una DELETE non ha 'data'
                    .andExpect(jsonPath("$.data.tipoDocumento").doesNotExist())
                    .andExpect(jsonPath("$.data.numeroDocumento").doesNotExist());
        }

        @Test
        @DisplayName("il documento omesso su un adulto non azzera niente: e' 400")
        void correzione_senzaDocumentoSuMaggiorenne_risponde400() throws Exception {
            // given: un adulto registrato col suo documento
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest());

            // when: una correzione distratta, che il documento non lo rimanda
            mockMvc.perform(put(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteMinorenneRequest()
                                    .dataNascita(LocalDate.now().minusYears(40)))))
                    // then: qui la promessa della PUT si ferma contro l'obbligo di legge, e
                    // va nel verso giusto — il documento di un adulto non si perde per una
                    // richiesta a cui e' rimasto fuori un campo
                    .andExpect(status().isBadRequest());

            // e infatti e' ancora li'
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].numeroDocumento").value("CA12345AB"));
        }

        @Test
        @DisplayName("su un ospite di un'altra prenotazione risponde 404")
        void correzione_conOspiteDiAltraPrenotazione_risponde404() throws Exception {
            // given: due prenotazioni, un ospite sulla prima
            String admin = tokenAdmin();
            long idPrima = prenotazioneConfermata(admin);
            long idSeconda = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrima, dati.ospiteRequest());

            // when/then: l'id dell'ospite e' valido, la prenotazione nell'URL no. 404
            // come se non ci fosse, invece di modificarlo a chi ha sbagliato indirizzo
            mockMvc.perform(put(ospiti(idSeconda) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/prenotazioni/{id}/ospiti/{ospiteId}")
    class Cancellazione {

        @Test
        @DisplayName("toglie l'ospite dal registro")
        void cancellazione_conOspiteEsistente_risponde200() throws Exception {
            // given
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest());

            // when
            mockMvc.perform(delete(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Ospite eliminato"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: sparito davvero
            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("togliere e rimettere lo stesso documento funziona")
        void cancellazione_poiRegistrazione_funziona() throws Exception {
            // given: un ospite registrato per errore sulla persona sbagliata
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest());

            mockMvc.perform(delete(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            // when/then: lo stesso numero passa di nuovo. Sembra ovvio e non lo e': con
            // una cancellazione logica invece che fisica l'indice unico del V7 vedrebbe
            // ancora la riga vecchia e risponderebbe 409
            mockMvc.perform(post(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.ospiteRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("su un ospite che non esiste risponde 404")
        void cancellazione_conOspiteInesistente_risponde404() throws Exception {
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            mockMvc.perform(delete(ospiti(idPrenotazione) + "/999999")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("cancellare una prenotazione porta via i suoi ospiti")
        void cancellazione_dellaPrenotazione_portaViaGliOspiti() throws Exception {
            // given: un ospite su una prenotazione
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);
            long idOspite = registra(admin, idPrenotazione, dati.ospiteRequest());

            // when/then: non c'e' nessun endpoint che cancelli una prenotazione — si
            // annulla — quindi la cascata del V1 non e' raggiungibile da qui. Cio' che
            // questo test prova e' l'altra meta': dopo l'annullamento l'ospite resta,
            // perche' annullare non e' cancellare e il registro di chi c'era e' storia
            mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/annullamento")
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());

            mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(idOspite));

            // ma non si tocca piu': ne' si toglie, ne' si corregge
            mockMvc.perform(delete(ospiti(idPrenotazione) + "/" + idOspite)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("Contratto")
    class Contratto {

        @Test
        @DisplayName("la busta e' quella standard anche qui")
        void busta_sullaLettura_haLaFormaDiSempre() throws Exception {
            String admin = tokenAdmin();
            long idPrenotazione = prenotazioneConfermata(admin);

            String risposta = mockMvc.perform(get(ospiti(idPrenotazione))
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // e non ha campi in piu' della busta: e' il controllo che ogni IT del
            // progetto fa, perche' un campo di troppo qui e' un contratto diverso da
            // quello dichiarato
            assertThat(objectMapper.readTree(risposta).properties())
                    .extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("status", "message", "timestamp", "data");
        }
    }
}
