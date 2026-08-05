package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.LoginRateLimitProperties;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.service.LoginAttemptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ritardo progressivo sui tentativi di login falliti, tenuto <b>in memoria</b>.
 *
 * <p>Perche' in memoria e non su database: il percorso che questo codice
 * protegge e' proprio quello che un attaccante martella: scrivere una riga ad
 * ogni tentativo fallito trasformerebbe la difesa dal brute force in un modo
 * per mettere in ginocchio il database. In cambio si accettano due limiti,
 * entrambi tollerabili per come e' fatto questo progetto: i contatori si
 * azzerano al riavvio dell'applicazione, e non sarebbero condivisi fra piu'
 * istanze (oggi ce n'e' una sola; il giorno che se ne aggiungessero, questa
 * classe andrebbe reimplementata su Redis o equivalente lasciando invariata
 * l'interfaccia).
 *
 * <p>Due mappe distinte, una per email e una per IP: vedi
 * {@link LoginAttemptService} per il perche' servano entrambe.
 */
@Slf4j
@Service
public class LoginAttemptServiceImpl implements LoginAttemptService {

    /**
     * Tetto al numero di chiavi tracciate, per mappa. Un attaccante che varia
     * l'email ad ogni tentativo crea una voce nuova ogni volta: senza un limite
     * la mappa crescerebbe finche' non finisce la memoria, e la protezione dal
     * brute force diventerebbe essa stessa il modo per far cadere l'app. Il
     * valore e' largo — a 100.000 voci si parla di pochi MB — perche' deve
     * essere una rete di sicurezza, non un limite che si tocca in esercizio.
     */
    private static final int MAX_CHIAVI = 100_000;

    /**
     * Oltre questo scarto il raddoppio andrebbe in overflow molto prima di
     * avere un senso: 2^20 volte il ritardo iniziale sono giorni, e il tetto
     * configurato e' comunque gia' stato superato da un pezzo.
     */
    private static final int MAX_RADDOPPI = 20;

    private final LoginRateLimitProperties properties;
    private final Clock clock;

    private final Map<String, Stato> tentativiPerEmail = new ConcurrentHashMap<>();
    private final Map<String, Stato> tentativiPerIp = new ConcurrentHashMap<>();

    /**
     * Costruttore usato da Spring. L'annotazione e' necessaria perche' i
     * costruttori pubblici sono due e senza indicazione il contesto non
     * saprebbe quale scegliere.
     */
    @Autowired
    public LoginAttemptServiceImpl(LoginRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Costruttore per i test, che passando un {@link Clock} pilotabile possono
     * far scorrere il tempo invece di aspettarlo davvero: una suite che
     * verificasse un'attesa di otto secondi dormendo otto secondi non la
     * eseguirebbe nessuno.
     */
    public LoginAttemptServiceImpl(LoginRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Quel che si sa su una chiave (un'email o un IP) in un dato momento.
     * Immutabile: viene sostituito per intero ad ogni aggiornamento dentro una
     * {@code compute}, cosi' due login concorrenti non possono lasciare il
     * conteggio a meta'.
     *
     * @param fallimenti      fallimenti consecutivi accumulati nella finestra
     * @param ultimoFallimento quando e' avvenuto l'ultimo, per capire quando la
     *                         finestra e' scaduta
     * @param prossimoPermesso istante prima del quale un nuovo tentativo viene
     *                         rifiutato
     */
    private record Stato(int fallimenti, Instant ultimoFallimento, Instant prossimoPermesso) {
    }

    @Override
    public void checkNotThrottled(String email, String clientIp) {
        Instant adesso = clock.instant();

        // Si applica il piu' lungo dei due ritardi: se l'IP e' rallentato ma l'email no
        // (o viceversa), quello che conta e' il vincolo piu' stringente.
        Duration attesaEmail = attesaResidua(tentativiPerEmail, chiaveEmail(email), adesso);
        Duration attesaIp = attesaResidua(tentativiPerIp, clientIp, adesso);
        Duration attesa = attesaEmail.compareTo(attesaIp) >= 0 ? attesaEmail : attesaIp;

        if (attesa.isZero()) {
            return;
        }

        // Arrotondato per eccesso: dire "riprova fra 0 secondi" a chi verrebbe comunque
        // rifiutato sarebbe un invito a riprovare subito e sbattere di nuovo contro il 429.
        long secondi = Math.max(1, (attesa.toMillis() + 999) / 1000);
        throw new TooManyRequestsException("Troppi tentativi di accesso falliti. Riprova fra "
                + secondi + (secondi == 1 ? " secondo" : " secondi"));
    }

    @Override
    public void recordFailure(String email, String clientIp) {
        Instant adesso = clock.instant();
        registraFallimento(tentativiPerEmail, chiaveEmail(email), adesso, properties.tentativiLiberiEmail());
        registraFallimento(tentativiPerIp, clientIp, adesso, properties.tentativiLiberiIp());
    }

    @Override
    public void recordSuccess(String email, String clientIp) {
        // Si azzera solo il contatore dell'email, non quello dell'IP: chi attacca
        // possiede quasi sempre almeno un account valido, e un login riuscito ogni
        // tanto gli basterebbe per ripulirsi il limite per IP e continuare
        // indisturbato a provare password sugli altri account.
        String chiave = chiaveEmail(email);
        if (chiave != null) {
            tentativiPerEmail.remove(chiave);
        }
    }

    @Override
    public void reset() {
        tentativiPerEmail.clear();
        tentativiPerIp.clear();
    }

    /**
     * Butta via periodicamente le chiavi la cui finestra e' scaduta: senza,
     * la memoria conserverebbe per sempre ogni email mai sbagliata da chiunque.
     * Non e' solo igiene, e' il modo in cui il tetto di {@link #MAX_CHIAVI}
     * resta lontano in esercizio normale.
     */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void rimuoviScaduti() {
        Instant adesso = clock.instant();
        tentativiPerEmail.values().removeIf(stato -> finestraScaduta(stato, adesso));
        tentativiPerIp.values().removeIf(stato -> finestraScaduta(stato, adesso));
    }

    /**
     * Quanto manca prima che questa chiave possa tentare di nuovo,
     * {@link Duration#ZERO} se puo' farlo subito.
     */
    private Duration attesaResidua(Map<String, Stato> contatori, String chiave, Instant adesso) {
        if (chiave == null) {
            return Duration.ZERO;
        }

        Stato stato = contatori.get(chiave);
        if (stato == null) {
            return Duration.ZERO;
        }

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

    private void registraFallimento(Map<String, Stato> contatori, String chiave, Instant adesso, int tentativiLiberi) {
        if (chiave == null) {
            return;
        }

        if (!haSpazio(contatori, chiave)) {
            return;
        }

        contatori.compute(chiave, (k, stato) -> {
            // Se la finestra e' scaduta si riparte da uno: chi ha sbagliato qualche
            // password un'ora fa non deve trascinarsi dietro il ritardo di allora.
            int fallimenti = (stato == null || finestraScaduta(stato, adesso)) ? 1 : stato.fallimenti() + 1;
            return new Stato(fallimenti, adesso, adesso.plus(calcolaRitardo(fallimenti, tentativiLiberi)));
        });
    }

    /**
     * Verifica che ci sia posto per una chiave nuova, provando prima a fare
     * spazio. Se la mappa resta piena si smette di tracciare (e lo si scrive nei
     * log): la scelta e' deliberata ed e' il male minore fra due sgradevoli —
     * rifiutare i login di tutti sarebbe un disservizio totale causato
     * dall'attaccante, mentre cosi' si perde temporaneamente la protezione ma
     * il servizio resta in piedi. Se questo messaggio comparisse davvero nei
     * log, e' il segnale che serve un contatore distribuito e non piu' una
     * mappa in memoria.
     */
    private boolean haSpazio(Map<String, Stato> contatori, String chiave) {
        if (contatori.size() < MAX_CHIAVI || contatori.containsKey(chiave)) {
            return true;
        }

        rimuoviScaduti();
        if (contatori.size() < MAX_CHIAVI) {
            return true;
        }

        log.warn("Contatori dei tentativi di login al limite di {} chiavi: nuovi tentativi non tracciati", MAX_CHIAVI);
        return false;
    }

    /**
     * Il ritardo da imporre dopo l'ennesimo fallimento: nessuno finche' si resta
     * entro i tentativi liberi, poi {@code ritardoIniziale} che raddoppia ad ogni
     * fallimento successivo fino al tetto configurato.
     */
    private Duration calcolaRitardo(int fallimenti, int tentativiLiberi) {
        int eccesso = fallimenti - tentativiLiberi;
        if (eccesso <= 0) {
            return Duration.ZERO;
        }

        if (eccesso > MAX_RADDOPPI) {
            return properties.ritardoMassimo();
        }

        Duration ritardo = properties.ritardoIniziale().multipliedBy(1L << (eccesso - 1));
        return ritardo.compareTo(properties.ritardoMassimo()) > 0 ? properties.ritardoMassimo() : ritardo;
    }

    private boolean finestraScaduta(Stato stato, Instant adesso) {
        return adesso.isAfter(stato.ultimoFallimento().plus(properties.finestra()));
    }

    /**
     * Normalizza l'email prima di usarla come chiave: senza, {@code Mario@X.it}
     * e {@code mario@x.it} sarebbero due contatori distinti e basterebbe
     * alternare le maiuscole per non farsi mai rallentare.
     */
    private String chiaveEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
