package com.felixhotel.backend.service;

import com.felixhotel.backend.exception.TooManyRequestsException;

/**
 * Tiene il conto dei tentativi di login falliti e impone un ritardo crescente
 * a chi ne accumula troppi, per rendere impraticabile il brute force sulle
 * password.
 *
 * <p>Il conteggio e' tenuto su due chiavi indipendenti, perche' gli attacchi
 * che si vogliono fermare sono due e distinti: <b>per email</b> (molte password
 * provate su un account preciso) e <b>per indirizzo IP</b> (una password
 * probabile provata su molti account diversi, il cosiddetto password spraying,
 * che sulla singola email non lascerebbe traccia). Il ritardo che si applica
 * e' il piu' lungo dei due.
 *
 * <p>Chiama {@link #checkNotThrottled} <i>prima</i> di verificare le
 * credenziali: il senso della difesa e' proprio non arrivare a controllarle.
 * Vedi {@code LoginRateLimitProperties} per il perche' di un ritardo invece di
 * un blocco.
 */
public interface LoginAttemptService {

    /**
     * Lascia passare se questa email e questo IP possono tentare adesso,
     * altrimenti solleva {@link TooManyRequestsException} (429) indicando fra
     * quanto riprovare.
     *
     * @param email    email con cui si sta tentando l'accesso; puo' non
     *                 corrispondere ad alcun account (i tentativi si contano
     *                 comunque, altrimenti basterebbe variare l'email per
     *                 azzerare il conteggio)
     * @param clientIp indirizzo IP di chi sta chiamando, {@code null} se non
     *                 determinabile
     */
    void checkNotThrottled(String email, String clientIp);

    /**
     * Registra un tentativo fallito, allungando il ritardo che verra' imposto
     * al prossimo.
     */
    void recordFailure(String email, String clientIp);

    /** Registra un accesso riuscito, azzerando il ritardo accumulato dall'email. */
    void recordSuccess(String email, String clientIp);

    /**
     * Dimentica tutti i conteggi.
     *
     * <p>Esiste per i test di integrazione, ed e' bene sapere perche' invece di
     * scoprirlo dopo: i contatori vivono in memoria e il contesto Spring e'
     * condiviso da tutta la suite, quindi i tentativi falliti di proposito da un
     * test resterebbero contati anche per i successivi — che, chiamando dallo
     * stesso IP, si vedrebbero rifiutare un login legittimo con un 429 uscito
     * dal nulla. Azzerare prima di ogni test rende ciascuno indipendente da
     * quelli che l'hanno preceduto.
     */
    void reset();
}
