package com.felixhotel.backend.exception;

import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import com.felixhotel.prova.EndpointCheEsplodono;
import com.felixhotel.prova.EndpointDiProva;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dei percorsi d'errore che non appartengono a nessuna
 * risorsa in particolare.
 *
 * <p>Nasce da una segnalazione della copertura, non da una funzionalita' nuova:
 * il report JaCoCo introdotto con la soglia ha mostrato che quattro handler del
 * {@link GlobalExceptionHandler} non erano esercitati da nessun test. Il primo a
 * essere coperto e' stato quello della rotta inesistente, che era solo non
 * provato; gli altri tre sono reti difensive che in funzionamento normale non
 * scattano mai, ed erano rimasti indietro proprio per questo - per arrivarci
 * serve qualcosa che si rompa.
 *
 * <p>Ora c'e' un posto in cui rompere le cose di proposito
 * ({@link EndpointCheEsplodono}), quindi ci sono anche loro. Il caso che conta
 * di piu' e' il catch-all: e' l'handler che decide <b>cosa vede chi chiama
 * quando qualcosa esplode</b>, e la promessa scritta e' che non veda il
 * dettaglio. Finche' nessun test lo attraversava, quella promessa non era
 * verificata da niente.
 */
@DisplayName("Percorsi d'errore trasversali")
@Import(EndpointDiProva.class)
class GlobalExceptionHandlerIT extends IntegrationTestBase {

    private static final String ROTTA_INESISTENTE = "/api/questa-rotta-non-esiste";

    /**
     * L'unico campo dello spec con due vincoli che uno stesso valore puo'
     * violare insieme: {@code maxLength: 500} e {@code pattern: ^https?://\S+$}
     * su {@code MediaCameraRequest.url}. Gli altri campi a due vincoli hanno
     * coppie che si escludono — un nome non puo' essere insieme troppo corto e
     * troppo lungo — quindi questo endpoint non e' stato scelto per comodita': e'
     * l'unico da cui si arriva li'.
     */
    private static final String MEDIA_DI_UNA_TIPOLOGIA = "/api/tipologie-camera/1/media";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    @Nested
    @DisplayName("rotta inesistente")
    class RottaInesistente {

        @Test
        @DisplayName("da autenticato risponde 404 nella busta standard")
        void rottaInesistente_daAutenticato_risponde404() throws Exception {
            // given: un cliente qualsiasi, purche' autenticato
            RegisterRequest cliente = dati.registerRequest();
            auth.registraAccount(cliente);
            String token = auth.ottieniToken(cliente.getEmail());

            // when: chiede un URL sotto /api che nessun Controller serve
            mockMvc.perform(get(ROTTA_INESISTENTE)
                            .header("Authorization", "Bearer " + token))
                    // then: 404 nella busta standard, non la pagina d'errore di Spring.
                    // E' l'ultimo pezzo della convenzione che restava scoperto: anche
                    // "non esiste" deve arrivare con status, message e timestamp come
                    // tutto il resto
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("da anonimo risponde 401 e non 404")
        void rottaInesistente_daAnonimo_risponde401() throws Exception {
            // when: lo stesso URL, senza token
            mockMvc.perform(get(ROTTA_INESISTENTE))
                    // then: 401 e non 404, ed e' giusto cosi'. La catena di sicurezza
                    // viene prima del DispatcherServlet, quindi decide senza sapere se la
                    // rotta esista; il default e' anyRequest().authenticated(), e chi non
                    // si e' autenticato si ferma li'.
                    //
                    // L'effetto collaterale e' desiderabile: un anonimo non puo' scoprire
                    // quali URL esistono confrontando 404 e 401, perche' li riceve tutti
                    // uguali. Il test sta qui per fissarlo come comportamento voluto — se
                    // un domani diventasse 404, sarebbe una regressione silenziosa che
                    // regala la mappa dell'API a chi la sonda
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Nested
    @DisplayName("status dichiarato dall'eccezione")
    class StatusDichiarato {

        @Test
        @DisplayName("con un motivo, lo riporta insieme allo status")
        void responseStatus_conMotivo_riportaMotivoEStatus() throws Exception {
            // given: un cliente autenticato, perche' /test-only non e' pubblico
            String token = tokenDiUnCliente();

            // when: si chiama la rotta che solleva una ResponseStatusException con motivo
            mockMvc.perform(get(EndpointCheEsplodono.STATUS_CON_MOTIVO)
                            .header("Authorization", "Bearer " + token))
                    // then: lo status dell'eccezione sopravvive. Senza questo handler
                    // l'eccezione cadrebbe nel catch-all e diventerebbe un 500, cioe'
                    // "colpa nostra" al posto di quello che l'eccezione dichiarava
                    .andExpect(status().isIAmATeapot())
                    .andExpect(jsonPath("$.status").value(418))
                    .andExpect(jsonPath("$.message").value(EndpointCheEsplodono.MOTIVO))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("senza motivo, ripiega sulla descrizione dello status e non su null")
        void responseStatus_senzaMotivo_ripiegaSullaDescrizione() throws Exception {
            // given
            String token = tokenDiUnCliente();

            // when: la stessa eccezione costruita col solo status, quindi getReason() null
            mockMvc.perform(get(EndpointCheEsplodono.STATUS_SENZA_MOTIVO)
                            .header("Authorization", "Bearer " + token))
                    // then: message valorizzato con la descrizione standard. E' il ramo
                    // che il javadoc dell'handler promette e che nessuno aveva mai
                    // percorso: sbagliandolo, la busta arriverebbe con message null,
                    // valido come JSON e inutile per chi lo legge
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.status").value(502))
                    .andExpect(jsonPath("$.message").value("Bad Gateway"));
        }
    }

    @Nested
    @DisplayName("autenticazione non convertita da nessun Service")
    class AutenticazioneNonConvertita {

        @Test
        @DisplayName("risponde 401 con un messaggio generico")
        void autenticazione_nonConvertita_risponde401() throws Exception {
            // given
            String token = tokenDiUnCliente();

            // when: una AuthenticationException arriva fino all'advice
            mockMvc.perform(get(EndpointCheEsplodono.AUTENTICAZIONE)
                            .header("Authorization", "Bearer " + token))
                    // then: 401 e un messaggio che non dice perche'. In produzione questo
                    // percorso non si apre - AuthServiceImpl.login cattura la propria per
                    // rispondere "Credenziali non valide" - quindi questa e' la rete per
                    // tutte le altre, e il fatto che non racconti niente e' voluto
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Autenticazione richiesta"))
                    .andExpect(content().string(not(containsString(EndpointCheEsplodono.DETTAGLIO_INTERNO))));
        }
    }

    @Nested
    @DisplayName("eccezione imprevista")
    class EccezioneImprevista {

        @Test
        @DisplayName("risponde 500 nella busta standard senza far uscire il dettaglio")
        void eccezioneImprevista_risponde500SenzaDettagli() throws Exception {
            // given
            String token = tokenDiUnCliente();

            // when: una IllegalStateException che nessun handler piu' specifico riconosce
            mockMvc.perform(get(EndpointCheEsplodono.IMPREVISTO)
                            .header("Authorization", "Bearer " + token))
                    // then: la busta standard anche qui, invece della pagina d'errore di
                    // Spring...
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.message").value("Si e' verificato un errore imprevisto"))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data").doesNotExist())
                    // ...e soprattutto: il dettaglio di cio' che e' esploso resta nei log.
                    // E' l'asserzione che conta piu' delle altre di questo file - le altre
                    // dicono che la forma e' giusta, questa che non si sta regalando a chi
                    // attacca la mappa di cosa c'e' dietro
                    .andExpect(content().string(not(containsString(EndpointCheEsplodono.DETTAGLIO_INTERNO))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))));
        }
    }

    @Nested
    @DisplayName("validazione del body")
    class ValidazioneDelBody {

        @Test
        @DisplayName("un campo che viola due vincoli riporta entrambi i messaggi, non solo l'ultimo")
        void validazione_stessoCampoDueVincoli_concatenaIMessaggi() throws Exception {
            // given: un ADMIN, perche' aggiungere una foto e' riservato
            String email = dati.emailUnivoca();
            creatoreStaff.creaAdmin(email);
            String token = auth.ottieniToken(email);

            // e un url che sfora la lunghezza massima E non rispetta il formato
            MediaCameraRequest richiesta = new MediaCameraRequest()
                    .url("non-un-url-" + "x".repeat(600));

            // when
            mockMvc.perform(post(MEDIA_DI_UNA_TIPOLOGIA)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: 400, e sotto la chiave del campo ci sono ENTRAMBI i messaggi.
                    // La mappa di 'data' ha una voce per campo, quindi due violazioni sullo
                    // stesso campo si accavallano: l'handler le concatena invece di lasciare
                    // che la seconda sovrascriva la prima. E' una promessa scritta nel
                    // commento accanto alla merge - "invece di perdere silenziosamente tutti
                    // i messaggi tranne l'ultimo" - e finora non la manteneva nessun test.
                    // Il separatore e' la prova che la concatenazione e' avvenuta: con una
                    // voce sola non ci sarebbe
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Dati non validi"))
                    .andExpect(jsonPath("$.data.url").value(containsString("; ")));
        }

        @Test
        @DisplayName("la risposta non arriva mai al Controller: la tipologia del path non esiste")
        void validazione_precedeLaRicercaDellaRisorsa() throws Exception {
            // given: lo stesso ADMIN e un body valido, ma su una tipologia inesistente
            String email = dati.emailUnivoca();
            creatoreStaff.creaAdmin(email);
            String token = auth.ottieniToken(email);

            MediaCameraRequest valida = new MediaCameraRequest()
                    .url("https://cdn.example/una-foto.jpg");

            // when
            mockMvc.perform(post(MEDIA_DI_UNA_TIPOLOGIA)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(valida)))
                    // then: 404 e non 400. Serve a rendere esplicito il presupposto del test
                    // qui sopra: quello riceve 400 perche' la validazione del body viene
                    // prima della ricerca della risorsa, non perche' la tipologia 1 esista.
                    // Senza questa asserzione, il giorno che qualcuno creasse davvero una
                    // tipologia con id 1 non ci accorgeremmo che il test misura altro
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * Un cliente qualsiasi, autenticato. Le rotte {@code /test-only} non sono fra
     * i {@code permitAll}, quindi da anonimo darebbero 401 prima ancora di
     * arrivare all'endpoint - e il test misurerebbe la security chain invece
     * dell'handler.
     */
    private String tokenDiUnCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        auth.registraAccount(cliente);
        return auth.ottieniToken(cliente.getEmail());
    }
}
