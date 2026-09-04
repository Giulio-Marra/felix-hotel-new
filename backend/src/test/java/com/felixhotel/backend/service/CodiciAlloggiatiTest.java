package com.felixhotel.backend.service;

import com.felixhotel.backend.entity.enums.Sesso;
import com.felixhotel.backend.entity.enums.TipoAlloggiato;
import com.felixhotel.backend.entity.enums.TipoDocumento;
import com.felixhotel.backend.service.impl.CodiciAlloggiati;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La corrispondenza fra i nostri elenchi e i codici del Ministero.
 *
 * <p><b>Quel che questo test puo' provare, e quel che non puo'.</b> Non puo' dire se
 * "IDENT" sia davvero il codice della carta d'identita': quel dato lo pubblica il
 * Ministero e nel progetto non c'e', per la stessa decisione che ha lasciato
 * {@code voce_codifica} vuota — scriverlo qui dentro come atteso vorrebbe dire
 * confrontare una costante con una copia di se stessa, che e' il modo classico di
 * avere un test verde che non prova niente.
 *
 * <p><b>Quel che invece prova e' la completezza</b>, che e' l'unico modo in cui
 * queste mappe possono rompersi da sole: qualcuno aggiunge un valore a uno dei tre
 * enum — un quinto documento, un sesto tipo di alloggiato — e si dimentica la riga
 * corrispondente. Senza questo test se ne accorgerebbe la prima schedina di quel
 * tipo, cioe' in produzione.
 *
 * <p>La verifica che i codici siano <i>giusti</i> sta altrove ed e' a runtime:
 * l'export cerca ogni codice nella famiglia importata dal portale e risponde 409 se
 * non lo trova. Vedi {@code AlloggiatiServiceImpl.verificaCodici}.
 *
 * <p>Unitario: tre mappe e tre enum, niente Spring.
 */
@DisplayName("CodiciAlloggiati")
class CodiciAlloggiatiTest {

    @Test
    @DisplayName("ogni tipo di alloggiato ha il suo codice")
    void codice_ogniTipoAlloggiato_esiste() {
        // when/then: se un valore mancasse, richiedi() solleverebbe IllegalStateException
        // e il test sarebbe rosso qui invece che davanti a un albergatore
        for (TipoAlloggiato tipo : TipoAlloggiato.values()) {
            assertThat(CodiciAlloggiati.codice(tipo))
                    .as("codice ministeriale di %s", tipo)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("ogni documento ammesso ha il suo codice, e il permesso di soggiorno non lo e'")
    void codice_ogniTipoDocumentoAmmesso_esiste() {
        // **Non tutti i nostri documenti sono esportabili**, ed e' la scoperta del
        // confronto col file del Ministero (2026-09-03): fra i novantacinque documenti
        // ammessi il permesso di soggiorno non c'e'. Prima era mappato su "PERMS", un
        // codice inventato che il portale avrebbe rifiutato.
        for (TipoDocumento tipo : TipoDocumento.values()) {
            if (CodiciAlloggiati.ammessoDalMinistero(tipo)) {
                assertThat(CodiciAlloggiati.codice(tipo))
                        .as("codice ministeriale di %s", tipo)
                        .isNotBlank();
            }
        }

        assertThat(CodiciAlloggiati.ammessoDalMinistero(TipoDocumento.PERMESSO_SOGGIORNO))
                .as("il permesso di soggiorno non e' un documento che il Ministero accetta")
                .isFalse();
    }

    @Test
    @DisplayName("i codici dei tipi di alloggiato sono quelli del file del Ministero")
    void codiciTipoAlloggiato_combacianoConLaFonte() throws Exception {
        // **Questo test e' la ragione per cui le due tabelle stanno fra le risorse.** Fino
        // al 2026-09-03 queste costanti erano l'unico punto del progetto scritto senza una
        // fonte davanti, e il gap lo diceva. Adesso la fonte c'e' e il confronto si rifa'
        // ad ogni build: se il Ministero cambia un codice, a dirlo e' una build rossa e non
        // una schedina rifiutata.
        Map<String, String> ufficiali = tabella("tipi-alloggiato.csv");

        for (TipoAlloggiato tipo : TipoAlloggiato.values()) {
            assertThat(ufficiali)
                    .as("il codice %s di %s deve esistere nella tabella del Ministero",
                            CodiciAlloggiati.codice(tipo), tipo)
                    .containsKey(CodiciAlloggiati.codice(tipo));
        }

        // E il verso opposto: i cinque valori del nostro enum coprono i cinque della
        // tabella, quindi non c'e' un tipo di alloggiato che non sapremmo dichiarare
        assertThat(ufficiali).hasSize(TipoAlloggiato.values().length);
    }

    @Test
    @DisplayName("i codici dei documenti sono quelli del file del Ministero")
    void codiciTipoDocumento_combacianoConLaFonte() throws Exception {
        Map<String, String> ufficiali = tabella("tipi-documento.csv");

        for (TipoDocumento tipo : TipoDocumento.values()) {
            if (CodiciAlloggiati.ammessoDalMinistero(tipo)) {
                assertThat(ufficiali)
                        .as("il codice %s di %s deve esistere nella tabella del Ministero",
                                CodiciAlloggiati.codice(tipo), tipo)
                        .containsKey(CodiciAlloggiati.codice(tipo));
            }
        }

        // I tre codici usati, con la descrizione che il Ministero gli da': e' il controllo
        // che prende lo **scambio**, cioe' l'errore che un semplice "esiste" non vedrebbe.
        // Un PASOR messo sulla patente esisterebbe eccome.
        assertThat(ufficiali.get(CodiciAlloggiati.codice(TipoDocumento.CARTA_IDENTITA)))
                .isEqualTo("CARTA DI IDENTITA'");
        assertThat(ufficiali.get(CodiciAlloggiati.codice(TipoDocumento.PASSAPORTO)))
                .isEqualTo("PASSAPORTO ORDINARIO");
        assertThat(ufficiali.get(CodiciAlloggiati.codice(TipoDocumento.PATENTE)))
                .isEqualTo("PATENTE DI GUIDA");
    }

    /**
     * Una tabella ufficiale, letta da {@code src/test/resources/alloggiati/}.
     *
     * <p>Il formato e' quello che il portale scarica: una riga di intestazione e poi
     * {@code codice,descrizione}. Vedi {@code PROVENIENZA.txt} accanto ai file per gli
     * indirizzi da cui si riscaricano.
     */
    private static Map<String, String> tabella(String nome) throws Exception {
        try (var flusso = CodiciAlloggiatiTest.class.getResourceAsStream("/alloggiati/" + nome)) {
            assertThat(flusso).as("la tabella %s deve stare fra le risorse di test", nome).isNotNull();

            Map<String, String> righe = new LinkedHashMap<>();
            for (String riga : new String(flusso.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String[] campi = riga.split(",", 2);
                if (campi.length == 2 && !"Codice".equals(campi[0])) {
                    righe.put(campi[0].trim(), campi[1].trim());
                }
            }
            return righe;
        }
    }

    @Test
    @DisplayName("ogni sesso ha la sua cifra")
    void codice_ogniSesso_esiste() {
        for (Sesso sesso : Sesso.values()) {
            assertThat(CodiciAlloggiati.codice(sesso))
                    .as("cifra del tracciato per %s", sesso)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("due valori diversi non hanno mai lo stesso codice")
    void codice_valoriDiversi_codiciDiversi() {
        // then: e' l'altro modo in cui una mappa scritta a mano si rompe — due righe
        // copiate e una non corretta. Due tipi di alloggiato con lo stesso codice
        // manderebbero alla Questura due persone col ruolo sbagliato, e nessun
        // controllo di esistenza se ne accorgerebbe: il codice duplicato esiste
        assertThat(codiciDistinti(TipoAlloggiato.values(), CodiciAlloggiati::codice))
                .isEqualTo(TipoAlloggiato.values().length);
        // Solo i documenti ammessi: dal 2026-09-03 il permesso di soggiorno non ha un
        // codice, perche' il Ministero non lo accetta — chiederglielo qui solleverebbe
        // l'eccezione che segnala una mappa incompleta, che non e' il caso.
        TipoDocumento[] ammessi = Arrays.stream(TipoDocumento.values())
                .filter(CodiciAlloggiati::ammessoDalMinistero)
                .toArray(TipoDocumento[]::new);
        assertThat(codiciDistinti(ammessi, CodiciAlloggiati::codice))
                .isEqualTo(ammessi.length);
        assertThat(codiciDistinti(Sesso.values(), CodiciAlloggiati::codice))
                .isEqualTo(Sesso.values().length);
    }

    private <T> int codiciDistinti(T[] valori, java.util.function.Function<T, String> codice) {
        Set<String> distinti = new HashSet<>(Arrays.stream(valori).map(codice).toList());
        return distinti.size();
    }
}
