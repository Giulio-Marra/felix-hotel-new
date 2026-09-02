package com.felixhotel.backend.service;

import com.felixhotel.backend.entity.enums.Sesso;
import com.felixhotel.backend.entity.enums.TipoAlloggiato;
import com.felixhotel.backend.entity.enums.TipoDocumento;
import com.felixhotel.backend.service.impl.CodiciAlloggiati;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
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
    @DisplayName("ogni tipo di documento ha il suo codice")
    void codice_ogniTipoDocumento_esiste() {
        for (TipoDocumento tipo : TipoDocumento.values()) {
            assertThat(CodiciAlloggiati.codice(tipo))
                    .as("codice ministeriale di %s", tipo)
                    .isNotBlank();
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
        assertThat(codiciDistinti(TipoDocumento.values(), CodiciAlloggiati::codice))
                .isEqualTo(TipoDocumento.values().length);
        assertThat(codiciDistinti(Sesso.values(), CodiciAlloggiati::codice))
                .isEqualTo(Sesso.values().length);
    }

    private <T> int codiciDistinti(T[] valori, java.util.function.Function<T, String> codice) {
        Set<String> distinti = new HashSet<>(Arrays.stream(valori).map(codice).toList());
        return distinti.size();
    }
}
