package com.felixhotel.backend.api;

import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.support.CreatoreStaff;
import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I due punti in cui due richieste simultanee potevano vendere la stessa cosa due volte.
 *
 * <p><b>E' l'unico test del progetto che manda due richieste insieme</b>, e non poteva
 * essere altrimenti: il difetto che protegge non esiste finche' le richieste sono una per
 * volta. Fino al 2026-09-03 questi due punti erano un gap dichiarato, rimandato con una
 * ragione scritta — <i>"sarebbe un meccanismo di concorrenza che nessun test di questa
 * suite sa vedere funzionare, e la regola 22 dice che un controllo mai visto agire non e'
 * un controllo"</i>. Questa classe e' quel che mancava.
 *
 * <p><b>Come si fa a vedere una corsa.</b> Due thread arrivano a una barriera e ripartono
 * insieme, cosi' che le due transazioni si sovrappongano davvero invece di succedersi.
 *
 * <p><b>Che questi due test vedano davvero il lock e' stato verificato togliendolo</b>, ed
 * e' l'unico modo di saperlo: un test di concorrenza che passa puo' farlo perche' la difesa
 * funziona o perche' la corsa non e' avvenuta, e le due cose da fuori si assomigliano.
 * Con i due lock commentati, <b>tutti e due i test falliscono, in tre esecuzioni su tre</b>
 * — cioe' la finestra e' larga abbastanza da essere colpita ogni volta, e non serve
 * ripetere il test cento volte per vederla.
 *
 * <p><b>Cosa si guarda: l'esito, non il modo.</b> Il test non sa niente di lock
 * pessimistici — chiede due volte la stessa ultima camera e pretende che una sola delle
 * due passi. Il giorno che il rimedio cambiasse forma, questo test continuerebbe a valere.
 */
@DisplayName("Due richieste nello stesso istante")
class ConcorrenzaApiIT extends IntegrationTestBase {

    private static final String PRENOTAZIONI = "/api/prenotazioni";
    private static final String TIPOLOGIE = "/api/tipologie-camera";
    private static final String CAMERE = "/api/camere";

    @Autowired
    private CreatoreStaff creatoreStaff;

    @Test
    @DisplayName("due conferme sull'ultima camera: ne passa una sola")
    void conferma_dueSimultanee_nePassaUna() throws Exception {
        // given: una tipologia con **una** camera e due carrelli in attesa sullo stesso
        // periodo. Finche' restano IN_ATTESA non occupano niente: e' la conferma a vendere
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        creaCamera(admin, tipologia);
        LocalDate arrivo = LocalDate.now().plusDays(300);

        String primo = tokenCliente();
        String secondo = tokenCliente();
        long prenotazionePrimo = creaPrenotazione(primo, tipologia, arrivo);
        long prenotazioneSecondo = creaPrenotazione(secondo, tipologia, arrivo);

        // when: le due conferme partono insieme
        List<Integer> esiti = insieme(
                () -> stato(put(PRENOTAZIONI + "/" + prenotazionePrimo + "/conferma")
                        .header("Authorization", "Bearer " + primo)),
                () -> stato(put(PRENOTAZIONI + "/" + prenotazioneSecondo + "/conferma")
                        .header("Authorization", "Bearer " + secondo)));

        // then: una sola vende. L'altra riceve un 409 — la camera non c'e' piu' — che e'
        // esattamente la risposta che avrebbe ricevuto arrivando un secondo dopo
        assertThat(esiti)
                .as("una conferma sola deve passare, altrimenti l'albergo ha venduto"
                        + " una camera che non ha")
                .containsExactlyInAnyOrder(200, 409);
    }

    @Test
    @DisplayName("due check-in sull'unica camera libera: ne passa uno solo")
    void checkIn_dueSimultanei_nePassaUno() throws Exception {
        // given: **due camere**, perche' due prenotazioni sulla stessa notte in una camera
        // sola non si potrebbero nemmeno vendere — e infatti la creazione le rifiuta. Poi
        // una delle due si mette fuori uso, e le due prenotazioni gia' vendute si trovano
        // a contendersi l'unica stanza rimasta. E' il caso vero: una camera si rompe la
        // mattina, e alle quattro arrivano tutti insieme
        String admin = tokenAdmin();
        long tipologia = creaTipologia(admin);
        long libera = creaCamera(admin, tipologia);
        long fuoriUso = creaCamera(admin, tipologia);

        long prima = prenotazioneDaRegistrare(admin, tipologia, LocalDate.now());
        long seconda = prenotazioneDaRegistrare(admin, tipologia, LocalDate.now());

        mockMvc.perform(put(CAMERE + "/" + fuoriUso + "/stato")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CameraStatoRequest().stato(StatoCamera.MANUTENZIONE))))
                .andExpect(status().isOk());

        assertThat(libera).as("la camera che resta").isNotEqualTo(fuoriUso);

        // when
        List<Integer> esiti = insieme(
                () -> stato(put(PRENOTAZIONI + "/" + prima + "/check-in")
                        .header("Authorization", "Bearer " + admin)),
                () -> stato(put(PRENOTAZIONI + "/" + seconda + "/check-in")
                        .header("Authorization", "Bearer " + admin)));

        // then: due chiavi della stessa stanza date a due persone diverse e' il difetto
        // che si vede subito e imbarazza sul posto
        assertThat(esiti)
                .as("un check-in solo deve passare, altrimenti due ospiti hanno la"
                        + " stessa stanza")
                .containsExactlyInAnyOrder(200, 409);
    }

    // ---------------------------------------------------------------- supporto

    /**
     * Fa partire due richieste il piu' vicino possibile.
     *
     * <p>La barriera serve a questo: senza, il primo thread finirebbe prima che il secondo
     * cominci, e il test proverebbe due richieste in fila — cioe' il caso che gia'
     * funzionava. Il pool ha due thread proprio perche' la barriera possa aprirsi.
     */
    private List<Integer> insieme(Callable<Integer> primo, Callable<Integer> secondo)
            throws Exception {
        CyclicBarrier via = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<Integer>> future = new ArrayList<>();
            for (Callable<Integer> richiesta : List.of(primo, secondo)) {
                future.add(pool.submit(() -> {
                    via.await();
                    return richiesta.call();
                }));
            }

            List<Integer> esiti = new ArrayList<>();
            for (Future<Integer> f : future) {
                esiti.add(f.get());
            }
            return esiti;

        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Lo status di una richiesta, senza pretendere che sia quello giusto.
     *
     * <p>Nessun {@code andExpect} qui: quale delle due passi non lo si sa in anticipo, ed
     * e' proprio la domanda del test. Un'eccezione risalita fin qui sarebbe invece un
     * fallimento vero, e infatti si propaga.
     */
    private int stato(org.springframework.test.web.servlet.RequestBuilder richiesta)
            throws Exception {
        MvcResult risultato = mockMvc.perform(richiesta).andReturn();
        return risultato.getResponse().getStatus();
    }

    /** Una prenotazione confermata che arriva oggi, con l'ospite gia' registrato. */
    private long prenotazioneDaRegistrare(String admin, long tipologia, LocalDate arrivo)
            throws Exception {
        String cliente = tokenCliente();
        long id = creaPrenotazione(cliente, tipologia, arrivo);

        mockMvc.perform(put(PRENOTAZIONI + "/" + id + "/conferma")
                        .header("Authorization", "Bearer " + cliente))
                .andExpect(status().isOk());

        mockMvc.perform(post(PRENOTAZIONI + "/" + id + "/ospiti")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.ospiteRequest())))
                .andExpect(status().isCreated());
        return id;
    }

    private long creaPrenotazione(String token, long tipologia, LocalDate arrivo) throws Exception {
        PrenotazioneRequest richiesta = dati.prenotazioneRequest(tipologia)
                .dataCheckIn(arrivo)
                .dataCheckOut(arrivo.plusDays(2))
                .numeroOspiti(1);

        String risposta = mockMvc.perform(post(PRENOTAZIONI)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(richiesta)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private long creaTipologia(String tokenAdmin) throws Exception {
        String risposta = mockMvc.perform(post(TIPOLOGIE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.tipologiaCameraRequest())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

    private long creaCamera(String tokenAdmin, long tipologia) throws Exception {
        String risposta = mockMvc.perform(post(CAMERE)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(dati.cameraRequest(tipologia))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(risposta).path("data").path("id").asLong();
    }

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
}
