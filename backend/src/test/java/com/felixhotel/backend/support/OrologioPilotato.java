package com.felixhotel.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Orologio che avanza solo quando glielo si dice.
 *
 * <p>Serve a verificare attese e scadenze senza che la suite debba davvero
 * aspettarle: un test che per controllare un ritardo di otto secondi ne dormisse
 * otto smetterebbe presto di essere eseguito, e un test che non si esegue non
 * protegge niente.
 *
 * <p>Sta qui e non dentro una singola classe di test perche' lo usano tutti i
 * componenti costruiti attorno a un {@link Clock} iniettabile: oggi i due
 * contatori del ritardo progressivo (login e registrazioni), domani qualunque
 * regola del dominio che dipenda dalla data — scadenze di prenotazione, calcolo
 * delle notti.
 */
public final class OrologioPilotato extends Clock {

    private Instant adesso;

    public OrologioPilotato(Instant partenza) {
        this.adesso = partenza;
    }

    /** Sposta avanti l'istante corrente della durata indicata. */
    public void avanza(Duration quanto) {
        adesso = adesso.plus(quanto);
    }

    @Override
    public Instant instant() {
        return adesso;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
