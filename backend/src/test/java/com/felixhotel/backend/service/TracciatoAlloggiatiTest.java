package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.RigaSchedina;
import com.felixhotel.backend.service.impl.TracciatoAlloggiati;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Il formato del file che il portale Alloggiati Web accetta.
 *
 * <p><b>E' il test piu' fitto del branch, e non e' sproporzionato.</b> Un tracciato
 * a posizioni fisse e' fatto di numeri che si sbagliano in silenzio: un campo lungo
 * un carattere di troppo sposta tutti quelli che seguono, e il risultato non e' un
 * errore ma un file che il portale rifiuta due giorni dopo senza dire dove. Non c'e'
 * nessun altro modo di accorgersene — la suite non ha un portale contro cui provare.
 *
 * <p><b>Le posizioni sono ricopiate qui a mano, ed e' voluto.</b> Verrebbe spontaneo
 * riusare le costanti di {@code TracciatoAlloggiati}, e sarebbe un test che non prova
 * niente: se un giorno una lunghezza li' dentro cambiasse per errore, il test
 * cambierebbe insieme a lei e resterebbe verde. Un tracciato si verifica contro una
 * <b>seconda scrittura indipendente</b> delle stesse posizioni, altrimenti si sta
 * solo confrontando un numero con se stesso.
 *
 * <p>Unitario e non IT: non serve ne' Spring ne' Postgres, si contano caratteri.
 */
@DisplayName("TracciatoAlloggiati")
class TracciatoAlloggiatiTest {

    // Le posizioni dei quattordici campi, scritte di nuovo a partire dalla specifica
    // del tracciato e non lette dalla classe sotto test. Sono coppie (inizio, fine)
    // con l'indice di fine escluso, come li vuole substring.
    private static final int TIPO_ALLOGGIATO_DA = 0;
    private static final int TIPO_ALLOGGIATO_A = 2;
    private static final int DATA_ARRIVO_DA = 2;
    private static final int DATA_ARRIVO_A = 12;
    private static final int PERMANENZA_DA = 12;
    private static final int PERMANENZA_A = 14;
    private static final int COGNOME_DA = 14;
    private static final int COGNOME_A = 64;
    private static final int NOME_DA = 64;
    private static final int NOME_A = 94;
    private static final int SESSO_DA = 94;
    private static final int SESSO_A = 95;
    private static final int DATA_NASCITA_DA = 95;
    private static final int DATA_NASCITA_A = 105;
    private static final int COMUNE_DA = 105;
    private static final int COMUNE_A = 114;
    private static final int PROVINCIA_DA = 114;
    private static final int PROVINCIA_A = 116;
    private static final int STATO_DA = 116;
    private static final int STATO_A = 125;
    private static final int CITTADINANZA_DA = 125;
    private static final int CITTADINANZA_A = 134;
    private static final int TIPO_DOCUMENTO_DA = 134;
    private static final int TIPO_DOCUMENTO_A = 139;
    private static final int NUMERO_DOCUMENTO_DA = 139;
    private static final int NUMERO_DOCUMENTO_A = 159;
    private static final int LUOGO_RILASCIO_DA = 159;
    private static final int LUOGO_RILASCIO_A = 168;

    private static final LocalDate ARRIVO = LocalDate.of(2026, 9, 1);
    private static final LocalDate NASCITA = LocalDate.of(1985, 4, 17);

    /**
     * Un capofamiglia con tutto compilato: e' la riga piu' piena che il tracciato
     * possa avere, quindi quella che mette alla prova tutte e quattordici le caselle.
     */
    private static RigaSchedina completa() {
        return new RigaSchedina("17", ARRIVO, 3, "ROSSI", "MARIO", "1", NASCITA,
                "058091", "RM", null, "100000100", "IDENT", "CA12345AB", "058091");
    }

    @Nested
    @DisplayName("posizioni dei campi")
    class Posizioni {

        @Test
        @DisplayName("una riga e' lunga esattamente 168 caratteri")
        void formatta_rigaCompleta_lunga168() {
            // when
            String riga = TracciatoAlloggiati.formatta(completa());

            // then: e' il numero da cui dipende tutto il resto. Se questo cambia, il
            // portale rifiuta il file intero e non la singola riga
            assertThat(riga).hasSize(168);
        }

        @Test
        @DisplayName("ogni campo sta nella sua casella")
        void formatta_rigaCompleta_campiNellePosizioniGiuste() {
            // when
            String riga = TracciatoAlloggiati.formatta(completa());

            // then: si guarda casella per casella, con le posizioni riscritte in questa
            // classe. E' l'unico controllo che si accorga di un campo spostato di uno
            assertThat(riga.substring(TIPO_ALLOGGIATO_DA, TIPO_ALLOGGIATO_A)).isEqualTo("17");
            assertThat(riga.substring(DATA_ARRIVO_DA, DATA_ARRIVO_A)).isEqualTo("01/09/2026");
            assertThat(riga.substring(PERMANENZA_DA, PERMANENZA_A)).isEqualTo("03");
            assertThat(riga.substring(COGNOME_DA, COGNOME_A)).isEqualTo(riempi("ROSSI", 50));
            assertThat(riga.substring(NOME_DA, NOME_A)).isEqualTo(riempi("MARIO", 30));
            assertThat(riga.substring(SESSO_DA, SESSO_A)).isEqualTo("1");
            assertThat(riga.substring(DATA_NASCITA_DA, DATA_NASCITA_A)).isEqualTo("17/04/1985");
            assertThat(riga.substring(COMUNE_DA, COMUNE_A)).isEqualTo(riempi("058091", 9));
            assertThat(riga.substring(PROVINCIA_DA, PROVINCIA_A)).isEqualTo("RM");
            assertThat(riga.substring(STATO_DA, STATO_A)).isEqualTo(riempi("", 9));
            assertThat(riga.substring(CITTADINANZA_DA, CITTADINANZA_A)).isEqualTo(riempi("100000100", 9));
            assertThat(riga.substring(TIPO_DOCUMENTO_DA, TIPO_DOCUMENTO_A)).isEqualTo("IDENT");
            assertThat(riga.substring(NUMERO_DOCUMENTO_DA, NUMERO_DOCUMENTO_A))
                    .isEqualTo(riempi("CA12345AB", 20));
            assertThat(riga.substring(LUOGO_RILASCIO_DA, LUOGO_RILASCIO_A)).isEqualTo(riempi("058091", 9));
        }

        @Test
        @DisplayName("chi e' nato all'estero ha lo stato pieno e comune e provincia vuoti")
        void formatta_natoAllEstero_comuneVuotoStatoPieno() {
            // given: le due caselle sono alternative, e questo e' il verso che l'altro
            // test non copre — insieme provano che l'esclusione funziona da tutti e due
            // i lati invece che solo da uno
            RigaSchedina riga = new RigaSchedina("16", ARRIVO, 2, "SCHMIDT", "HANS", "1", NASCITA,
                    null, null, "100000215", "100000215", "PASOR", "P1234567", "100000215");

            // when
            String testo = TracciatoAlloggiati.formatta(riga);

            // then
            assertThat(testo.substring(COMUNE_DA, COMUNE_A)).isEqualTo(riempi("", 9));
            assertThat(testo.substring(PROVINCIA_DA, PROVINCIA_A)).isEqualTo("  ");
            assertThat(testo.substring(STATO_DA, STATO_A)).isEqualTo(riempi("100000215", 9));
            assertThat(testo).hasSize(168);
        }

        @Test
        @DisplayName("chi e' accompagnato ha le tre caselle del documento in bianco")
        void formatta_senzaDocumento_caselleDocumentoVuote() {
            // given: un familiare. Il documento l'ha esibito chi lo accompagna, e il
            // tracciato quelle caselle le vuole vuote — non "assenti": la riga resta
            // lunga uguale
            RigaSchedina riga = new RigaSchedina("19", ARRIVO, 3, "ROSSI", "LUCA", "1",
                    LocalDate.of(2016, 5, 2), "058091", "RM", null, "100000100", null, null, null);

            // when
            String testo = TracciatoAlloggiati.formatta(riga);

            // then
            assertThat(testo.substring(TIPO_DOCUMENTO_DA, TIPO_DOCUMENTO_A)).isEqualTo("     ");
            assertThat(testo.substring(NUMERO_DOCUMENTO_DA, NUMERO_DOCUMENTO_A)).isEqualTo(riempi("", 20));
            assertThat(testo.substring(LUOGO_RILASCIO_DA, LUOGO_RILASCIO_A)).isEqualTo(riempi("", 9));
            assertThat(testo).hasSize(168);
        }
    }

    @Nested
    @DisplayName("testo dei campi")
    class Testo {

        @Test
        @DisplayName("gli accenti si traslitterano invece di far saltare la riga")
        void formatta_conAccenti_traslittera() {
            // given: un cognome e un nome che il tracciato non accetterebbe come sono
            RigaSchedina riga = completaCon("D'ANGELO", "Niccolò");

            // when
            String testo = TracciatoAlloggiati.formatta(riga);

            // then: "NICCOLO" e non una casella di punti interrogativi. Una schedina con
            // un accento perso identifica la persona, una schedina scartata no —
            // l'apostrofo invece resta, perche' e' ASCII e nei cognomi ci sta
            assertThat(testo.substring(NOME_DA, NOME_A)).isEqualTo(riempi("NICCOLO", 30));
            assertThat(testo.substring(COGNOME_DA, COGNOME_A)).isEqualTo(riempi("D'ANGELO", 50));
        }

        @Test
        @DisplayName("il minuscolo diventa maiuscolo")
        void formatta_minuscolo_diventaMaiuscolo() {
            // when
            String testo = TracciatoAlloggiati.formatta(completaCon("rossi", "mario"));

            // then
            assertThat(testo.substring(COGNOME_DA, COGNOME_A)).isEqualTo(riempi("ROSSI", 50));
            assertThat(testo.substring(NOME_DA, NOME_A)).isEqualTo(riempi("MARIO", 30));
        }

        @Test
        @DisplayName("cio' che nemmeno la traslitterazione sa rendere diventa spazio")
        void formatta_alfabetoNonLatino_diventaSpazi() {
            // given: il cirillico non si riduce a lettere latine scomponendolo
            RigaSchedina riga = completaCon("ИВАНОВ", "MARIO");

            // when
            String testo = TracciatoAlloggiati.formatta(riga);

            // then: sei spazi al posto di sei lettere, cioe' la casella resta lunga
            // uguale. **E' il limite dichiarato del metodo**, non un successo: quella
            // schedina il portale la accetta e non identifica nessuno. La risposta
            // giusta e' che al banco si digiti il nome come sta stampato sul documento,
            // dove l'autorita' che l'ha emesso lo ha gia' traslitterato
            assertThat(testo.substring(COGNOME_DA, COGNOME_A)).isEqualTo(riempi("", 50));
            assertThat(testo).hasSize(168);
        }

        @Test
        @DisplayName("un cognome piu' lungo della casella viene tagliato, non rifiutato")
        void formatta_cognomeTroppoLungo_taglia() {
            // given: cinquantacinque caratteri contro i cinquanta della casella. La
            // colonna del database ne accetta cento, quindi il caso e' raggiungibile
            String lunghissimo = "A".repeat(55);

            // when
            String testo = TracciatoAlloggiati.formatta(completaCon(lunghissimo, "MARIO"));

            // then: fra una schedina col cognome accorciato e nessuna schedina, la prima
            // e' la meno peggio — la persona resta registrata e riconoscibile
            assertThat(testo.substring(COGNOME_DA, COGNOME_A)).isEqualTo("A".repeat(50));
            assertThat(testo).hasSize(168);
        }
    }

    @Nested
    @DisplayName("permanenza")
    class Permanenza {

        @Test
        @DisplayName("una notte sola si scrive con lo zero davanti")
        void formatta_unaNotte_conZeroDavanti() {
            // when
            String testo = TracciatoAlloggiati.formatta(
                    new RigaSchedina("16", ARRIVO, 1, "ROSSI", "MARIO", "1", NASCITA,
                            "058091", "RM", null, "100000100", "IDENT", "CA1", "058091"));

            // then: "01" e non "1 ". E' l'unico campo del tracciato che si allinea a
            // destra, ed e' il motivo per cui non passa dal riempimento degli altri
            assertThat(testo.substring(PERMANENZA_DA, PERMANENZA_A)).isEqualTo("01");
        }

        @Test
        @DisplayName("trenta notti ci stanno: e' il massimo che il Ministero accetti")
        void formatta_trentaNotti_ciSta() {
            // when
            String testo = TracciatoAlloggiati.formatta(
                    new RigaSchedina("16", ARRIVO, 30, "ROSSI", "MARIO", "1", NASCITA,
                            "058091", "RM", null, "100000100", "IDENT", "CA1", "058091"));

            // then: 30 e non 99. Fino al 2026-09-03 questo test diceva 90, perche' il
            // limite era stato letto sulla larghezza del campo invece che sulla sua
            // regola — la tabella ufficiale, accanto a "Numero Giorni di Permanenza",
            // dice "Massimo 30 gg"
            assertThat(testo.substring(PERMANENZA_DA, PERMANENZA_A)).isEqualTo("30");
        }

        @Test
        @DisplayName("oltre i trenta si ferma invece di scrivere una riga che verra' rifiutata")
        void formatta_permanenzaFuoriScala_sollevaIllegalState() {
            // given: 31, cioe' il primo giorno che il Ministero non accetta. **E'
            // raggiungibile**, e questa e' la differenza col caso di prima: il progetto
            // vende soggiorni fino a 90 notti, quindi fra 31 e 90 c'e' un intervallo di
            // prenotazioni legittime che qui non passano
            RigaSchedina riga = new RigaSchedina("16", ARRIVO, 31, "ROSSI", "MARIO", "1",
                    NASCITA, "058091", "RM", null, "100000100", "IDENT", "CA1", "058091");

            // when/then: IllegalStateException e non una riga formalmente giusta che il
            // portale rifiuterebbe due giorni dopo senza dire perche'. Chi arriva fin qui
            // ha aggirato il 409 del Service, che e' il posto in cui il caso si spiega:
            // questa e' la rete sotto, e infatti parla di un difetto nostro
            assertThatThrownBy(() -> TracciatoAlloggiati.formatta(riga))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("31");
        }
    }

    @Test
    @DisplayName("il terminatore di riga e' CRLF e non dipende dal sistema operativo")
    void fineRiga_eCrlf() {
        // then: scritto a mano invece che con System.lineSeparator(), altrimenti il file
        // prodotto su Linux e quello prodotto su Windows sarebbero diversi — cioe' uno
        // dei due verrebbe rifiutato, e non si saprebbe quale finche' non succede
        assertThat(TracciatoAlloggiati.FINE_RIGA).isEqualTo("\r\n");
    }

    /** La riga di prova con cognome e nome sostituiti, che e' l'unica cosa che varia. */
    private static RigaSchedina completaCon(String cognome, String nome) {
        RigaSchedina base = completa();
        return new RigaSchedina(base.codiceTipoAlloggiato(), base.dataArrivo(),
                base.giorniPermanenza(), cognome, nome, base.codiceSesso(), base.dataNascita(),
                base.comuneNascita(), base.provinciaNascita(), base.statoNascita(),
                base.cittadinanza(), base.codiceTipoDocumento(), base.numeroDocumento(),
                base.luogoRilascioDocumento());
    }

    /** Il valore seguito dagli spazi che riempiono la casella, come lo scrive il tracciato. */
    private static String riempi(String valore, int lunghezza) {
        return valore + " ".repeat(lunghezza - valore.length());
    }
}
