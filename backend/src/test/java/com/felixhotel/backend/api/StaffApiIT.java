package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffRequest;
import com.felixhotel.backend.support.Autenticatore;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.backend.support.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione della gestione degli account del personale.
 *
 * <p><b>E' l'IT che chiude un cerchio aperto dal primo giorno.</b> Tutti gli
 * altri test di questa suite che avevano bisogno di un token del personale se lo
 * sono procurato con {@link CreatoreStaff}, cioe' scrivendo la riga a mano,
 * perche' un endpoint per farlo non esisteva. Qui l'account nasce da
 * {@code POST /api/staff} e poi <b>si autentica davvero</b>: e' la sola verifica
 * che conta, perche' un account creato che non riesce a entrare non e' un
 * account.
 *
 * <p><b>Cosa questo IT non puo' provare</b>: il rifiuto di togliere di mezzo
 * l'ultimo amministratore attivo. Il database e' condiviso da tutta la suite e
 * di ADMIN attivi ce ne sono decine, quindi qui quella condizione non si
 * verifica mai — e un test che non puo' fallire non e' un test. Sta fra gli
 * unitari di {@code StaffServiceImplTest}, dove il conteggio si puo' mettere a
 * zero.
 */
@DisplayName("API degli account del personale")
class StaffApiIT extends IntegrationTestBase {

    private static final String STAFF = "/api/staff";

    /**
     * Serve ancora, e solo per una cosa: fabbricare il <b>primo</b> ADMIN, quello
     * che chiama gli endpoint sotto esame. E' il problema dell'uovo e della
     * gallina che questa risorsa non risolve e non puo' risolvere — un account di
     * backoffice lo crea un ADMIN, quindi il primo di tutti nasce per forza da
     * fuori. Da qui in poi pero' non serve piu' a nessun altro scopo.
     */
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

    /** Crea un account dall'endpoint vero e ne restituisce l'id. */
    private long creaStaff(String tokenAdmin, StaffRequest richiesta) throws Exception {
        String risposta = mockMvc.perform(post(STAFF)
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
     * Prova a fare il login e restituisce lo status ottenuto, qualunque sia.
     *
     * <p>{@link Autenticatore#ottieniToken} non serve qui: quello pretende che il
     * login riesca, ed e' giusto cosi' per chi ha solo bisogno di un token. Qui
     * invece il fallimento e' il risultato atteso di meta' dei casi — un account
     * disattivato, una password vecchia — e va guardato, non evitato.
     */
    private int statusDelLogin(String email, String password) throws Exception {
        return mockMvc.perform(post(Autenticatore.LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.loginRequest(email, password))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Nested
    @DisplayName("Permessi")
    class Permessi {

        @Test
        @DisplayName("da anonimo risponde 401")
        void elenco_daAnonimo_risponde401() throws Exception {
            // when: si prova a leggere l'organico senza token
            mockMvc.perform(get(STAFF))
                    // then: 401. Nessun path di questa risorsa e' fra i permitAll, quindi
                    // ricadono tutti nel default autenticato
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con un token da cliente risponde 403")
        void elenco_conTokenCliente_risponde403() throws Exception {
            // when
            mockMvc.perform(get(STAFF).header("Authorization", "Bearer " + tokenCliente()))
                    // then: 403
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("con un token da STAFF risponde 403 anche in lettura")
        void elenco_conTokenStaff_risponde403() throws Exception {
            // when: un membro del personale non amministratore prova a vedere i colleghi
            mockMvc.perform(get(STAFF).header("Authorization", "Bearer " + tokenStaff()))
                    // then: 403, ed e' la differenza rispetto alle camere. La' lo STAFF
                    // legge l'inventario perche' e' il suo lavoro di turno; qui leggere
                    // vuol dire vedere recapiti e privilegi dei colleghi, che non lo e'
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("uno STAFF non puo' nemmeno creare account")
        void creazione_conTokenStaff_risponde403() throws Exception {
            // when
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest())))
                    // then: 403. Distribuire privilegi non e' un'operazione da turno
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /api/staff")
    class Creazione {

        @Test
        @DisplayName("da ADMIN crea l'account, risponde 201 e non rimanda la password")
        void creazione_daAdmin_risponde201() throws Exception {
            // given
            StaffRequest richiesta = dati.staffRequest();

            // when
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 201 con la risorsa creata. L'email c'e' — qui la risorsa e'
                    // l'account e quello e' il suo modo di entrare — la password no, in
                    // nessuna forma, hash compreso
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.email").value(richiesta.getEmail()))
                    .andExpect(jsonPath("$.data.ruolo").value("STAFF"))
                    .andExpect(jsonPath("$.data.attivo").value(true))
                    .andExpect(jsonPath("$.data.password").doesNotExist())
                    .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        }

        @Test
        @DisplayName("l'account creato riesce davvero ad autenticarsi")
        void creazione_daAdmin_lAccountEntraDavvero() throws Exception {
            // given: un account nato dall'endpoint e non da una INSERT
            StaffRequest richiesta = dati.staffRequest();
            creaStaff(tokenAdmin(), richiesta);

            // when: quella persona fa il login come farebbe il primo giorno di lavoro
            String token = auth.ottieniToken(richiesta.getEmail());

            // then: e con quel token entra dove il suo ruolo gli permette di entrare.
            // E' la verifica che vale per tutte le altre: la password scelta
            // dall'amministratore arriva cifrata fino al confronto del login, e il ruolo
            // scritto nella richiesta e' quello con cui la richiesta successiva viene
            // giudicata.
            // L'endpoint e' di un'altra risorsa perche' non c'e' scelta: questa e'
            // riservata agli ADMIN, e serviva qualcosa che uno STAFF possa raggiungere.
            // E' un accoppiamento vero — il giorno che le camere cambiassero permessi,
            // questo test diventerebbe rosso per una ragione che non lo riguarda — e si
            // ripara guardando il messaggio, non indovinando
            mockMvc.perform(get("/api/camere").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("un ADMIN puo' creare un altro ADMIN")
        void creazione_diUnAltroAdmin_risponde201() throws Exception {
            // given/when: il ruolo e' un campo della richiesta, e ADMIN e' un valore lecito
            StaffRequest richiesta = dati.staffRequest().ruolo(RuoloStaff.ADMIN);
            creaStaff(tokenAdmin(), richiesta);

            // then: il nuovo amministratore fa cose da amministratore, cioe' crea a sua
            // volta. E' la prova che il ruolo non e' solo un'etichetta nella risposta
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + auth.ottieniToken(richiesta.getEmail()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("con un'email gia' del personale risponde 409")
        void creazione_conEmailDuplicata_risponde409() throws Exception {
            // given: un account che c'e' gia'
            String tokenAdmin = tokenAdmin();
            StaffRequest primo = dati.staffRequest();
            creaStaff(tokenAdmin, primo);

            // when: se ne crea un secondo con lo stesso indirizzo
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest().email(primo.getEmail()))))
                    // then: 409
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("con la stessa email scritta a maiuscole diverse risponde comunque 409")
        void creazione_conEmailDuplicataAMaiuscoleDiverse_risponde409() throws Exception {
            // given: un account con un indirizzo tutto minuscolo
            String tokenAdmin = tokenAdmin();
            StaffRequest primo = dati.staffRequest();
            creaStaff(tokenAdmin, primo);

            // when: se ne crea un secondo con lo stesso indirizzo in maiuscolo
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest()
                                    .email(primo.getEmail().toUpperCase(Locale.ROOT)))))
                    // then: 409 e non 201. Per chi lo scrive e' lo stesso indirizzo, e da
                    // V6 lo e' anche per il database — senza, sarebbero nati due account e
                    // il login ne avrebbe raggiunto uno solo
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("con un'email gia' di un cliente risponde 409")
        void creazione_conEmailDiUnCliente_risponde409() throws Exception {
            // given: un cliente registrato dal frontoffice
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);

            // when: si prova a farne un account del personale con lo stesso indirizzo
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest().email(cliente.getEmail()))))
                    // then: 409. L'email e' la credenziale di login e il login e' uno solo
                    // per le due popolazioni: due account con lo stesso indirizzo vorrebbe
                    // dire che uno dei due non entra piu'
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("con una password troppo corta risponde 400 col campo indicato")
        void creazione_conPasswordCorta_risponde400() throws Exception {
            // when
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffRequest().password("corta"))))
                    // then: 400 con la mappa campo -> messaggio, che e' l'unica risposta
                    // d'errore del progetto in cui 'data' non e' null
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.password").exists());
        }

        @Test
        @DisplayName("con ruolo USER risponde 400")
        void creazione_conRuoloUser_risponde400() throws Exception {
            // given: un corpo scritto a mano, perche' il DTO generato non permette
            // nemmeno di esprimere questo caso — l'enum ha due valori
            String corpo = json(dati.staffRequest()).replace("\"STAFF\"", "\"USER\"");

            // when
            mockMvc.perform(post(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpo))
                    // then: 400. USER e' il ruolo dei clienti, che vivono in un'altra
                    // tabella: una riga di staff che lo portasse sarebbe dentro il
                    // backoffice senza nessuno dei privilegi per cui quella tabella esiste
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/staff")
    class Elenco {

        @Test
        @DisplayName("da ADMIN risponde 200 con la busta paginata")
        void elenco_daAdmin_risponde200() throws Exception {
            // given
            String tokenAdmin = tokenAdmin();
            creaStaff(tokenAdmin, dati.staffRequest());

            // when
            mockMvc.perform(get(STAFF).header("Authorization", "Bearer " + tokenAdmin))
                    // then: 200 con la busta paginata completa
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.page.pageNumber").value(0))
                    .andExpect(jsonPath("$.data[0].cognome").exists())
                    .andExpect(jsonPath("$.data[0].ruolo").exists())
                    .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist());
        }

        @Test
        @DisplayName("con un ruolo fuori dall'elenco risponde 400 e non una pagina vuota")
        void elenco_conRuoloInesistente_risponde400() throws Exception {
            // when
            mockMvc.perform(get(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .param("ruolo", "PORTIERE"))
                    // then: 400. E' un valore che non esiste, non un filtro che non trova
                    // niente: rispondere con una pagina vuota direbbe che il filtro ha
                    // funzionato
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("comprende anche gli account disattivati")
        void elenco_senzaFiltroAttivo_comprendeIDisattivati() throws Exception {
            // given: un account che viene disattivato subito dopo essere nato
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);
            disattiva(tokenAdmin, id);

            // when: si filtra per i soli disattivati
            mockMvc.perform(get(STAFF)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .param("attivo", "false")
                            .param("size", "100"))
                    // then: c'e'. Un account disattivato non sparisce, altrimenti non lo si
                    // potrebbe piu' ritrovare per riattivarlo
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + id + ")]").exists());
        }
    }

    @Nested
    @DisplayName("GET /api/staff/{id}")
    class Dettaglio {

        @Test
        @DisplayName("con id inesistente risponde 404")
        void dettaglio_conIdInesistente_risponde404() throws Exception {
            // when
            mockMvc.perform(get(STAFF + "/999999").header("Authorization", "Bearer " + tokenAdmin()))
                    // then
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    @Nested
    @DisplayName("PUT /api/staff/{id}")
    class Aggiornamento {

        @Test
        @DisplayName("cambia i campi anagrafici e il ruolo, e il nuovo ruolo vale davvero")
        void aggiornamento_conRuoloNuovo_cambiaIPrivilegi() throws Exception {
            // given: uno STAFF che non puo' vedere l'organico
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);

            mockMvc.perform(get(STAFF).header("Authorization", "Bearer " + auth.ottieniToken(richiesta.getEmail())))
                    .andExpect(status().isForbidden());

            // when: lo si promuove ad amministratore
            mockMvc.perform(put(STAFF + "/" + id)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAggiornamentoRequest(richiesta.getEmail(), RuoloStaff.ADMIN)
                                    .cognome("Bianchi Rossi"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.cognome").value("Bianchi Rossi"))
                    .andExpect(jsonPath("$.data.ruolo").value("ADMIN"));

            // then: con un token nuovo entra dove prima si sentiva dire di no. Il token
            // vecchio non c'entra: il ruolo lo ricarica il filtro ad ogni richiesta, ma
            // rifare il login e' quello che farebbe una persona
            mockMvc.perform(get(STAFF).header("Authorization", "Bearer " + auth.ottieniToken(richiesta.getEmail())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("con l'email di un altro account risponde 409")
        void aggiornamento_conEmailDiUnAltro_risponde409() throws Exception {
            // given: due account
            String tokenAdmin = tokenAdmin();
            StaffRequest primo = dati.staffRequest();
            creaStaff(tokenAdmin, primo);
            long idSecondo = creaStaff(tokenAdmin, dati.staffRequest());

            // when: si prova a dare al secondo l'indirizzo del primo
            mockMvc.perform(put(STAFF + "/" + idSecondo)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAggiornamentoRequest(primo.getEmail(), RuoloStaff.STAFF))))
                    // then: 409
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("riconfermando la propria email risponde 200")
        void aggiornamento_conLaPropriaEmail_risponde200() throws Exception {
            // given
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);

            // when: la PUT rimanda tutti i campi, email compresa
            mockMvc.perform(put(STAFF + "/" + id)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAggiornamentoRequest(richiesta.getEmail(), RuoloStaff.STAFF))))
                    // then: 200 e non 409 contro se stessi — il caso piu' frequente di tutti
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PUT /api/staff/{id}/attivazione")
    class Attivazione {

        @Test
        @DisplayName("un account disattivato non riesce piu' ad autenticarsi")
        void attivazione_disattivando_chiudeLAccesso() throws Exception {
            // given: un account che funziona
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);
            auth.ottieniToken(richiesta.getEmail());

            // when: lo si disattiva
            mockMvc.perform(put(STAFF + "/" + id + "/attivazione")
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAttivazioneRequest(false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attivo").value(false));

            // then: 401, con le credenziali giuste. E' il controllo che gia' esisteva —
            // isEnabled() sul principal — visto per la prima volta da un endpoint che
            // puo' spegnerlo
            assertThat(statusDelLogin(richiesta.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("riattivandolo l'accesso torna")
        void attivazione_riattivando_riapreLAccesso() throws Exception {
            // given: un account spento
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);
            disattiva(tokenAdmin, id);

            // when: lo si riaccende
            mockMvc.perform(put(STAFF + "/" + id + "/attivazione")
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAttivazioneRequest(true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attivo").value(true));

            // then: rientra. Disattivare non cancella niente, ed e' il motivo per cui
            // questa risorsa non ha un DELETE
            auth.ottieniToken(richiesta.getEmail());
        }

        @Test
        @DisplayName("rimandare lo stato che l'account ha gia' risponde 200")
        void attivazione_conLoStessoStato_risponde200() throws Exception {
            // given: un account gia' attivo
            String tokenAdmin = tokenAdmin();
            long id = creaStaff(tokenAdmin, dati.staffRequest());

            // when/then: 200 e non 409. Chi ripete l'operazione perche' non era sicuro
            // che la prima fosse arrivata non sta sbagliando niente
            mockMvc.perform(put(STAFF + "/" + id + "/attivazione")
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffAttivazioneRequest(true))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PUT /api/staff/{id}/password")
    class Password {

        @Test
        @DisplayName("la password nuova funziona e la vecchia no")
        void password_cambiata_sostituisceLaPrecedente() throws Exception {
            // given: un account con la password scelta alla creazione
            String tokenAdmin = tokenAdmin();
            StaffRequest richiesta = dati.staffRequest();
            long id = creaStaff(tokenAdmin, richiesta);

            // when: un amministratore gliene assegna un'altra, senza conoscere la prima
            mockMvc.perform(put(STAFF + "/" + id + "/password")
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffPasswordRequest("PasswordNuova456"))))
                    // then: 200 con 'data' null — la password non torna indietro in nessuna
                    // forma, nemmeno per conferma
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());

            // then: e la sostituzione e' vera da tutte e due le parti
            auth.ottieniToken(richiesta.getEmail(), "PasswordNuova456");

            assertThat(statusDelLogin(richiesta.getEmail(), TestDataFactory.PASSWORD_VALIDA))
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void password_conIdInesistente_risponde404() throws Exception {
            // when
            mockMvc.perform(put(STAFF + "/999999/password")
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.staffPasswordRequest("PasswordNuova456"))))
                    // then
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Login a meno delle maiuscole")
    class LoginCaseInsensitive {

        @Test
        @DisplayName("un account del personale entra scrivendo l'email in maiuscolo")
        void login_conEmailInMaiuscolo_riesce() throws Exception {
            // given: un account creato con l'indirizzo tutto minuscolo
            StaffRequest richiesta = dati.staffRequest();
            creaStaff(tokenAdmin(), richiesta);

            // when/then: entra lo stesso. Nessuno ricorda con che maiuscole si e'
            // registrato, e da V6 il database la pensa allo stesso modo
            assertThat(statusDelLogin(richiesta.getEmail().toUpperCase(Locale.ROOT),
                            TestDataFactory.PASSWORD_VALIDA))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("un cliente entra scrivendo l'email in maiuscolo")
        void login_daClienteConEmailInMaiuscolo_riesce() throws Exception {
            // given: un cliente registrato dal frontoffice
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);

            // when/then: la stessa regola vale per l'altra popolazione — l'indice di V6
            // le copre entrambe, e il login le cerca tutte e due
            assertThat(statusDelLogin(cliente.getEmail().toUpperCase(Locale.ROOT),
                            TestDataFactory.PASSWORD_VALIDA))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("una registrazione con la stessa email a maiuscole diverse risponde 409")
        void registrazione_conEmailDuplicataAMaiuscoleDiverse_risponde409() throws Exception {
            // given: un cliente registrato
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);

            // when: qualcuno prova a registrarsi con lo stesso indirizzo in maiuscolo
            mockMvc.perform(post(Autenticatore.REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.registerRequest()
                                    .email(cliente.getEmail().toUpperCase(Locale.ROOT)))))
                    // then: 409 e non 201. Prima di V6 nascevano due clienti e il secondo
                    // non sarebbe mai riuscito a entrare, perche' il login trova il primo
                    .andExpect(status().isConflict());
        }
    }

    /** Spegne un account: serve a piu' di un test e non e' il gesto che quei test verificano. */
    private void disattiva(String tokenAdmin, long id) throws Exception {
        mockMvc.perform(put(STAFF + "/" + id + "/attivazione")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.staffAttivazioneRequest(false))))
                .andExpect(status().isOk());
    }
}
