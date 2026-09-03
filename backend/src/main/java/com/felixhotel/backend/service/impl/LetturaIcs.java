package com.felixhotel.backend.service.impl;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.property.DateEnd;
import biweekly.property.DateStart;
import biweekly.property.DurationProperty;
import biweekly.property.Status;
import biweekly.property.Transparency;
import biweekly.property.Uid;
import biweekly.util.Duration;
import biweekly.util.ICalDate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Legge un calendario iCal altrui e ne ricava le occupazioni.
 *
 * <p><b>Il verso opposto di {@link CalendarioIcs}, e con una scelta opposta</b>: quello e'
 * scritto a mano, questo si appoggia a biweekly. Non e' un'incoerenza ma la stessa regola
 * applicata due volte — in scrittura si produce un sottoinsieme deciso da noi, in lettura
 * arriva quel che manda Booking. Righe piegate a settantacinque caratteri, valori
 * protetti, fusi orari, ricorrenze: scriverne un lettore a mano vorrebbe dire riscrivere
 * biweekly peggio, e i bug di un lettore iCal fatto in casa si manifestano come camere
 * rivendute.
 *
 * <p><b>Cosa scarta, e perche' ognuna di queste righe e' importante.</b> Un evento buttato
 * via per sbaglio e' una camera che si rivende; uno tenuto per sbaglio e' una camera che
 * non si vende. Le due cose non si equivalgono — la prima costa molto di piu' — quindi
 * nel dubbio si tiene:
 * <ul>
 *   <li><b>senza data di inizio</b>: non e' un'occupazione, e' una riga rotta;</li>
 *   <li><b>annullato</b> ({@code STATUS:CANCELLED}): il canale sta dicendo esplicitamente
 *       che quella vendita non c'e' piu';</li>
 *   <li><b>trasparente</b> ({@code TRANSP:TRANSPARENT}): in iCal vuol dire "questo tempo
 *       resta libero", ed e' cosi' che alcuni canali pubblicano i promemoria;</li>
 *   <li><b>di zero notti</b>: un periodo che finisce prima o quando comincia non rende
 *       invendibile niente.</li>
 * </ul>
 * Tutto il resto entra, comprese le righe senza titolo e senza descrizione — che sono la
 * norma, perche' iCal porta solo le date.
 *
 * <p><b>Solo date, mai istanti.</b> Un soggiorno occupa <i>notti</i>, quindi di ogni
 * estremo si prende la data <i>come e' scritta</i> nel file e non l'istante che
 * rappresenta. La differenza si vede quando un canale manda un orario invece di una data:
 * {@code 20260903T140000Z} diventa il 3 settembre in tutti e due i modi, ma
 * {@code 20260903T230000Z} letto come istante diventerebbe il 4 in mezza Europa, cioe' una
 * notte occupata che si sposta a seconda di dove gira il backend. Per i calendari di
 * alloggio la forma normale e' comunque {@code VALUE=DATE}, senza orario.
 *
 * <p>Classe pura: nessuna entita', nessun database, nessuna rete. E' cio' che permette di
 * provarla su file veri — vedi {@code LetturaIcsTest}.
 */
public final class LetturaIcs {

    private LetturaIcs() {
        // Solo metodi statici: e' un formato, non un oggetto.
    }

    /**
     * Le occupazioni contenute in un calendario.
     *
     * @param testo il file iCal come arrivato dal canale
     * @return i periodi occupati, nell'ordine in cui compaiono nel file
     * @throws IcsIlleggibileException se non e' un calendario
     */
    public static List<OccupazioneEsterna> occupazioni(String testo) {
        List<OccupazioneEsterna> occupazioni = new ArrayList<>();

        for (ICalendar calendario : leggi(testo)) {
            for (VEvent evento : calendario.getEvents()) {
                aggiungiSeOccupa(occupazioni, evento);
            }
        }
        return occupazioni;
    }

    /**
     * <b>Tutti i calendari del file e non solo il primo.</b> Un file iCal puo' contenere
     * piu' di un {@code VCALENDAR}, ed e' raro ma non impossibile: fermarsi al primo
     * vorrebbe dire perdere in silenzio le occupazioni degli altri, che e' il modo in cui
     * si rivende una camera senza nemmeno un errore nei log.
     */
    private static List<ICalendar> leggi(String testo) {
        List<ICalendar> calendari = Biweekly.parse(testo).all();

        // biweekly non solleva niente davanti a un testo che non e' un calendario: ne
        // restituisce zero. Senza questo controllo, una pagina di errore HTML del canale
        // verrebbe letta come "nessuna occupazione" e libererebbe tutte le camere.
        if (calendari.isEmpty()) {
            throw new IcsIlleggibileException(
                    "La risposta non contiene nessun calendario: l'indirizzo non punta a un file iCal");
        }
        return calendari;
    }

    private static void aggiungiSeOccupa(List<OccupazioneEsterna> occupazioni, VEvent evento) {
        if (annullato(evento) || trasparente(evento)) {
            return;
        }

        DateStart inizioProprieta = evento.getDateStart();
        if (inizioProprieta == null || inizioProprieta.getValue() == null) {
            return;
        }

        LocalDate inizio = aData(inizioProprieta.getValue());
        LocalDate fine = fine(evento, inizio);

        if (!fine.isAfter(inizio)) {
            return;
        }
        occupazioni.add(new OccupazioneEsterna(uid(evento), inizio, fine));
    }

    /**
     * Quando la camera torna libera. <b>Esclusa</b>, come la partenza di una prenotazione
     * e come vuole iCal.
     *
     * <p>Tre casi in ordine di attendibilita', e il terzo e' quello che vale la pena
     * spiegare. Se manca {@code DTEND}, la specifica dice che un evento di sole date dura
     * <b>un giorno</b>: e' l'unica interpretazione ammessa, non un ripiego. Il caso di
     * mezzo — la {@code DURATION} — non lo usa nessun canale di alloggio, e si legge lo
     * stesso perche' l'alternativa sarebbe scartare in silenzio un'occupazione vera.
     */
    private static LocalDate fine(VEvent evento, LocalDate inizio) {
        DateEnd fineProprieta = evento.getDateEnd();
        if (fineProprieta != null && fineProprieta.getValue() != null) {
            return aData(fineProprieta.getValue());
        }

        DurationProperty durata = evento.getDuration();
        if (durata != null && durata.getValue() != null) {
            return inizio.plusDays(notti(durata.getValue()));
        }
        return inizio.plusDays(1);
    }

    /**
     * Quante notti dura una {@code DURATION}.
     *
     * <p>Si contano <b>solo settimane e giorni</b>: ore e minuti descrivono un
     * appuntamento, non un soggiorno, e sommarli darebbe frazioni di notte che questa
     * tabella non sa rappresentare. Il minimo e' una notte, perche' una durata piu' corta
     * di un giorno resta comunque una camera che quella notte non e' vendibile.
     */
    private static long notti(Duration durata) {
        long giorni = valore(durata.getWeeks()) * 7L + valore(durata.getDays());
        return Math.max(giorni, 1L);
    }

    private static long valore(Integer numero) {
        return numero == null ? 0L : numero;
    }

    /**
     * La data di un estremo, <b>come e' scritta nel file</b>.
     *
     * <p>{@code getRawComponents()} restituisce i campi cosi' come sono stati letti, ed e'
     * esattamente quel che serve: convertire l'{@code ICalDate} in istante e poi in data
     * lo farebbe passare per un fuso orario, e una notte occupata cambierebbe giorno a
     * seconda della macchina. Vale null solo per gli {@code ICalDate} costruiti a mano e
     * non letti da un file — non e' il nostro caso, e il ripiego c'e' per non far dipendere
     * la correttezza da un dettaglio di una libreria.
     */
    private static LocalDate aData(ICalDate valore) {
        var grezzo = valore.getRawComponents();
        if (grezzo != null) {
            return LocalDate.of(grezzo.getYear(), grezzo.getMonth(), grezzo.getDate());
        }
        return valore.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static boolean annullato(VEvent evento) {
        Status stato = evento.getStatus();
        return stato != null && stato.isCancelled();
    }

    private static boolean trasparente(VEvent evento) {
        Transparency trasparenza = evento.getTransparency();
        return trasparenza != null && trasparenza.isTransparent();
    }

    /**
     * L'identificativo dell'evento, o null se il calendario non ne da' uno.
     *
     * <p>Un UID mancante non e' un motivo per scartare l'occupazione: nessuna decisione
     * dipende da questo valore — la sincronizzazione riscrive i propri blocchi da capo ad
     * ogni giro — e serve solo a ritrovare, davanti a un blocco che non si spiega, la riga
     * del calendario che lo ha prodotto.
     */
    private static String uid(VEvent evento) {
        Uid uid = evento.getUid();
        return uid == null ? null : uid.getValue();
    }

    /**
     * Un periodo occupato su un calendario esterno.
     *
     * @param uid    l'identificativo che il canale gli da', o null se non ce l'ha
     * @param inizio primo giorno occupato
     * @param fine   giorno in cui la camera torna libera, <b>escluso</b>
     */
    public record OccupazioneEsterna(String uid, LocalDate inizio, LocalDate fine) {
    }

    /**
     * Quel che e' arrivato dall'indirizzo non e' un calendario.
     *
     * <p>Non estende {@code AppException}: non e' un errore da tradurre in una risposta
     * HTTP, perche' nessuno sta aspettando questa lettura. Diventa un esito
     * {@code ERRORE} sulla riga della sorgente, che e' dove qualcuno andra' a guardarlo.
     */
    public static class IcsIlleggibileException extends RuntimeException {

        public IcsIlleggibileException(String messaggio) {
            super(messaggio);
        }
    }
}
