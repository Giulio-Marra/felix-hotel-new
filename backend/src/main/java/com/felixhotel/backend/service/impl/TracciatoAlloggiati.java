package com.felixhotel.backend.service.impl;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Il formato del file che il portale Alloggiati Web accetta.
 *
 * <p><b>Una riga per persona, 168 caratteri, niente separatori.</b> E' un tracciato
 * a posizioni fisse: ogni campo occupa un numero di caratteri deciso, allineato a
 * sinistra e riempito di spazi se il valore e' piu' corto. Non c'e' nessun modo di
 * "sbagliare di poco" — un carattere in piu' sposta tutto quel che segue, e il
 * portale rifiuta il file senza dire dove.
 *
 * <p><b>Perche' e' una classe sua e non un metodo del Service.</b> Qui non c'e'
 * nessuna decisione di dominio: si contano caratteri. Tenerlo separato e' cio' che
 * permette di provarlo per intero con un unitario — nessuna entita', nessun
 * database, nessuna codifica da importare — e questo e' il pezzo del branch che di
 * test ne vuole di piu', perche' e' fatto di numeri che si sbagliano in silenzio.
 * E' la stessa divisione gia' fatta fra {@code DurataSoggiorno} e chi la usa.
 *
 * <p><b>Le posizioni sono scritte come lunghezze e non come indici</b>, in
 * quest'ordine e sommate da {@link #LUNGHEZZA_RIGA}: cosi' aggiungere o correggere
 * un campo non obbliga a ricalcolare a mano tutti gli offset successivi, che e'
 * esattamente il modo in cui un tracciato si rompe quando lo si tocca.
 *
 * <p><b>Solo ASCII maiuscolo.</b> Il tracciato non accetta lettere accentate, e la
 * strada scelta e' <b>traslitterare</b> invece di rifiutare: un ospite che si chiama
 * Niccolo' esiste, e una schedina con "NICCOLO" vale infinitamente piu' di una
 * schedina scartata. Vedi {@link #ripulisci(String)} per cosa succede a quel che
 * nemmeno la traslitterazione sa rendere.
 */
public final class TracciatoAlloggiati {

    /**
     * Lunghezza di una riga, controllata a ogni chiamata da {@link #formatta}.
     *
     * <p><b>Privata di proposito</b>: il test del tracciato riscrive 168 a mano invece
     * di importarla, e deve continuare a farlo — un test che confronta una costante con
     * se stessa resta verde anche quando quella costante e' sbagliata.
     */
    private static final int LUNGHEZZA_RIGA = 168;

    /**
     * Il terminatore di riga, CRLF e non solo LF.
     *
     * <p><b>Non e' un dettaglio di piattaforma</b>: e' quel che il portale si
     * aspetta, e scriverlo come {@code System.lineSeparator()} vorrebbe dire un file
     * diverso a seconda del sistema operativo su cui gira il backend — cioe' un file
     * che passa in sviluppo e viene rifiutato in produzione, o il contrario.
     */
    public static final String FINE_RIGA = "\r\n";

    /** Le date del tracciato: giorno, mese e anno separati da barre. */
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Le lunghezze dei quattordici campi, nell'ordine in cui compaiono sulla riga.
    private static final int LUNGHEZZA_TIPO_ALLOGGIATO = 2;
    private static final int LUNGHEZZA_DATA = 10;
    private static final int LUNGHEZZA_PERMANENZA = 2;
    private static final int LUNGHEZZA_COGNOME = 50;
    private static final int LUNGHEZZA_NOME = 30;
    private static final int LUNGHEZZA_SESSO = 1;
    private static final int LUNGHEZZA_COMUNE = 9;
    private static final int LUNGHEZZA_PROVINCIA = 2;
    private static final int LUNGHEZZA_STATO = 9;
    private static final int LUNGHEZZA_CITTADINANZA = 9;
    private static final int LUNGHEZZA_TIPO_DOCUMENTO = 5;
    private static final int LUNGHEZZA_NUMERO_DOCUMENTO = 20;
    private static final int LUNGHEZZA_LUOGO_RILASCIO = 9;

    /**
     * Il massimo che le due cifre della permanenza sanno scrivere.
     *
     * <p>Non e' un limite che questa classe imponga: un soggiorno non puo' superare
     * le 90 notti (deciso il 2026-09-01, applicato da ricerca e creazione), quindi il
     * caso non e' raggiungibile da nessuna prenotazione valida. Sta qui perche' se un
     * giorno quel tetto salisse sopra 99, e' questo il posto che si rompe — e meglio
     * che si rompa dicendolo.
     */
    private static final int PERMANENZA_MASSIMA = 99;

    /**
     * I caratteri che il tracciato accetta dopo la traslitterazione: lettere,
     * cifre, spazio e i pochi segni che compaiono davvero nei nomi propri.
     *
     * <p>L'apostrofo c'e' perche' "D'ANGELO" e' un cognome comune; il trattino
     * perche' i doppi cognomi lo usano; il punto perche' le abbreviazioni sui
     * documenti lo hanno. Tutto il resto diventa uno spazio.
     */
    private static final String CARATTERI_AMMESSI = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 '-.";

    private TracciatoAlloggiati() {
        // Solo metodi statici: e' un formato, non un oggetto.
    }

    /**
     * Scrive una riga del tracciato.
     *
     * <p>L'ordine dei campi <b>e'</b> il formato: non c'e' nessun'altra fonte che lo
     * dica, quindi si legge da qui. Un {@code null} diventa una casella di spazi, che
     * e' quel che il tracciato vuole per i campi che una persona accompagnata non
     * compila.
     *
     * <p>Il controllo finale sulla lunghezza non e' una cintura di sicurezza generica:
     * e' l'unica cosa che, sommando quattordici numeri scritti a mano, si accorge di
     * un errore di somma. Se scatta, il difetto e' qui dentro e non nei dati — quindi
     * {@link IllegalStateException} e non un errore di richiesta.
     */
    public static String formatta(RigaSchedina riga) {
        StringBuilder sb = new StringBuilder(LUNGHEZZA_RIGA);

        sb.append(campo(riga.codiceTipoAlloggiato(), LUNGHEZZA_TIPO_ALLOGGIATO));
        sb.append(campo(data(riga.dataArrivo()), LUNGHEZZA_DATA));
        sb.append(permanenza(riga.giorniPermanenza()));
        sb.append(testo(riga.cognome(), LUNGHEZZA_COGNOME));
        sb.append(testo(riga.nome(), LUNGHEZZA_NOME));
        sb.append(campo(riga.codiceSesso(), LUNGHEZZA_SESSO));
        sb.append(campo(data(riga.dataNascita()), LUNGHEZZA_DATA));
        sb.append(campo(riga.comuneNascita(), LUNGHEZZA_COMUNE));
        sb.append(campo(riga.provinciaNascita(), LUNGHEZZA_PROVINCIA));
        sb.append(campo(riga.statoNascita(), LUNGHEZZA_STATO));
        sb.append(campo(riga.cittadinanza(), LUNGHEZZA_CITTADINANZA));
        sb.append(campo(riga.codiceTipoDocumento(), LUNGHEZZA_TIPO_DOCUMENTO));
        sb.append(campo(riga.numeroDocumento(), LUNGHEZZA_NUMERO_DOCUMENTO));
        sb.append(campo(riga.luogoRilascioDocumento(), LUNGHEZZA_LUOGO_RILASCIO));

        String risultato = sb.toString();
        if (risultato.length() != LUNGHEZZA_RIGA) {
            throw new IllegalStateException("Riga del tracciato lunga " + risultato.length()
                    + " invece di " + LUNGHEZZA_RIGA + ": le lunghezze dei campi non tornano");
        }
        return risultato;
    }

    /**
     * Un campo: tagliato alla lunghezza esatta e riempito di spazi a destra.
     *
     * <p><b>Non ripulisce niente</b>, ed e' la divisione che conta con
     * {@link #testo(String, int)}: qui passano date gia' formattate e codici emessi da
     * un'autorita', cioe' valori che sono <i>gia'</i> esatti. Sottoporli alla
     * traslitterazione non li renderebbe piu' sicuri, li romperebbe — le barre di
     * "01/09/2026" non sono nell'alfabeto ammesso di un nome proprio, e diventerebbero
     * spazi. E' il difetto che il primo giro di test ha trovato, e la lezione e' quella
     * generale: <b>la ripulitura serve a cio' che ha digitato una persona</b>, non a
     * cio' che il codice ha appena costruito.
     *
     * <p><b>Taglia invece di rifiutare</b>, ed e' una scelta e non una svista. Le
     * lunghezze del tracciato sono piu' generose delle nostre colonne quasi ovunque —
     * cinquanta caratteri per un cognome contro i cento che il database accetta — e
     * l'unico caso in cui il taglio morde davvero e' un cognome lunghissimo. Fra una
     * schedina con un cognome accorciato e nessuna schedina, la prima e' la meno
     * peggio: la persona resta registrata e riconoscibile. Il caso e' anche raro
     * abbastanza da non meritare un errore che blocchi gli arrivi di tutta la
     * giornata.
     */
    private static String campo(String valore, int lunghezza) {
        String pieno = valore == null ? "" : valore;
        if (pieno.length() > lunghezza) {
            pieno = pieno.substring(0, lunghezza);
        }
        return pieno + " ".repeat(lunghezza - pieno.length());
    }

    /**
     * Un campo di testo digitato da una persona: ripulito, poi trattato come gli
     * altri.
     *
     * <p>Sono due soli — il cognome e il nome — e sono esattamente i due che qualcuno
     * ha battuto a mano leggendo un documento. Tutto il resto della riga viene da
     * elenchi o da date, ed e' il motivo per cui passa da {@link #campo}.
     */
    private static String testo(String valore, int lunghezza) {
        return campo(ripulisci(valore), lunghezza);
    }

    /**
     * Toglie da una stringa tutto quel che il tracciato non accetta.
     *
     * <p>Tre passaggi, in quest'ordine perche' l'ordine conta:
     * <ol>
     *   <li><b>si scompone in forma NFD</b>, che separa la lettera dal suo accento —
     *       "e'" diventa "e" seguita da un segno diacritico a se' stante;</li>
     *   <li><b>si buttano i segni diacritici</b>, e quel che resta e' la lettera
     *       latina di base. E' cio' che rende "NICCOLO'" da "Niccolo'" invece che
     *       una casella piena di punti interrogativi;</li>
     *   <li><b>si passa a maiuscolo e si sostituisce con uno spazio tutto cio' che
     *       non e' ammesso.</b> Ci finiscono gli alfabeti che la scomposizione non
     *       sa ridurre — cirillico, greco, cinese — e la sostituzione con lo spazio
     *       e' l'unica scelta che non altera la lunghezza del campo.</li>
     * </ol>
     *
     * <p><b>Il limite di questo metodo, dichiarato</b>: un nome scritto in un
     * alfabeto non latino esce come una casella di spazi, cioe' una schedina che il
     * portale accetta e che non identifica nessuno. Non e' risolvibile qui — la
     * traslitterazione dal cirillico e' un problema suo, e sui documenti quel nome
     * compare gia' traslitterato dall'autorita' che li ha emessi. La risposta giusta
     * e' che al banco si digiti il nome <i>come sta stampato sul documento</i>, che e'
     * gia' quel che il contratto chiede.
     *
     * <p>{@link Locale#ROOT} sul maiuscolo e non il locale di sistema: in turco la
     * "i" maiuscola non e' "I", e un backend che gira su una macchina configurata in
     * turco produrrebbe un file diverso. Stessa ragione del CRLF scritto a mano.
     */
    private static String ripulisci(String valore) {
        if (valore == null) {
            return "";
        }

        String senzaAccenti = Normalizer.normalize(valore, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        String maiuscolo = senzaAccenti.toUpperCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder(maiuscolo.length());
        for (int i = 0; i < maiuscolo.length(); i++) {
            char c = maiuscolo.charAt(i);
            sb.append(CARATTERI_AMMESSI.indexOf(c) >= 0 ? c : ' ');
        }
        return sb.toString();
    }

    /** Una data nel formato del tracciato, o la casella vuota se manca. */
    private static String data(LocalDate valore) {
        return valore == null ? null : valore.format(DATA);
    }

    /**
     * Le notti, in due cifre con lo zero davanti.
     *
     * <p>Non passa da {@link #campo}: quello allinea a sinistra e riempie a destra,
     * mentre un numero in un tracciato si scrive con gli zeri davanti — "07" e non
     * "7 ". E' l'unico campo del tracciato che si comporti cosi', ed e' il motivo per
     * cui ha un metodo suo invece di un parametro in piu' su {@code campo}.
     */
    private static String permanenza(int giorni) {
        if (giorni < 1 || giorni > PERMANENZA_MASSIMA) {
            throw new IllegalStateException("Permanenza di " + giorni
                    + " notti: il tracciato ne scrive al massimo " + PERMANENZA_MASSIMA);
        }
        return String.format(Locale.ROOT, "%02d", giorni);
    }
}
