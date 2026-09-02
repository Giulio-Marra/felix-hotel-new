package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.entity.enums.Sesso;
import com.felixhotel.backend.entity.enums.TipoAlloggiato;
import com.felixhotel.backend.entity.enums.TipoDocumento;

import java.util.EnumMap;
import java.util.Map;

/**
 * La corrispondenza fra gli elenchi di questa applicazione e i codici che il
 * Ministero pretende sulla schedina.
 *
 * <p><b>Perche' esiste una classe apposta.</b> Il progetto ha due modi di trattare
 * i codici ministeriali e li tiene separati apposta: quelli che il Ministero
 * <i>pubblica</i> stanno in {@code voce_codifica} e li importa l'ADMIN (V12, quarta
 * riga della regola 24); quelli che dicono <i>come i nostri valori si traducono nei
 * suoi</i> non li pubblica nessuno, perche' nessun file ministeriale sa che esiste
 * un enum chiamato {@code CARTA_IDENTITA}. Questa e' la seconda cosa, ed e' logica
 * nostra: la voce lasciata aperta il 2026-09-01 chiudendo
 * {@code feature/codifiche-ministeriali}.
 *
 * <p><b>Le costanti qui dentro sono l'unico punto del branch che non si e' potuto
 * verificare contro una fonte</b>, e va detto invece che nascosto. I codici veri si
 * leggono dalle tabelle che il portale Alloggiati pubblica, e quelle tabelle qui non
 * ci sono — per la stessa decisione che ha lasciato {@code voce_codifica} vuota:
 * scriverle a memoria vorrebbe dire inventare dati che devono essere esatti.
 *
 * <p><b>Cio' che il codice puo' fare, e fa, e' non lasciare che un errore qui
 * diventi silenzioso.</b> Ogni codice che questa classe restituisce viene cercato
 * nella famiglia importata prima di finire nel file: se non c'e', l'export risponde
 * <b>409 dicendo quale codice manca in quale famiglia</b>, invece di produrre una
 * riga che il portale rifiuterebbe due giorni dopo senza spiegare perche'. La rete
 * non rende le costanti giuste — le rende <i>controllabili</i>, che e' la cosa
 * migliore disponibile finche' qualcuno non apre il file del Ministero e le
 * confronta.
 *
 * <p>Non e' istanziabile e non ha stato: sono tre tabelle di traduzione e tre
 * metodi che le leggono.
 */
public final class CodiciAlloggiati {

    /**
     * I codici dei tipi di alloggiato.
     *
     * <p>Le descrizioni corrispondenti nella famiglia {@code TIPO_ALLOGGIATO} sono
     * "Ospite Singolo", "Capofamiglia", "Capogruppo", "Familiare", "Membro Gruppo":
     * l'ordine dell'elenco ministeriale e' quello, e i cinque codici sono
     * consecutivi. E' anche il motivo per cui un errore qui sarebbe <i>uno spostamento
     * di tutti</i> e non un valore sbagliato isolato — cioe' un errore che il
     * controllo di esistenza contro la famiglia importata non prenderebbe, perche'
     * tutti e cinque i codici esisterebbero comunque. E' il caso peggiore di questa
     * classe e vale la pena averlo scritto: quando le tabelle vere saranno importate,
     * questa mappa va confrontata <b>con le descrizioni</b>, non solo con i codici.
     */
    private static final Map<TipoAlloggiato, String> TIPI_ALLOGGIATO =
            new EnumMap<>(Map.of(
                    TipoAlloggiato.OSPITE_SINGOLO, "16",
                    TipoAlloggiato.CAPOFAMIGLIA, "17",
                    TipoAlloggiato.CAPOGRUPPO, "18",
                    TipoAlloggiato.FAMILIARE, "19",
                    TipoAlloggiato.MEMBRO_GRUPPO, "20"));

    /**
     * I codici dei tipi di documento.
     *
     * <p><b>Quattro verso una trentina</b>: il nostro {@link TipoDocumento} elenca i
     * documenti che una reception vede davvero, la famiglia {@code TIPO_DOCUMENTO}
     * del Ministero distingue per esempio fra passaporto ordinario, diplomatico e di
     * servizio. La mappa sceglie ogni volta <b>il caso ordinario</b>, che e' l'unico
     * che il nostro enum sappia esprimere: chi presenta un passaporto diplomatico
     * viene registrato come passaporto e basta.
     *
     * <p><b>La conseguenza va saputa</b>, perche' e' una perdita di informazione e
     * non un arrotondamento: se un giorno servisse distinguerli, non basterebbe
     * cambiare questa mappa — servirebbe che {@link TipoDocumento} avesse i valori
     * per dirlo. Finche' non succede, questa e' la traduzione piu' onesta possibile
     * fra i due elenchi.
     */
    private static final Map<TipoDocumento, String> TIPI_DOCUMENTO =
            new EnumMap<>(Map.of(
                    TipoDocumento.CARTA_IDENTITA, "IDENT",
                    TipoDocumento.PASSAPORTO, "PASOR",
                    TipoDocumento.PATENTE, "PATEN",
                    TipoDocumento.PERMESSO_SOGGIORNO, "PERMS"));

    /**
     * Il sesso come lo vuole il tracciato: una cifra.
     *
     * <p><b>Non passa da nessuna famiglia di codifica</b>, al contrario delle altre
     * due, ed e' il motivo per cui non ha la rete: il Ministero non pubblica un
     * elenco "sesso" da importare, e' un campo con due valori scritti nella
     * specifica del tracciato. Qui l'unico controllo possibile e' quello che c'e' —
     * l'enum ha due valori e la mappa due righe.
     */
    private static final Map<Sesso, String> SESSI =
            new EnumMap<>(Map.of(
                    Sesso.M, "1",
                    Sesso.F, "2"));

    private CodiciAlloggiati() {
        // Classe di sole costanti: nessuno la costruisce.
    }

    /** Il codice ministeriale del tipo di alloggiato. */
    public static String codice(TipoAlloggiato tipoAlloggiato) {
        return richiedi(TIPI_ALLOGGIATO.get(tipoAlloggiato), tipoAlloggiato);
    }

    /** Il codice ministeriale del tipo di documento. */
    public static String codice(TipoDocumento tipoDocumento) {
        return richiedi(TIPI_DOCUMENTO.get(tipoDocumento), tipoDocumento);
    }

    /** La cifra del tracciato per il sesso. */
    public static String codice(Sesso sesso) {
        return richiedi(SESSI.get(sesso), sesso);
    }

    /**
     * Che la traduzione ci sia davvero.
     *
     * <p>Un {@code null} qui vuol dire che qualcuno ha aggiunto un valore a uno dei
     * tre enum senza aggiungerlo alla mappa corrispondente, ed e' l'unico modo in cui
     * queste tre tabelle possono restare indietro. Un {@link IllegalStateException}
     * — cioe' un 500 — e' la risposta giusta: non ha sbagliato chi ha chiamato,
     * manca un pezzo a noi, e la riga alternativa sarebbe una schedina con una
     * casella vuota spedita alla Questura.
     *
     * <p>Non c'e' un test che percorra questo ramo, perche' con gli enum attuali non
     * e' raggiungibile: le tre mappe sono complete, ed e' esattamente cio' che il
     * test {@code CodiciAlloggiatiTest} verifica invece — <b>che siano complete</b>,
     * che e' la stessa garanzia presa dal lato che si puo' provare.
     */
    private static String richiedi(String codice, Enum<?> valore) {
        if (codice == null) {
            throw new IllegalStateException("Manca il codice ministeriale per " + valore.name()
                    + ": va aggiunto in CodiciAlloggiati");
        }
        return codice;
    }
}
