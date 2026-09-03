package com.felixhotel.backend.entity.enums;

/**
 * Com'e' finito l'ultimo giro di lettura su una sorgente di calendario.
 *
 * <p><b>Tre valori e non due</b>, e per una volta la regola 24 spinge nella direzione
 * opposta al solito: qui la distinzione in piu' si paga da sola, perche' sono <i>tre cose
 * da fare</i> diverse. {@link #OK} non chiede niente; {@link #ERRORE} chiede di guardare
 * l'indirizzo, che e' un problema tecnico; {@link #CONFLITTI} chiede di guardare
 * l'albergo, che e' un problema di camere. Confondere gli ultimi due manderebbe qualcuno
 * a cercare un guasto davanti a un overbooking, dove non c'e' niente di rotto.
 *
 * <p><b>Non c'e' un valore per "mai eseguita"</b>: quello lo dice gia'
 * {@code ultimaSincronizzazione} valendo null, e un enum che raccontasse la stessa cosa
 * permetterebbe alle due colonne di contraddirsi.
 */
public enum EsitoSincronizzazione {

    /** Letto e riscritto, niente da segnalare. */
    OK,

    /**
     * Letto, ma quel che dice il canale non torna con quel che sappiamo noi: o quelle
     * notti erano gia' tutte vendute, o su quella camera c'era gia' un blocco di
     * qualcun altro.
     *
     * <p><b>Non e' un fallimento</b>: i blocchi che si potevano scrivere sono stati
     * scritti. E' un fatto dell'albergo che qualcuno deve guardare.
     */
    CONFLITTI,

    /**
     * Il calendario non si e' potuto leggere affatto — indirizzo irraggiungibile, risposta
     * che non e' un calendario, canale in errore.
     *
     * <p><b>I blocchi precedenti restano dove sono.</b> Un canale che non risponde non
     * vuol dire che abbia disdetto tutto: cancellarli rimetterebbe in vendita camere
     * vendute, che e' il danno peggiore fra i due.
     */
    ERRORE
}
