package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.ImpostazioniHotelRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dell'anagrafica della struttura: richiesta HTTP reale
 * (via MockMvc) fino a Postgres e ritorno.
 *
 * <p><b>Cosa si prova qui e non altrove.</b> La forma della busta e il
 * campionario di validazione sono gia' esercitati dalle altre risorse e non si
 * ripetono. Di questa si prova cio' che e' <b>suo</b>, e sono quattro cose:
 * <ul>
 *   <li>che la rotta pubblica <b>non faccia uscire l'identita' fiscale</b>. E'
 *       il test che conta piu' di tutti gli altri: il codice per Alloggiati Web
 *       e' di fatto una credenziale verso la Questura, e qui si verifica dal
 *       bordo HTTP che non compaia — dopo aver scritto un valore vero, cosi'
 *       che "il campo non c'e'" non possa essere confuso con "il campo e'
 *       vuoto";</li>
 *   <li>che la risorsa completa e la PUT vogliano un <b>ADMIN</b>: anonimo 401,
 *       cliente e STAFF 403;</li>
 *   <li>che la PUT sia una PUT davvero, cioe' che un facoltativo omesso torni
 *       null anche riletto dal database;</li>
 *   <li>che il vincolo <b>una riga sola</b> sia del database e non del codice —
 *       vedi {@link RigaSingola}.</li>
 * </ul>
 *
 * <p><b>Nessun test qui si crea la propria riga di impostazioni</b>, ed e'
 * l'unico IT del progetto in cui la risorsa sotto esame non nasce da un
 * endpoint: la riga esiste dalla migration e i test la riscrivono, uno dopo
 * l'altro, sulla stessa. Per questo la richiesta di partenza porta un nome
 * univoco (vedi {@code TestDataFactory.impostazioniHotelRequest}) — senza, un
 * test che verifica di aver salvato passerebbe anche trovando quello che il
 * test precedente aveva lasciato li'. Gli account invece se li creano, come
 * ovunque.
 */
@DisplayName("API delle impostazioni della struttura")
class ImpostazioniHotelApiIT extends IntegrationTestBase {

    private static final String IMPOSTAZIONI = "/api/impostazioni";
    private static final String IMPOSTAZIONI_PUBBLICHE = "/api/impostazioni/pubbliche";

    /** Crea account del personale a database: non esiste un endpoint per farlo senza un ADMIN. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    /**
     * Serve a due cose che nessun endpoint sa fare: rileggere la riga com'e'
     * scritta davvero, e provare a inserirne una seconda per vedere il CHECK
     * mordere.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** Scrive le impostazioni passando dall'endpoint vero e restituisce cio' che ha scritto. */
    private ImpostazioniHotelRequest scrivi(ImpostazioniHotelRequest richiesta) throws Exception {
        mockMvc.perform(put(IMPOSTAZIONI)
                        .header("Authorization", "Bearer " + tokenAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isOk());
        return richiesta;
    }

    @Nested
    @DisplayName("GET /api/impostazioni/pubbliche")
    class LetturaPubblica {

        @Test
        @DisplayName("da anonimo risponde 200 con recapiti e orari")
        void pubbliche_daAnonimo_rispondeConIRecapiti() throws Exception {
            // given: impostazioni compilate da un ADMIN
            ImpostazioniHotelRequest scritte = scrivi(dati.impostazioniHotelRequest());

            // when: la legge chi passa di li', senza autenticarsi
            mockMvc.perform(get(IMPOSTAZIONI_PUBBLICHE))
                    // then: 200 e busta completa
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.data.nome").value(scritte.getNome()))
                    .andExpect(jsonPath("$.data.indirizzo").value(scritte.getIndirizzo()))
                    .andExpect(jsonPath("$.data.telefono").value(scritte.getTelefono()))
                    .andExpect(jsonPath("$.data.email").value(scritte.getEmail()))
                    .andExpect(jsonPath("$.data.orarioCheckInDefault").exists())
                    .andExpect(jsonPath("$.data.orarioCheckOutDefault").exists());
        }

        @Test
        @DisplayName("non fa uscire nessun dato fiscale, nemmeno quando e' compilato")
        void pubbliche_conDatiFiscaliCompilati_nonLiEspone() throws Exception {
            // given: i codici ci sono davvero, quindi "assente" non puo' voler dire
            // "non l'ha mai scritto nessuno"
            ImpostazioniHotelRequest scritte = scrivi(dati.impostazioniHotelRequest());
            assertThat(scritte.getCodiceStrutturaAlloggiati()).isNotBlank();

            // when
            mockMvc.perform(get(IMPOSTAZIONI_PUBBLICHE))
                    // then: nessuno dei sette campi riservati compare nella risposta.
                    // E' il test che protegge la decisione centrale di questa risorsa:
                    // i due DTO sono separati proprio perche' questa riga non dipenda
                    // da qualcuno che si ricordi di azzerare dei campi
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ragioneSociale").doesNotExist())
                    .andExpect(jsonPath("$.data.partitaIva").doesNotExist())
                    .andExpect(jsonPath("$.data.codiceFiscale").doesNotExist())
                    .andExpect(jsonPath("$.data.cin").doesNotExist())
                    .andExpect(jsonPath("$.data.comune").doesNotExist())
                    .andExpect(jsonPath("$.data.codiceIstatComune").doesNotExist())
                    .andExpect(jsonPath("$.data.codiceStrutturaAlloggiati").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /api/impostazioni")
    class LetturaCompleta {

        @Test
        @DisplayName("da ADMIN risponde 200 con anche i codici degli adempimenti")
        void completa_daAdmin_rispondeConTuttiICampi() throws Exception {
            // given
            ImpostazioniHotelRequest scritte = scrivi(dati.impostazioniHotelRequest());

            // when
            mockMvc.perform(get(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin()))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.nome").value(scritte.getNome()))
                    .andExpect(jsonPath("$.data.ragioneSociale").value(scritte.getRagioneSociale()))
                    .andExpect(jsonPath("$.data.partitaIva").value(scritte.getPartitaIva()))
                    .andExpect(jsonPath("$.data.codiceFiscale").value(scritte.getCodiceFiscale()))
                    .andExpect(jsonPath("$.data.cin").value(scritte.getCin()))
                    .andExpect(jsonPath("$.data.comune").value(scritte.getComune()))
                    .andExpect(jsonPath("$.data.codiceIstatComune").value(scritte.getCodiceIstatComune()))
                    .andExpect(jsonPath("$.data.codiceStrutturaAlloggiati")
                            .value(scritte.getCodiceStrutturaAlloggiati()));
        }

        @Test
        @DisplayName("da anonimo risponde 401")
        void completa_daAnonimo_rispondeNonAutorizzato() throws Exception {
            mockMvc.perform(get(IMPOSTAZIONI))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("da cliente risponde 403")
        void completa_daCliente_rispondeVietato() throws Exception {
            mockMvc.perform(get(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenCliente()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("da STAFF risponde 403: un livello di permesso e non tre")
        void completa_daStaff_rispondeVietato() throws Exception {
            // when: chi sta al banco non legge l'identita' fiscale della societa'.
            // Gli orari e i recapiti, che a lui servono davvero, li ha dalla rotta
            // pubblica come chiunque altro
            mockMvc.perform(get(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenStaff()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("PUT /api/impostazioni")
    class Aggiornamento {

        @Test
        @DisplayName("da ADMIN salva e restituisce la vista completa")
        void aggiornamento_daAdmin_salvaERestituisceTutto() throws Exception {
            // given
            ImpostazioniHotelRequest richiesta = dati.impostazioniHotelRequest()
                    .orarioCheckInDefault(LocalTime.of(15, 30));

            // when
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    // then: la risposta e' quella completa e non quella pubblica —
                    // ricevere meno di quello che si e' appena mandato lascerebbe il
                    // dubbio che il resto non sia stato salvato
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.nome").value(richiesta.getNome()))
                    .andExpect(jsonPath("$.data.orarioCheckInDefault").value("15:30:00"))
                    .andExpect(jsonPath("$.data.partitaIva").value(richiesta.getPartitaIva()));

            // e la riletta dal database e' quella scritta, non quella in memoria
            String nomeInDatabase = jdbcTemplate.queryForObject(
                    "select nome from impostazioni_hotel where id = ?", String.class,
                    ImpostazioniHotel.ID_RIGA_UNICA);
            assertThat(nomeInDatabase).isEqualTo(richiesta.getNome());
        }

        @Test
        @DisplayName("azzera i facoltativi omessi: e' una PUT, non una PATCH")
        void aggiornamento_conFacoltativiOmessi_liAzzeraInDatabase() throws Exception {
            // given: prima si compila tutto, cosi' i campi hanno un valore da perdere
            scrivi(dati.impostazioniHotelRequest());

            // when: poi si manda una richiesta con i soli obbligatori
            ImpostazioniHotelRequest minima = new ImpostazioniHotelRequest()
                    .nome(dati.nomeUnivoco("Felix Hotel"))
                    .orarioCheckInDefault(LocalTime.of(14, 0))
                    .orarioCheckOutDefault(LocalTime.of(10, 0));
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(minima)))
                    .andExpect(status().isOk())
                    // then: nella risposta i facoltativi sono spariti
                    .andExpect(jsonPath("$.data.partitaIva").doesNotExist())
                    .andExpect(jsonPath("$.data.codiceStrutturaAlloggiati").doesNotExist());

            // e sono spariti anche in database, che e' l'unico posto in cui conta
            String partitaIva = jdbcTemplate.queryForObject(
                    "select partita_iva from impostazioni_hotel where id = ?", String.class,
                    ImpostazioniHotel.ID_RIGA_UNICA);
            assertThat(partitaIva).isNull();
        }

        @Test
        @DisplayName("senza il nome risponde 400 con la mappa dei campi")
        void aggiornamento_senzaNome_rispondeRichiestaNonValida() throws Exception {
            // given: il nome e' NOT NULL dal V1, quindi lo spec lo dichiara obbligatorio
            ImpostazioniHotelRequest senzaNome = dati.impostazioniHotelRequest().nome(null);

            // when / then: 400 al bordo, con la mappa campo -> messaggio in 'data'
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(senzaNome)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.nome").exists());
        }

        @Test
        @DisplayName("con un'email malformata risponde 400")
        void aggiornamento_conEmailMalformata_rispondeRichiestaNonValida() throws Exception {
            // given: e' l'unico vincolo di *formato* di questo schema — sui codici
            // fiscali non ce n'e' nessuno, di proposito — quindi senza questo test
            // resterebbe dichiarato e non protetto
            ImpostazioniHotelRequest richiesta = dati.impostazioniHotelRequest().email("non-una-email");

            // when / then
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(richiesta)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.data.email").exists());
        }

        @Test
        @DisplayName("con un orario impossibile risponde 400 e non 500")
        void aggiornamento_conOrarioImpossibile_rispondeRichiestaNonValida() throws Exception {
            // given: un corpo scritto a mano, perche' il DTO non permette di
            // costruire un LocalTime che non esiste
            String corpo = """
                    {"nome":"Felix Hotel","orarioCheckInDefault":"25:99","orarioCheckOutDefault":"10:00"}""";

            // when / then: e' il motivo per cui il pom mappa "format: time" su
            // LocalTime invece di lasciarlo String. Con una String il valore
            // attraverserebbe il bordo e si romperebbe nel Service, cioe' arriverebbe
            // qui come 500 — il difetto che la regola 21 esiste per evitare
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(corpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("da anonimo risponde 401")
        void aggiornamento_daAnonimo_rispondeNonAutorizzato() throws Exception {
            mockMvc.perform(put(IMPOSTAZIONI)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.impostazioniHotelRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("da cliente risponde 403")
        void aggiornamento_daCliente_rispondeVietato() throws Exception {
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenCliente())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.impostazioniHotelRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("da STAFF risponde 403")
        void aggiornamento_daStaff_rispondeVietato() throws Exception {
            mockMvc.perform(put(IMPOSTAZIONI)
                            .header("Authorization", "Bearer " + tokenStaff())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.impostazioniHotelRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("la riga e' una sola")
    class RigaSingola {

        @Test
        @DisplayName("la migration ne ha seminata esattamente una")
        void migration_haSeminatoUnaRigaSola() {
            // then: e' cio' che permette alla GET di rispondere sempre 200, e quindi
            // a fatture e schedine di non portarsi dietro un ramo "e se non ci
            // fossero?"
            Integer quante = jdbcTemplate.queryForObject(
                    "select count(*) from impostazioni_hotel", Integer.class);
            assertThat(quante).isEqualTo(1);
        }

        @Test
        @DisplayName("il database rifiuta una seconda riga")
        void insert_diUnaSecondaRiga_violaIlVincolo() {
            // when / then: il CHECK (id = 1) della V8. Non c'e' nessun endpoint che
            // possa provocarlo — non esiste una POST — quindi si prova dal basso, che
            // e' l'unico modo di vedere agire un vincolo che difende da una INSERT a
            // mano. Senza questo test "la riga e' una sola" resterebbe una frase in
            // un commento, che e' esattamente cio' che la regola 17 vieta
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "insert into impostazioni_hotel (id, nome, orario_check_in_default, "
                            + "orario_check_out_default) values (2, 'Secondo albergo', '14:00', '10:00')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
