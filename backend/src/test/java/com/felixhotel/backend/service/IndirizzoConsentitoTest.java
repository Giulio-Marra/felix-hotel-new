package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.IndirizzoConsentito;
import com.felixhotel.backend.service.impl.IndirizzoConsentito.IndirizzoNonConsentitoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La difesa dell'unica rotta del progetto che faccia partire una richiesta verso un
 * indirizzo scelto da qualcun altro.
 *
 * <p><b>Nessun indirizzo di questi test viene interrogato davvero</b>: sono tutti forme
 * numeriche, che {@code InetAddress} risolve senza chiedere niente al DNS. E' voluto — un
 * test che dipendesse da un resolver vero fallirebbe sulla macchina di chi lavora senza
 * rete, e proverebbe il DNS invece della nostra regola.
 */
@DisplayName("IndirizzoConsentito")
class IndirizzoConsentitoTest {

    /**
     * La configurazione di <b>produzione</b>, cioe' quella che conta: il default e' chiuso
     * e i profili {@code dev} e {@code test} lo aprono, ma quel che va provato qui e' il
     * comportamento senza aperture. Un test che girasse con l'apertura del profilo non
     * proverebbe niente.
     */
    private static final IndirizzoConsentito CHIUSO = new IndirizzoConsentito(false);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // Il caso che si fa incollando un indirizzo, ed e' il piu' comune: la macchina
            // stessa. Un Postgres, un Redis, una console di amministrazione.
            "http://127.0.0.1:5432/calendario.ics",
            "http://localhost:8080/calendario.ics",
            // Il piu' pericoloso in cloud: l'endpoint dei metadati, che su piu' di un
            // fornitore serve credenziali a chiunque sappia chiederle da dentro.
            "http://169.254.169.254/latest/meta-data/",
            // Le tre reti private, che sono il resto dell'ufficio
            "http://10.0.0.5/calendario.ics",
            "http://192.168.1.1/calendario.ics",
            "http://172.16.0.1/calendario.ics",
            // 0.0.0.0 vale "questa macchina" quasi ovunque
            "http://0.0.0.0/calendario.ics",
            // IPv6: il loopback e l'unique-local, che e' l'equivalente moderno del 10.x e
            // che isSiteLocalAddress() non vede
            "http://[::1]/calendario.ics",
            "http://[fd00::1]/calendario.ics"})
    @DisplayName("un indirizzo dentro la rete si rifiuta")
    void verifica_indirizzoInterno_solleva(String url) {
        assertThatThrownBy(() -> CHIUSO.verifica(url))
                .isInstanceOf(IndirizzoNonConsentitoException.class)
                .hasMessageContaining("punta dentro la rete");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "jar:file:///tmp/x.jar!/calendario.ics",
            "ftp://esempio.invalid/calendario.ics",
            "gopher://esempio.invalid/"})
    @DisplayName("uno schema che non sia http o https si rifiuta")
    void verifica_schemaNonAmmesso_solleva(String url) {
        assertThatThrownBy(() -> CHIUSO.verifica(url))
                .isInstanceOf(IndirizzoNonConsentitoException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"http://8.8.8.8/calendario.ics", "https://1.1.1.1/calendario.ics"})
    @DisplayName("un indirizzo pubblico passa")
    void verifica_indirizzoPubblico_passa(String url) {
        // La meta' che impedisce di "risolvere" il problema rifiutando tutto: una difesa
        // che non lascia passare nemmeno Booking non e' una difesa, e' una rotta rotta
        assertThatCode(() -> CHIUSO.verifica(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un nome che non si risolve si rifiuta invece di essere provato")
    void verifica_nomeInesistente_solleva() {
        // .invalid e' riservato dalla specifica proprio per non risolversi mai. Rifiutarlo
        // qui non e' pignoleria: un nome che non si risolve non si puo' nemmeno
        // controllare, e lasciarlo passare vorrebbe dire saltare la verifica
        assertThatThrownBy(() ->
                CHIUSO.verifica("http://questo-non-esiste-davvero.invalid/x.ics"))
                .isInstanceOf(IndirizzoNonConsentitoException.class)
                .hasMessageContaining("non si risolve");
    }

    @Test
    @DisplayName("il messaggio non dice a quale indirizzo il nome puntava")
    void verifica_messaggio_nonRivelaLIndirizzo() {
        // Sarebbe proprio la risposta che l'attacco cerca — "questo nome punta dentro, ed
        // e' il 10.0.0.5" — servita in chiaro a chi la stava cercando
        assertThatThrownBy(() -> CHIUSO.verifica("http://10.0.0.5/calendario.ics"))
                .hasMessageNotContaining("10.0.0.5/")
                .hasMessageContaining("10.0.0.5");
    }

    @Test
    @DisplayName("col profilo che apre, il loopback passa")
    void verifica_conAperturaDiSviluppo_passa() {
        // E' l'altra meta' della configurazione, e va provata quanto la prima: senza,
        // nessuno si accorgerebbe che l'apertura non funziona — finche' non si prova a
        // sviluppare contro un canale finto
        IndirizzoConsentito aperto = new IndirizzoConsentito(true);

        assertThatCode(() -> aperto.verifica("http://127.0.0.1:9099/calendario.ics"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/calendario.ics",
            "http://192.168.1.1/calendario.ics"})
    @DisplayName("l'apertura vale per il loopback e non per il resto della rete")
    void verifica_conApertura_ilRestoRestaChiuso(String url) {
        // **La restrizione fatta rileggendo.** La prima stesura apriva tutti gli indirizzi
        // interni, e cosi' in sviluppo sarebbe passato anche l'endpoint dei metadati del
        // cloud — cioe' proprio il caso che questa classe esiste per fermare, reso non
        // provabile nell'unico ambiente in cui si prova
        IndirizzoConsentito aperto = new IndirizzoConsentito(true);

        assertThatThrownBy(() -> aperto.verifica(url))
                .isInstanceOf(IndirizzoNonConsentitoException.class)
                .hasMessageContaining("punta dentro la rete");
    }

    @Test
    @DisplayName("nemmeno col profilo aperto passa uno schema che non sia http")
    void verifica_conApertura_loSchemaRestaChiuso() {
        // L'apertura riguarda **dove** si puo' andare, non **come**: un file: non e' un
        // calendario nemmeno in sviluppo
        IndirizzoConsentito aperto = new IndirizzoConsentito(true);

        assertThatThrownBy(() -> aperto.verifica("file:///etc/passwd"))
                .isInstanceOf(IndirizzoNonConsentitoException.class);
    }

    @Test
    @DisplayName("un indirizzo senza host si rifiuta")
    void verifica_senzaHost_solleva() {
        assertThatThrownBy(() -> CHIUSO.verifica("http:///calendario.ics"))
                .isInstanceOf(IndirizzoNonConsentitoException.class);
    }

    @Test
    @DisplayName("un indirizzo scritto male si rifiuta invece di rompersi")
    void verifica_malformato_solleva() {
        assertThatThrownBy(() -> CHIUSO.verifica("non un indirizzo"))
                .isInstanceOf(IndirizzoNonConsentitoException.class);
    }
}
