package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.RegistrationRateLimitProperties;
import com.felixhotel.backend.exception.TooManyRequestsException;
import com.felixhotel.backend.service.RegistrationAttemptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Ritardo progressivo sulle registrazioni, contate per indirizzo IP.
 *
 * <p>Il meccanismo sta in {@link ContatoreTentativi}, lo stesso che rallenta i
 * login falliti; qui c'e' solo cosa si conta e con quali soglie. Un contatore
 * unico, quindi, e non due come nel login: il perche' e' nel javadoc di
 * {@link RegistrationAttemptService}.
 */
@Service
public class RegistrationAttemptServiceImpl implements RegistrationAttemptService {

    private final ContatoreTentativi contatorePerIp;

    /**
     * Costruttore usato da Spring. L'annotazione e' necessaria perche' i
     * costruttori pubblici sono due e senza indicazione il contesto non saprebbe
     * quale scegliere.
     */
    @Autowired
    public RegistrationAttemptServiceImpl(RegistrationRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Costruttore per i test, che passando un {@link Clock} pilotabile possono
     * far scorrere il tempo invece di aspettarlo davvero.
     */
    public RegistrationAttemptServiceImpl(RegistrationRateLimitProperties properties, Clock clock) {
        this.contatorePerIp = new ContatoreTentativi("registrazioni per IP",
                new ContatoreTentativi.Parametri(properties.tentativiLiberiIp(), properties.ritardoIniziale(),
                        properties.ritardoMassimo(), properties.finestra()),
                clock);
    }

    @Override
    public void checkNotThrottled(String clientIp) {
        Duration attesa = contatorePerIp.attesaResidua(clientIp);
        if (attesa.isZero()) {
            return;
        }

        // Il messaggio non parla di tentativi "falliti" come quello del login: qui a
        // essere troppe sono le registrazioni in se', riuscite comprese.
        throw new TooManyRequestsException(
                "Troppe registrazioni da questo indirizzo. Riprova fra " + ContatoreTentativi.descriviAttesa(attesa));
    }

    @Override
    public void recordAttempt(String clientIp) {
        contatorePerIp.registra(clientIp);
    }

    @Override
    public void reset() {
        contatorePerIp.svuota();
    }

    /**
     * Pulizia periodica dei conteggi scaduti, vedi
     * {@link ContatoreTentativi#rimuoviScaduti()}. Perche' scatti davvero serve
     * {@code @EnableScheduling} (in {@code SchedulingConfig}): senza, i metodi
     * {@code @Scheduled} vengono ignorati in silenzio.
     */
    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    public void rimuoviScaduti() {
        contatorePerIp.rimuoviScaduti();
    }
}
