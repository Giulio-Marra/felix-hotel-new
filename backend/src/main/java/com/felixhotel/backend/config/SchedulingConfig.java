package com.felixhotel.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Abilita i metodi annotati {@code @Scheduled}, che senza questa
 * configurazione Spring ignorerebbe in silenzio — senza errori all'avvio e
 * senza che nulla venga mai eseguito.
 *
 * <p>Oggi ne esiste uno solo, la pulizia periodica dei contatori dei tentativi
 * di login ({@code LoginAttemptServiceImpl#rimuoviScaduti}). Sta in una classe
 * a se' e non appesa a una configurazione esistente perche' l'abilitazione vale
 * per tutta l'applicazione, non per il singolo caso che l'ha resa necessaria.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
