package com.felixhotel.backend.service.impl;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conta i tentativi fatti su una chiave (un'email, un indirizzo IP) e ne ricava
 * quanto chi la usa deve aspettare prima del prossimo: nulla finche' resta
 * entro i tentativi "liberi", poi un'attesa che <b>raddoppia ad ogni tentativo
 * successivo</b> fino a un tetto, e che si dimentica da sola dopo un periodo di
 * inattivita'.
 *
 * <p>E' il meccanismo, non la politica: questa classe non sa cosa sia un login
 * o una registrazione, non decide quando registrare un tentativo e non sceglie
 * il messaggio d'errore. Quelle sono decisioni dei service che la usano
 * ({@link LoginAttemptServiceImpl} e {@link RegistrationAttemptServiceImpl}),
 * ognuno con le proprie soglie e le proprie regole su cosa conta come tentativo.
 * Sta qui, package-private, perche' e' un dettaglio implementativo di quei due
 * e non qualcosa che il resto dell'applicazione debba poter usare.
 *
 * <p><b>Lo stato vive in memoria</b>, in una {@link ConcurrentHashMap} per
 * istanza. Il motivo e' che il percorso protetto e' proprio quello che un
 * attaccante martella: scrivere una riga su database ad ogni tentativo
 * trasformerebbe la difesa nel modo per mettere in ginocchio il database. In
 * cambio si accettano due limiti, entrambi tollerabili per come e' fatto oggi
 * questo progetto: i conteggi si azzerano al riavvio, e non sarebbero condivisi
 * fra piu' istanze (ce n'e' una sola; il giorno che se ne aggiungessero,
 * andrebbe reimplementata su Redis o equivalente lasciando invariate le
 * interfacce dei service).
 *
 * <p>Non e' thread-safe "per caso": ogni aggiornamento passa da una
 * {@code compute} sulla mappa e sostituisce per intero un record immutabile,
 * cosi' due richieste in parallelo non possono lasciare un conteggio a meta'.
 */
@Slf4j
class ContatoreTentativi {

    /**
     * Tetto al numero di chiavi tracciate. Chi varia la chiave ad ogni tentativo
     * (un'email diversa ogni volta, una botnet con molti indirizzi) crea una voce
     * nuova ogni volta: senza un limite la mappa crescerebbe finche' non finisce
     * la memoria, e la protezione diventerebbe essa stessa il modo per far cadere
     * l'app. Il valore e' largo — a 100.000 voci si parla di pochi MB — perche'
     * deve essere una rete di sicurezza, non un limite che si tocca in esercizio.
     */
    private static final int MAX_CHIAVI = 100_000;

    /**
     * Oltre questo scarto il raddoppio andrebbe in overflow molto prima di avere
     * un senso: 2^20 volte il ritardo iniziale sono giorni, e il tetto
     * configurato e' comunque gia' stato superato da un pezzo.
     */
    private static final int MAX_RADDOPPI = 20;

    /**
     * Taratura del ritardo. Sono parametri e non costanti perche' quanto stringere
     * dipende da cosa si sta proteggendo e da dove gira: un login e una
     * registrazione hanno frequenze legittime molto diverse, e nei test le soglie
     * si abbassano per esercitare il meccanismo in pochi tentativi.
     *
     * @param tentativiLiberi  tentativi tollerati prima che il ritardo cominci ad
     *                         applicarsi
     * @param ritardoIniziale  attesa imposta al primo tentativo oltre la soglia,
     *                         che raddoppia ad ogni successivo
     * @param ritardoMassimo   tetto del raddoppio. Senza, dopo qualche decina di
     *                         tentativi l'attesa diventerebbe di giorni: un blocco
     *                         di fatto, cioe' cio' che si vuole evitare
     * @param finestra         inattivita' dopo la quale il conteggio si azzera da
     *                         solo
     */
    record Parametri(int tentativiLiberi, Duration ritardoIniziale, Duration ritardoMassimo, Duration finestra) {
    }

    /**
     * Quel che si sa su una chiave in un dato momento. Immutabile: viene
     * sostituito per intero ad ogni aggiornamento.
     *
     * @param tentativi        tentativi accumulati nella finestra
     * @param ultimoTentativo  quando e' avvenuto l'ultimo, per capire quando la
     *                         finestra e' scaduta
     * @param prossimoPermesso istante prima del quale un nuovo tentativo viene
     *                         rifiutato
     */
    private record Stato(int tentativi, Instant ultimoTentativo, Instant prossimoPermesso) {
    }

    /** Comparirebbe nei log solo se il tetto di chiavi venisse raggiunto: serve a sapere quale contatore. */
    private final String nome;
    private final Parametri parametri;
    private final Clock clock;

    private final Map<String, Stato> contatori = new ConcurrentHashMap<>();

    ContatoreTentativi(String nome, Parametri parametri, Clock clock) {
        this.nome = nome;
        this.parametri = parametri;
        this.clock = clock;
    }

    /**
     * Quanto manca prima che questa chiave possa tentare di nuovo,
     * {@link Duration#ZERO} se puo' farlo subito (o se la chiave e' assente:
     * senza chiave non c'e' niente da contare, e la richiesta passa).
     */
    Duration attesaResidua(String chiave) {
        if (chiave == null) {
            return Duration.ZERO;
        }

        Stato stato = contatori.get(chiave);
        if (stato == null) {
            return Duration.ZERO;
        }

        Instant adesso = clock.instant();

        // Finestra scaduta: il conteggio non vale piu' niente e la voce se ne va subito,
        // senza aspettare la pulizia periodica.
        if (finestraScaduta(stato, adesso)) {
            contatori.remove(chiave, stato);
            return Duration.ZERO;
        }

        return adesso.isBefore(stato.prossimoPermesso())
                ? Duration.between(adesso, stato.prossimoPermesso())
                : Duration.ZERO;
    }

    /** Registra un tentativo su questa chiave, allungando il ritardo che verra' imposto al prossimo. */
    void registra(String chiave) {
        if (chiave == null || !haSpazio(chiave)) {
            return;
        }

        Instant adesso = clock.instant();
        contatori.compute(chiave, (k, stato) -> {
            // Se la finestra e' scaduta si riparte da uno: chi ha fatto qualche tentativo
            // un'ora fa non deve trascinarsi dietro il ritardo di allora.
            int tentativi = (stato == null || finestraScaduta(stato, adesso)) ? 1 : stato.tentativi() + 1;
            return new Stato(tentativi, adesso, adesso.plus(calcolaRitardo(tentativi)));
        });
    }

    /** Dimentica il conteggio di una singola chiave, che riparte da zero. */
    void dimentica(String chiave) {
        if (chiave != null) {
            contatori.remove(chiave);
        }
    }

    /** Dimentica tutti i conteggi. */
    void svuota() {
        contatori.clear();
    }

    /**
     * Butta via le chiavi la cui finestra e' scaduta: senza, la memoria
     * conserverebbe per sempre ogni chiave mai vista. Non e' solo igiene, e' il
     * modo in cui il tetto di {@link #MAX_CHIAVI} resta lontano in esercizio
     * normale. La chiama periodicamente il service che possiede il contatore.
     */
    void rimuoviScaduti() {
        Instant adesso = clock.instant();
        contatori.values().removeIf(stato -> finestraScaduta(stato, adesso));
    }

    /**
     * Descrive un'attesa in parole, per il messaggio di errore che la comunica a
     * chi chiama.
     *
     * <p>Sta qui, e non nei service che compongono il messaggio, perche' i due
     * messaggi sono diversi ma l'attesa si annuncia allo stesso modo: separarla
     * vorrebbe dire due arrotondamenti da tenere allineati a mano.
     *
     * <p>Arrotonda sempre <b>per eccesso</b>, in entrambe le unita': dire
     * "riprova fra 0 secondi" a chi verrebbe comunque rifiutato sarebbe un invito
     * a riprovare subito e sbattere di nuovo contro il 429, e per lo stesso motivo
     * un'attesa di 61 secondi si annuncia come due minuti e non come uno. Meglio
     * far aspettare un istante di troppo che promettere un'attesa piu' corta di
     * quella vera.
     */
    static String descriviAttesa(Duration attesa) {
        long secondi = Math.max(1, (attesa.toMillis() + 999) / 1000);
        if (secondi < 60) {
            return secondi + (secondi == 1 ? " secondo" : " secondi");
        }

        long minuti = (secondi + 59) / 60;
        return minuti + (minuti == 1 ? " minuto" : " minuti");
    }

    /**
     * Verifica che ci sia posto per una chiave nuova, provando prima a fare
     * spazio. Se la mappa resta piena si smette di tracciare (e lo si scrive nei
     * log): la scelta e' deliberata ed e' il male minore fra due sgradevoli —
     * rifiutare le richieste di tutti sarebbe un disservizio totale causato
     * dall'attaccante, mentre cosi' si perde temporaneamente la protezione ma il
     * servizio resta in piedi. Se questo messaggio comparisse davvero nei log, e'
     * il segnale che serve un contatore distribuito e non piu' una mappa in
     * memoria.
     */
    private boolean haSpazio(String chiave) {
        if (contatori.size() < MAX_CHIAVI || contatori.containsKey(chiave)) {
            return true;
        }

        rimuoviScaduti();
        if (contatori.size() < MAX_CHIAVI) {
            return true;
        }

        log.warn("Contatore '{}' al limite di {} chiavi: nuovi tentativi non tracciati", nome, MAX_CHIAVI);
        return false;
    }

    /**
     * Il ritardo da imporre dopo l'ennesimo tentativo: nessuno finche' si resta
     * entro i tentativi liberi, poi {@code ritardoIniziale} che raddoppia ad ogni
     * tentativo successivo fino al tetto configurato.
     */
    private Duration calcolaRitardo(int tentativi) {
        int eccesso = tentativi - parametri.tentativiLiberi();
        if (eccesso <= 0) {
            return Duration.ZERO;
        }

        if (eccesso > MAX_RADDOPPI) {
            return parametri.ritardoMassimo();
        }

        Duration ritardo = parametri.ritardoIniziale().multipliedBy(1L << (eccesso - 1));
        return ritardo.compareTo(parametri.ritardoMassimo()) > 0 ? parametri.ritardoMassimo() : ritardo;
    }

    private boolean finestraScaduta(Stato stato, Instant adesso) {
        return adesso.isAfter(stato.ultimoTentativo().plus(parametri.finestra()));
    }
}
