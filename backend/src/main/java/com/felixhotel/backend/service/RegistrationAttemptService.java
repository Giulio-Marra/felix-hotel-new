package com.felixhotel.backend.service;

import com.felixhotel.backend.exception.TooManyRequestsException;

/**
 * Tiene il conto delle registrazioni tentate da ciascun indirizzo IP e impone un
 * ritardo crescente a chi ne accumula troppe, per rendere impraticabile la
 * creazione di account a macchina.
 *
 * <p>Fratello di {@link LoginAttemptService}, con cui condivide il meccanismo
 * (ritardo progressivo, vedi {@code ContatoreTentativi}) ma non le regole:
 * <ul>
 *   <li><b>si conta ogni tentativo, non solo quelli falliti</b>. Sul login il
 *       danno e' indovinare una password, quindi contano i fallimenti; qui il
 *       danno e' proprio la registrazione riuscita, quindi conta tutto;</li>
 *   <li><b>l'unica chiave e' l'indirizzo IP</b>. L'email non serve come chiave:
 *       chi crea account a macchina ne usa una diversa ogni volta, e un account
 *       da proteggere non esiste ancora;</li>
 *   <li><b>niente azzeramento su esito positivo</b>, per lo stesso motivo: qui
 *       riuscire non e' la prova di essere legittimi, e' il risultato che si
 *       vuole limitare.</li>
 * </ul>
 *
 * <p>Chiama {@link #checkNotThrottled} <i>prima</i> di qualunque lavoro: il senso
 * della difesa e' che il tentativo di troppo non costi all'applicazione ne' un
 * hash BCrypt ne' un accesso al database.
 */
public interface RegistrationAttemptService {

    /**
     * Lascia passare se questo indirizzo puo' registrare adesso, altrimenti
     * solleva {@link TooManyRequestsException} (429) indicando fra quanto
     * riprovare.
     *
     * @param clientIp indirizzo IP di chi sta chiamando, {@code null} se non
     *                 determinabile — in tal caso non c'e' nulla da contare e la
     *                 richiesta passa
     */
    void checkNotThrottled(String clientIp);

    /**
     * Registra un tentativo di registrazione, allungando il ritardo che verra'
     * imposto al prossimo. Va chiamato <b>qualunque sia l'esito</b>: vedi il
     * javadoc della classe per il perche'.
     */
    void recordAttempt(String clientIp);

    /**
     * Dimentica tutti i conteggi.
     *
     * <p>Esiste per i test di integrazione, ed e' bene sapere perche' invece di
     * scoprirlo dopo: i contatori vivono in memoria e il contesto Spring e'
     * condiviso da tutta la suite, quindi gli account creati da un test
     * resterebbero contati anche per i successivi — che, chiamando dallo stesso
     * indirizzo, si vedrebbero rifiutare una registrazione legittima con un 429
     * uscito dal nulla. Azzerare prima di ogni test rende ciascuno indipendente
     * da quelli che l'hanno preceduto.
     */
    void reset();
}
