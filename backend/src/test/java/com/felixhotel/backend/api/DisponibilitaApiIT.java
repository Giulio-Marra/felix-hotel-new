package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione della ricerca di disponibilita'.
 *
 * <p><b>L'isolamento qui non si ottiene come negli altri IT.</b> Ogni altro
 * elenco del progetto si guarda una risorsa alla volta, quindi bastava che ogni
 * test si creasse la propria; questa invece interroga <b>l'intero catalogo</b>,
 * e nella risposta finirebbero anche le tipologie create da tutti gli altri
 * test — che girano sullo stesso database e non lo ripuliscono.
 *
 * <p>Il rimedio e' il filtro di prezzo, stretto su un valore che appartiene a una
 * tipologia sola ({@code dati.prezzoUnivoco()}): ritaglia la propria riga usando
 * uno dei filtri che questi test devono comunque esercitare. E' il motivo per cui
 * quasi ogni test qui sotto passa {@code prezzoMinimo} e {@code prezzoMassimo}
 * sullo stesso valore anche quando il prezzo non e' cio' che sta verificando —
 * senza, si guarderebbe il catalogo di tutta la suite.
 */
@DisplayName("API della ricerca di disponibilita'")
class DisponibilitaApiIT extends IntegrationTestBase {

    private static final String DISPONIBILITA = "/api/disponibilita";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";
    private static final String PRENOTAZIONI = "/api/prenotazioni";

    /** Crea account del personale a database: non esiste un endpoint per farlo. */
    @Autowired
    private CreatoreStaff creatoreStaff;

    private String tokenAdmin() throws Exception {
        String email = dati.emailUnivoca();
        creatoreStaff.creaAdmin(email);
        return auth.ottieniToken(email);
    }

    private String tokenCliente() throws Exception {
        RegisterRequest cliente = dati.registerRequest();
        auth.registraAccount(cliente);
        return auth.ottieniToken(cliente.getEmail());
    }

    /**
     * Una tipologia di prova: il suo id, e il prezzo che la distingue da ogni
     * altra del catalogo.
     *
     * <p><b>Porta tutti e due i valori di proposito.</b> La prima versione
     * restituiva il solo prezzo, e i test che avevano bisogno dell'id se lo
     * facevano dire dalla ricerca — cioe' costruivano la propria fixture
     * <b>usando l'endpoint sotto esame</b>. Bastava che la ricerca si rompesse
     * perche' quei test fallissero prima ancora di arrivare a cio' che volevano
     * verificare, dicendo la cosa sbagliata su dove fosse il guasto.
     */
    private record TipologiaDiProva(long id, BigDecimal prezzo) { }

    /**
     * Una tipologia col prezzo che la distingue da ogni altra, {@code quante}
     * camere, e la capienza richiesta.
     */
    private TipologiaDiProva tipologiaIsolata(String tokenAdmin, int quante, int capienza) throws Exception {
        BigDecimal prezzo = dati.prezzoUnivoco();

        TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest()
                .capienzaMax(capienza)
                .prezzoNotte(prezzo);

        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long idTipologia = objectMapper.readTree(risposta).path("data").path("id").asLong();

        for (int i = 0; i < quante; i++) {
            mockMvc.perform(post(CAMERE)
                            .header("Authorization", "Bearer " + tokenAdmin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dati.cameraRequest(idTipologia))))
                    .andExpect(status().isCreated());
        }

        return new TipologiaDiProva(idTipologia, prezzo);
    }

    /** Prenota e conferma: e' cio' che toglie davvero una camera dalla disponibilita'. */
    private void occupa(long idTipologia, LocalDate arrivo, LocalDate partenza) throws Exception {
        String cliente = tokenCliente();

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + cliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PrenotazioneRequest()
                                .tipologiaCameraId(idTipologia)
                                .dataCheckIn(arrivo)
                                .dataCheckOut(partenza)
                                .numeroOspiti(1))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long idPrenotazione = objectMapper.readTree(risposta).path("data").path("id").asLong();

        mockMvc.perform(put(PRENOTAZIONI + "/" + idPrenotazione + "/conferma")
                        .header("Authorization", "Bearer " + cliente))
                .andExpect(status().isOk());
    }

    private LocalDate fraSettimane(int settimane) {
        return LocalDate.now().plusWeeks(settimane);
    }

    @Nested
    @DisplayName("GET /api/disponibilita")
    class Ricerca {

        @Test
        @DisplayName("senza token risponde 200: e' la domanda che si fa prima di registrarsi")
        void ricerca_senzaToken_risponde200() throws Exception {
            // given: una tipologia con due camere, nessuna prenotazione
            String admin = tokenAdmin();
            BigDecimal prezzo = tipologiaIsolata(admin, 2, 2).prezzo();
            LocalDate arrivo = fraSettimane(10);

            // when: nessun header Authorization
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(3).toString())
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then: 200, due camere libere e il totale di tre notti
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(2))
                    .andExpect(jsonPath("$.data[0].importoTotale")
                            .value(prezzo.multiply(BigDecimal.valueOf(3)).doubleValue()))
                    .andExpect(jsonPath("$.data[0].tipologia.prezzoNotte").value(prezzo.doubleValue()));
        }

        @Test
        @DisplayName("una tipologia esaurita compare lo stesso, con zero camere")
        void ricerca_conTipologiaEsaurita_mostraZero() throws Exception {
            // given: UNA camera, e quella camera gia' confermata nel periodo
            String admin = tokenAdmin();
            TipologiaDiProva tipologia = tipologiaIsolata(admin, 1, 2);
            BigDecimal prezzo = tipologia.prezzo();
            LocalDate arrivo = fraSettimane(11);
            occupa(tipologia.id(), arrivo, arrivo.plusDays(3));

            // when
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(3).toString())
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then: la riga c'e' con zero. Toglierla darebbe pagine di dimensione
                    // variabile, e direbbe al cliente che quella camera non esiste invece
                    // che è finita
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(0))
                    .andExpect(jsonPath("$.data[0].importoTotale")
                            .value(prezzo.multiply(BigDecimal.valueOf(3)).doubleValue()));
        }

        @Test
        @DisplayName("tre soggiorni brevi in fila lasciano libera la seconda camera")
        void ricerca_conSoggiorniConsecutivi_contaLaNottePeggiore() throws Exception {
            // given: DUE camere e tre confermate consecutive, che stanno tutte in una
            // camera sola perche' il giorno di partenza non e' occupato
            String admin = tokenAdmin();
            TipologiaDiProva tipologia = tipologiaIsolata(admin, 2, 2);
            BigDecimal prezzo = tipologia.prezzo();
            LocalDate arrivo = fraSettimane(12);

            occupa(tipologia.id(), arrivo, arrivo.plusDays(1));
            occupa(tipologia.id(), arrivo.plusDays(1), arrivo.plusDays(2));
            occupa(tipologia.id(), arrivo.plusDays(2), arrivo.plusDays(3));

            // when: si cercano tutte e tre le notti insieme
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(3).toString())
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then: UNA libera, non meno di zero. Contando le prenotazioni che
                    // toccano il periodo sarebbero tre su due camere: e' il difetto
                    // corretto in 85cdb51, qui visto dal lato del cliente
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(1));
        }

        @Test
        @DisplayName("il filtro sugli ospiti tiene fuori chi non li ospita")
        void ricerca_conNumeroOspiti_escludeLeTroppoPiccole() throws Exception {
            // given: una tipologia da due posti
            String admin = tokenAdmin();
            BigDecimal prezzo = tipologiaIsolata(admin, 1, 2).prezzo();
            LocalDate arrivo = fraSettimane(13);

            // when: se ne cercano per tre
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(1).toString())
                            .param("numeroOspiti", "3")
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then: pagina vuota. Non e' un errore — e' una domanda ben posta a cui
                    // la risposta e' "niente", come per gli altri filtri del progetto
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }

        @Test
        @DisplayName("con gli ospiti che ci stanno la tipologia resta")
        void ricerca_conNumeroOspitiCompatibile_laTiene() throws Exception {
            // given: la stessa tipologia da due posti
            String admin = tokenAdmin();
            BigDecimal prezzo = tipologiaIsolata(admin, 1, 2).prezzo();
            LocalDate arrivo = fraSettimane(14);

            // when: se ne cercano per due — il filtro e' "almeno", non "esattamente"
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(1).toString())
                            .param("numeroOspiti", "2")
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(1));
        }

        @Test
        @DisplayName("con la partenza prima dell'arrivo risponde 400 e non una pagina vuota")
        void ricerca_conDateInvertite_risponde400() throws Exception {
            // given
            LocalDate arrivo = fraSettimane(15);

            // when/then: un periodo di zero notti non e' una ricerca che non trova
            // niente, e' una richiesta che non vuol dire niente
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("con l'arrivo nel passato risponde 200: si sta guardando, non prenotando")
        void ricerca_conArrivoPassato_risponde200() throws Exception {
            // given: un periodo finito la settimana scorsa
            String admin = tokenAdmin();
            BigDecimal prezzo = tipologiaIsolata(admin, 1, 2).prezzo();
            LocalDate arrivo = LocalDate.now().minusDays(10);

            // when/then: 200, al contrario della creazione che qui darebbe 400. Chi sta
            // al banco deve poter controllare com'era andata la settimana scorsa
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(2).toString())
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(1));
        }

        @Test
        @DisplayName("una tipologia senza camere risulta a zero, non sparisce")
        void ricerca_conTipologiaSenzaCamere_mostraZero() throws Exception {
            // given: una tipologia a catalogo di cui nessuna stanza e' stata ancora creata
            String admin = tokenAdmin();
            BigDecimal prezzo = tipologiaIsolata(admin, 0, 2).prezzo();
            LocalDate arrivo = fraSettimane(16);

            // when
            mockMvc.perform(get(DISPONIBILITA)
                            .param("dataCheckIn", arrivo.toString())
                            .param("dataCheckOut", arrivo.plusDays(1).toString())
                            .param("prezzoMinimo", prezzo.toPlainString())
                            .param("prezzoMassimo", prezzo.toPlainString()))
                    // then: compare a zero. E' il caso in cui il conteggio raggruppato non
                    // restituisce nessuna riga — un group by non raggruppa righe che non
                    // esistono — e il default del service deve leggerlo come zero invece
                    // che far sparire la tipologia dalla pagina
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].camereDisponibili").value(0));
        }
    }
}
