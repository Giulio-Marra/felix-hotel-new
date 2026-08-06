package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.LoginRateLimitProperties;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.service.LoginAttemptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Ritardo progressivo sui tentativi di login falliti.
 *
 * <p>Il meccanismo del ritardo (soglia, raddoppio, tetto, finestra) sta in
 * {@link ContatoreTentativi}, condiviso con la protezione delle registrazioni.
 * Qui restano le decisioni che riguardano il login e solo quello: <b>due
 * contatori indipendenti</b>, perche' gli attacchi da fermare sono due e
 * distinti — per email (molte password su un account preciso) e per indirizzo IP
 * (una password probabile su molti account diversi, il password spraying, che
 * sulla singola email non lascerebbe traccia) — quale sia il tentativo da
 * contare (uno fallito, non uno qualsiasi) e cosa azzera cosa.
 */
@Service
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private final ContatoreTentativi contatorePerEmail;
    private final ContatoreTentativi contatorePerIp;

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
        this.contatorePerEmail = new ContatoreTentativi("login per email",
                new ContatoreTentativi.Parametri(properties.tentativiLiberiEmail(), properties.ritardoIniziale(),
                        properties.ritardoMassimo(), properties.finestra()),
                clock);
        this.contatorePerIp = new ContatoreTentativi("login per IP",
                new ContatoreTentativi.Parametri(properties.tentativiLiberiIp(), properties.ritardoIniziale(),
                        properties.ritardoMassimo(), properties.finestra()),
                clock);
    }

    @Override
    public void checkNotThrottled(String email, String clientIp) {
        // Si applica il piu' lungo dei due ritardi: se l'IP e' rallentato ma l'email no
        // (o viceversa), quello che conta e' il vincolo piu' stringente.
        Duration attesaEmail = contatorePerEmail.attesaResidua(chiaveEmail(email));
        Duration attesaIp = contatorePerIp.attesaResidua(clientIp);
        Duration attesa = attesaEmail.compareTo(attesaIp) >= 0 ? attesaEmail : attesaIp;

        if (attesa.isZero()) {
            return;
        }

        throw new TooManyRequestsException(
                "Troppi tentativi di accesso falliti. Riprova fra " + ContatoreTentativi.descriviAttesa(attesa));
    }

    @Override
    public void recordFailure(String email, String clientIp) {
        contatorePerEmail.registra(chiaveEmail(email));
        contatorePerIp.registra(clientIp);
    }

    @Override
    public void recordSuccess(String email, String clientIp) {
        // Si azzera solo il contatore dell'email, non quello dell'IP: chi attacca
        // possiede quasi sempre almeno un account valido, e un login riuscito ogni
        // tanto gli basterebbe per ripulirsi il limite per IP e continuare
        // indisturbato a provare password sugli altri account.
        contatorePerEmail.dimentica(chiaveEmail(email));
    }

    @Override
    public void reset() {
        contatorePerEmail.svuota();
        contatorePerIp.svuota();
    }

    /**
     * Pulizia periodica dei conteggi scaduti, vedi
     * {@link ContatoreTentativi#rimuoviScaduti()}. Perche' scatti davvero serve
     * {@code @EnableScheduling} (in {@code SchedulingConfig}): senza, i metodi
     * {@code @Scheduled} vengono ignorati in silenzio, all'avvio non succede
     * niente e non lo si scopre finche' la memoria non cresce.
     */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void rimuoviScaduti() {
        contatorePerEmail.rimuoviScaduti();
        contatorePerIp.rimuoviScaduti();
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
