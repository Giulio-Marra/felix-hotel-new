package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.Periodo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Il formato iCalendar (RFC 5545) nella sola forma che serve qui: un calendario di
 * periodi occupati.
 *
 * <p><b>E' scritto a mano e non con una libreria</b>, al contrario di {@link LetturaIcs},
 * che i calendari altrui li legge con biweekly. La differenza e' tutta qui: in scrittura
 * si produce un sottoinsieme che decidiamo noi — eventi di sole date, senza fusi orari,
 * senza ricorrenze, senza allegati — e sono trenta righe che si provano carattere per
 * carattere. In lettura arriva quel che manda Booking, con le righe spezzate a
 * settantacinque caratteri, le virgole protette e i fusi orari, e li' scriverne uno a
 * mano vorrebbe dire riscrivere una libreria peggio.
 *
 * <p><b>Solo date, mai orari.</b> Un soggiorno in albergo occupa <i>notti</i>, e
 * {@code VALUE=DATE} e' esattamente questo: nessun fuso orario da sbagliare, e
 * {@code DTEND} escluso come la data di partenza di una prenotazione — che e' anche la
 * convenzione di tutto il progetto. Con gli orari avremmo dovuto scegliere un fuso e
 * ogni consumatore avrebbe potuto interpretarlo a modo suo.
 *
 * <p><b>Le righe finiscono con CRLF</b>, come vuole la specifica e per la stessa ragione
 * per cui lo vuole il tracciato delle schedine: scriverlo come separatore di sistema
 * darebbe un file diverso a seconda di dove gira il backend.
 */
public final class CalendarioIcs {

    /**
     * La parte finale degli UID che scriviamo noi.
     *
     * <p><b>Non e' decorativa: e' come ci si riconosce.</b> Diversi canali ripubblicano
     * nel proprio calendario in uscita anche le occupazioni che hanno letto dal nostro, e
     * la specifica pretende che in quel giro l'UID resti lo stesso. Senza un modo di dire
     * "questo evento l'ho scritto io", {@link LetturaIcs} lo importerebbe come
     * occupazione nuova: una nostra prenotazione tornerebbe indietro come blocco, la
     * camera risulterebbe occupata due volte e ogni giro segnalerebbe un overbooking che
     * non esiste. Vedi il filtro in {@code SincronizzatoreSorgente}.
     */
    public static final String DOMINIO_UID = "@felix-hotel";

    /** Le date in iCal: otto cifre, senza separatori. */
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String FINE_RIGA = "\r\n";

    /**
     * Chi ha prodotto il file. Non serve a nessun consumatore, la specifica lo pretende
     * e basta — e chi legge i log di Booking almeno sa da dove arriva.
     */
    private static final String PRODID = "-//Felix Hotel//Calendario camere//IT";

    private CalendarioIcs() {
        // Solo metodi statici: e' un formato, non un oggetto.
    }

    /**
     * Il calendario di una camera.
     *
     * @param nomeCamera compare come titolo degli eventi: serve solo a chi apre il file
     *                   con gli occhi, perche' i canali guardano le date
     * @param periodi    in ordine, gia' compattati
     * @param generatoIl la data di generazione, che finisce negli identificativi degli
     *                   eventi
     */
    public static String calendario(String nomeCamera, List<Periodo> periodi, LocalDate generatoIl) {
        StringBuilder ics = new StringBuilder();

        ics.append("BEGIN:VCALENDAR").append(FINE_RIGA);
        ics.append("VERSION:2.0").append(FINE_RIGA);
        ics.append("PRODID:").append(PRODID).append(FINE_RIGA);
        // Senza questo, alcuni consumatori interpretano un calendario di sole date come
        // una serie di appuntamenti e li mostrano all'ora sbagliata.
        ics.append("CALSCALE:GREGORIAN").append(FINE_RIGA);
        ics.append("METHOD:PUBLISH").append(FINE_RIGA);

        int progressivo = 0;
        for (Periodo periodo : periodi) {
            progressivo++;
            ics.append("BEGIN:VEVENT").append(FINE_RIGA);
            // L'identificativo deve essere stabile fra due generazioni dello stesso
            // calendario, altrimenti ogni lettura sembra un evento nuovo: si costruisce
            // dalle date e non dal momento in cui si genera.
            ics.append("UID:").append(periodo.inizio().format(DATA))
                    .append('-').append(periodo.fine().format(DATA))
                    .append('-').append(progressivo)
                    .append(DOMINIO_UID).append(FINE_RIGA);
            ics.append("DTSTAMP:").append(generatoIl.format(DATA)).append("T000000Z").append(FINE_RIGA);
            ics.append("DTSTART;VALUE=DATE:").append(periodo.inizio().format(DATA)).append(FINE_RIGA);
            ics.append("DTEND;VALUE=DATE:").append(periodo.fine().format(DATA)).append(FINE_RIGA);
            ics.append("SUMMARY:").append(sfuggi(nomeCamera)).append(FINE_RIGA);
            // OPAQUE vuol dire "questo tempo e' occupato": e' il campo su cui un canale
            // decide se la camera sia vendibile, e ometterlo lascerebbe la decisione ai
            // valori predefiniti di chi legge.
            ics.append("TRANSP:OPAQUE").append(FINE_RIGA);
            ics.append("END:VEVENT").append(FINE_RIGA);
        }

        ics.append("END:VCALENDAR").append(FINE_RIGA);
        return ics.toString();
    }

    /**
     * Protegge i caratteri che in iCal hanno un significato.
     *
     * <p>Riguarda solo il titolo, che e' l'unico testo libero del file: virgole, punti e
     * virgola e barre rovesciate vanno preceduti da una barra, e un a capo dentro un
     * valore spezzerebbe il file in due. Il numero di una camera non li contiene quasi
     * mai — ma "quasi mai" e' il motivo per cui questo metodo esiste: un numero come
     * {@code 12; suite} e' improbabile e non impossibile, e senza questa riga
     * produrrebbe un calendario che nessuno riesce piu' a leggere.
     */
    private static String sfuggi(String valore) {
        return valore.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }
}
