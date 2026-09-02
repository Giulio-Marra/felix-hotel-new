package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.Sesso;
import com.felixhotel.backend.dto.TipoAlloggiato;
import com.felixhotel.backend.dto.TipoCodifica;
import com.felixhotel.backend.dto.VoceCodifica;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.service.impl.CodiciAlloggiati;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test di integrazione dell'export delle schedine alloggiati.
 *
 * <p><b>L'isolamento qui e' il problema piu' grosso di tutta la suite, e vale la pena
 * capirlo prima di aggiungere un test.</b> L'export e' <i>per giorno</i>, e una
 * prenotazione puo' trovarsi in CHECK_IN solo se e' arrivata <b>oggi</b>: il check-in
 * si fa dal giorno di arrivo in poi, e la creazione rifiuta un arrivo nel passato.
 * Quindi ogni prenotazione arrivata di questa suite ha per forza la data di oggi — e
 * due test qualunque, anche di classi diverse, finirebbero nello stesso file.
 * Peggio: l'export e' tutto-o-niente, quindi un ospite incompleto lasciato da un
 * altro test renderebbe 409 anche i test corretti.
 *
 * <p><b>Il rimedio e' {@link #spostaNelPassato(long)}</b>: si costruisce la
 * prenotazione dal bordo HTTP come farebbe una persona — creazione, conferma, ospiti,
 * check-in — e <i>poi</i> le si sposta la data di arrivo in un giorno del passato
 * tutto suo. Lo stato che ne esce e' esattamente quello che il sistema avrebbe avuto
 * se quella prenotazione fosse davvero arrivata quel giorno, quindi non si sta
 * provando una configurazione irraggiungibile: si sta ricreando ieri.
 *
 * <p><b>Le codifiche invece si isolano da sole</b>, ed e' un regalo di una decisione
 * presa per un'altra ragione: l'import <i>sostituisce</i> la famiglia invece di
 * fonderla (V12), quindi qualunque test parta trova le quattro famiglie come se le e'
 * scritte lui.
 */
@DisplayName("API dell'export alloggiati")
class AlloggiatiApiIT extends IntegrationTestBase {

    private static final String SCHEDINE = "/api/alloggiati/schedine";
    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CODIFICHE = "/api/codifiche/";

    /** Il comune di prova, con la sua provincia: il tracciato le vuole tutte e due. */
    private static final String CODICE_COMUNE = "058091";
    private static final String CODICE_STATO = "100000100";

    /**
     * Da quanti giorni indietro comincia ad assegnare i giorni ai test.
     *
     * <p>Abbastanza indietro da non incrociare nessuna prenotazione che gli altri test
     * della suite creano — quelli lavorano su oggi e sul futuro.
     */
    private static final int PRIMO_GIORNO_INDIETRO = 400;

    /**
     * Il contatore che da' a ogni test il suo giorno.
     *
     * <p>Statico perche' l'unicita' deve valere fra i metodi della classe, e i campi
     * d'istanza rinascono a ogni test. E' la stessa forma dei nomi univoci di
     * {@code TestDataFactory}.
     */
    private static final AtomicInteger GIORNI_USATI = new AtomicInteger();

    @Autowired
    private CreatoreStaff creatoreStaff;

    /**
     * Serve solo a {@link #spostaNelPassato(long)}. E' l'unica volta in cui un IT di
     * questo progetto scrive nel database invece di passare da un endpoint, ed e'
     * scritto in cima perche' non diventi un'abitudine: qui non c'e' alternativa,
     * perche' nessun endpoint puo' produrre una prenotazione arrivata ieri.
     */
    @Autowired
    private PrenotazioneRepository prenotazioneRepository;

    @Test
    @DisplayName("un giorno senza arrivi da' zero schedine e un file vuoto")
    void esporta_giornoSenzaArrivi_risponde200() throws Exception {
        // given: un giorno tutto suo su cui nessuno e' mai arrivato
        String staff = tokenStaff();
        LocalDate giorno = giornoRiservato();

        // when / then: 200 e non 404 — chi automatizza il download non deve
        // distinguere una giornata vuota da un guasto
        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.numeroSchedine").value(0))
                .andExpect(jsonPath("$.data.contenuto").value(""))
                .andExpect(jsonPath("$.data.nomeFile").isNotEmpty());
    }

    @Test
    @DisplayName("una famiglia arrivata produce due righe da 168 caratteri")
    void esporta_famigliaArrivata_produceIlTracciato() throws Exception {
        // given: un capofamiglia col documento e un figlio senza, cioe' il gruppo
        // tipico e insieme il caso che il V10 esiste per permettere
        String admin = tokenAdmin();
        importaCodifiche(admin);
        long prenotazione = prenotazioneArrivata(admin,
                capofamiglia("ROSSI", "MARIO"),
                familiare("ROSSI", "LUCA"));
        LocalDate giorno = spostaNelPassato(prenotazione);

        // when / then
        String risposta = mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.numeroSchedine").value(2))
                .andExpect(jsonPath("$.data.data").value(giorno.toString()))
                .andReturn().getResponse().getContentAsString();

        String contenuto = objectMapper.readTree(risposta).path("data").path("contenuto").asText();

        // due righe da 168 piu' i due terminatori CRLF, l'ultimo compreso
        assertThat(contenuto).hasSize(2 * 168 + 4);
        // il capofamiglia porta il suo documento, il figlio ha le caselle in bianco:
        // e' l'unica differenza di forma fra i due gruppi di valori, ed e' tutto il
        // motivo per cui il tipo di alloggiato esiste
        assertThat(contenuto)
                .startsWith(CodiciAlloggiati.codice(com.felixhotel.backend.entity.enums.TipoAlloggiato.CAPOFAMIGLIA))
                .contains("CA12345AB")
                .endsWith("\r\n");
        String rigaFiglio = contenuto.substring(170, 170 + 168);
        assertThat(rigaFiglio.substring(134))
                .as("le tre caselle del documento di un familiare restano vuote")
                .isBlank();
    }

    @Test
    @DisplayName("un ospite senza i dati della schedina blocca l'export e il messaggio dice chi")
    void esporta_ospiteIncompleto_risponde409() throws Exception {
        // given: un ospite registrato come il registro degli ospiti permette — nome,
        // cognome, documento, data di nascita — e nient'altro. E' il caso normale di
        // chi ha registrato in fretta al banco, non un difetto
        String admin = tokenAdmin();
        importaCodifiche(admin);
        long prenotazione = prenotazioneArrivata(admin, dati.ospiteRequest().cognome("VERDI"));
        LocalDate giorno = spostaNelPassato(prenotazione);

        // when / then: il messaggio nomina la persona. Senza, chi sta al banco dovrebbe
        // riaprire una per una le prenotazioni della giornata per capire chi manca
        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("VERDI")));
    }

    @Test
    @DisplayName("senza le codifiche importate l'export si ferma invece di inventare codici")
    void esporta_senzaCodifiche_risponde409() throws Exception {
        // given: un'installazione nuova. Le tabelle di codifica nascono vuote di
        // proposito (V12) e le riempie l'ADMIN dal portale
        String admin = tokenAdmin();
        importaCodifiche(admin);
        long prenotazione = prenotazioneArrivata(admin, capofamiglia("NERI", "ANNA"));
        LocalDate giorno = spostaNelPassato(prenotazione);
        svuotaCodifica(admin, TipoCodifica.TIPO_ALLOGGIATO);

        // when / then: 409 e non un file di codici inventati, che e' esattamente il
        // difetto che la quarta riga della regola 24 esiste per evitare
        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("/api/codifiche/TIPO_ALLOGGIATO")));
    }

    @Test
    @DisplayName("una prenotazione confermata e mai arrivata non finisce nel file")
    void esporta_prenotazioneNonArrivata_nonCompare() throws Exception {
        // given: confermata, con gli ospiti registrati, ma nessuno si e' presentato
        String admin = tokenAdmin();
        importaCodifiche(admin);
        long idTipologia = tipologiaPrenotabile(admin);
        String cliente = tokenCliente();
        LocalDate oggi = LocalDate.now();
        long id = creaPrenotazione(cliente, idTipologia, oggi, oggi.plusDays(3), 2);
        confermaPrenotazione(cliente, id);
        registraOspite(admin, id, capofamiglia("GIALLI", "PAOLO"));
        registraOspite(admin, id, familiare("GIALLI", "SARA"));
        LocalDate giorno = spostaNelPassato(id);

        // when / then: comunicare un arrivo che non c'e' stato vorrebbe dire dichiarare
        // alla Questura che qualcuno dorme qui quando non c'e' — cioe' scrivere il falso
        // in una comunicazione di legge, che e' peggio di non mandarla
        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.numeroSchedine").value(0));
    }

    @Test
    @DisplayName("il cliente non ci arriva nemmeno, e l'anonimo prende 401")
    void esporta_senzaPermesso_rispondeVietato() throws Exception {
        // given
        LocalDate giorno = giornoRiservato();

        // when / then: nessuna lettura pubblica, e nemmeno per il cliente sulla propria
        // prenotazione — quel che passa di qui sono documenti di persone che non sono
        // nemmeno clienti dell'albergo
        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(SCHEDINE).param("data", giorno.toString())
                        .header("Authorization", "Bearer " + tokenCliente()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("una data scritta storta si ferma al bordo con un 400")
    void esporta_dataNonValida_risponde400() throws Exception {
        // when / then: e' la regola 21 — il valore sbagliato non deve arrivare al
        // Service per rompersi li', cioe' un 500 al posto di un 400
        mockMvc.perform(get(SCHEDINE).param("data", "non-una-data")
                        .header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("senza la data e' 400: il parametro e' obbligatorio")
    void esporta_senzaData_risponde400() throws Exception {
        // when / then: fino al 2026-09-02 questo era un **500**, e non solo qui — ogni
        // parametro obbligatorio del progetto lo era, {@code /api/disponibilita}
        // compresa, che e' per giunta pubblica. L'ha trovato il punto 2 della checklist
        // provando questa rotta con curl; il rimedio sta nel gestore centralizzato e il
        // test della classe di difetti in GlobalExceptionHandlerIT
        mockMvc.perform(get(SCHEDINE)
                        .header("Authorization", "Bearer " + tokenStaff()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("data")));
    }

    // ---------------------------------------------------------------- supporto

    /**
     * Un giorno del passato che nessun altro test di questa classe usera'.
     *
     * <p>Sta indietro di oltre un anno perche' il resto della suite lavora su oggi e
     * sul futuro: cosi' le due popolazioni non si incrociano mai.
     */
    private LocalDate giornoRiservato() {
        return LocalDate.now().minusDays(PRIMO_GIORNO_INDIETRO + GIORNI_USATI.getAndIncrement());
    }

    /**
     * Sposta una prenotazione gia' arrivata in un giorno del passato tutto suo,
     * conservandone la durata.
     *
     * <p><b>E' l'unica scrittura diretta nel database di tutti gli IT del progetto</b>,
     * e la ragione e' che nessun endpoint puo' produrre questo stato: il check-in si fa
     * il giorno dell'arrivo e la creazione rifiuta gli arrivi passati, quindi dal bordo
     * HTTP <i>ogni</i> prenotazione arrivata ha la data di oggi. Senza questo
     * spostamento l'export di un test vedrebbe gli ospiti di tutti gli altri, e
     * basterebbe un ospite incompleto lasciato altrove per rendere 409 anche i test
     * corretti.
     *
     * <p>Lo stato che ne risulta non e' inventato: e' esattamente quello che il sistema
     * avrebbe se quella prenotazione fosse arrivata davvero quel giorno.
     *
     * @return il giorno di arrivo assegnato, cioe' quello da chiedere all'export
     */
    private LocalDate spostaNelPassato(long idPrenotazione) {
        LocalDate giorno = giornoRiservato();
        Prenotazione prenotazione = prenotazioneRepository.findById(idPrenotazione).orElseThrow();
        // Tutte e due le date, dello stesso scarto: il tracciato scrive anche le notti
        // di permanenza, e spostare solo l'arrivo le cambierebbe
        long durata = java.time.temporal.ChronoUnit.DAYS.between(
                prenotazione.getDataCheckIn(), prenotazione.getDataCheckOut());
        prenotazione.setDataCheckIn(giorno);
        prenotazione.setDataCheckOut(giorno.plusDays(durata));
        prenotazioneRepository.save(prenotazione);
        return giorno;
    }

    /** Le quattro famiglie con dentro i codici che questo test usera'. */
    private void importaCodifiche(String tokenAdmin) throws Exception {
        importa(tokenAdmin, TipoCodifica.COMUNE, List.of(
                new VoceCodifica().codice(CODICE_COMUNE).descrizione("ROMA").provincia("RM")));
        importa(tokenAdmin, TipoCodifica.STATO, List.of(
                new VoceCodifica().codice(CODICE_STATO).descrizione("ITALIA")));
        // I codici si chiedono a CodiciAlloggiati invece di essere scritti a mano: e' il
        // verso giusto della dipendenza. Il test importa quel che l'applicazione andra'
        // a cercare, quindi resta vero anche il giorno in cui quelle costanti verranno
        // corrette col file vero del Ministero
        importa(tokenAdmin, TipoCodifica.TIPO_DOCUMENTO, List.of(
                new VoceCodifica()
                        .codice(CodiciAlloggiati.codice(
                                com.felixhotel.backend.entity.enums.TipoDocumento.CARTA_IDENTITA))
                        .descrizione("CARTA DI IDENTITA")));
        importa(tokenAdmin, TipoCodifica.TIPO_ALLOGGIATO, List.of(
                voceAlloggiato(com.felixhotel.backend.entity.enums.TipoAlloggiato.OSPITE_SINGOLO),
                voceAlloggiato(com.felixhotel.backend.entity.enums.TipoAlloggiato.CAPOFAMIGLIA),
                voceAlloggiato(com.felixhotel.backend.entity.enums.TipoAlloggiato.FAMILIARE)));
    }

    private VoceCodifica voceAlloggiato(com.felixhotel.backend.entity.enums.TipoAlloggiato tipo) {
        return new VoceCodifica().codice(CodiciAlloggiati.codice(tipo)).descrizione(tipo.name());
    }

    private void importa(String tokenAdmin, TipoCodifica tipo, List<VoceCodifica> voci) throws Exception {
        mockMvc.perform(put(CODIFICHE + tipo.getValue())
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(voci)))
                .andExpect(status().isOk());
    }

    /** Svuota una famiglia: e' come si trova un'installazione appena fatta. */
    private void svuotaCodifica(String tokenAdmin, TipoCodifica tipo) throws Exception {
        importa(tokenAdmin, tipo, List.of());
    }

    private OspiteRequest capofamiglia(String cognome, String nome) {
        return schedinaCompleta(dati.ospiteRequest().cognome(cognome).nome(nome))
                .tipoAlloggiato(TipoAlloggiato.CAPOFAMIGLIA)
                .luogoRilascioDocumento(CODICE_COMUNE);
    }

    /**
     * Un familiare minorenne: niente documento (V10) e niente luogo di rilascio, che
     * senza documento sarebbe 400.
     */
    private OspiteRequest familiare(String cognome, String nome) {
        return schedinaCompleta(dati.ospiteMinorenneRequest().cognome(cognome).nome(nome))
                .tipoAlloggiato(TipoAlloggiato.FAMILIARE);
    }

    private OspiteRequest schedinaCompleta(OspiteRequest richiesta) {
        return richiesta
                .sesso(Sesso.M)
                .comuneNascita(CODICE_COMUNE)
                .cittadinanza(CODICE_STATO);
    }

    /** Una prenotazione arrivata oggi, con gli ospiti registrati e il check-in fatto. */
    private long prenotazioneArrivata(String tokenAdmin, OspiteRequest... ospiti) throws Exception {
        long idTipologia = tipologiaPrenotabile(tokenAdmin);
        String cliente = tokenCliente();
        LocalDate oggi = LocalDate.now();

        // Il numero di ospiti della prenotazione e' quello che questo test registrera':
        // il check-in pretende un'uguaglianza, non un "almeno uno"
        long id = creaPrenotazione(cliente, idTipologia, oggi, oggi.plusDays(3), ospiti.length);
        confermaPrenotazione(cliente, id);
        for (OspiteRequest ospite : ospiti) {
            registraOspite(tokenAdmin, id, ospite);
        }

        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/check-in")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andExpect(status().isOk());

        return id;
    }

    private long creaPrenotazione(String tokenCliente, long idTipologia, LocalDate arrivo,
                                  LocalDate partenza, int numeroOspiti) throws Exception {
        // Il numero di ospiti si passa invece di lasciare quello della fabbrica, perche'
        // il check-in pretende che i registrati siano **esattamente** tanti: una
        // prenotazione per due con un ospite solo non arriva mai in CHECK_IN, quindi non
        // finirebbe in nessun file
        PrenotazioneRequest richiesta = dati.prenotazioneRequest(idTipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(partenza)
                .numeroOspiti(numeroOspiti);

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private void confermaPrenotazione(String tokenCliente, long id) throws Exception {
        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/conferma")
                        .header("Authorization", "Bearer " + tokenCliente))
                .andExpect(status().isOk());
    }

    private void registraOspite(String token, long idPrenotazione, OspiteRequest richiesta) throws Exception {
        mockMvc.perform(post(PRENOTAZIONI + "/" + idPrenotazione + "/ospiti")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated());
    }

    /** Una tipologia con una camera dentro: la camera serve alla conferma. */
    private long tipologiaPrenotabile(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idTipologia = objectMapper.readTree(risposta).path("data").path("id").asLong();

        mockMvc.perform(post("/api/camere")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(idTipologia))))
                .andExpect(status().isCreated());

        return idTipologia;
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
}
