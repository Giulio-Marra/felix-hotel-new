package com.felixhotel.backend.repository;

/**
 * Quante camere fisiche ha una tipologia.
 *
 * <p>Esiste per la ricerca di disponibilita', che di conteggi ne vuole tanti
 * quante sono le tipologie della pagina. Chiederli uno per uno con
 * {@code countByTipologiaCameraId} sarebbe la classica N+1: una query per
 * mostrare una riga, moltiplicata per venti righe. La creazione e la conferma
 * continuano a usare quel metodo, e non e' un doppione — li' la tipologia e'
 * una sola e il conteggio raggruppato non avrebbe niente da raggruppare.
 */
public interface ConteggioCamere {

    /** La tipologia a cui si riferisce il conteggio. */
    Long getTipologiaCameraId();

    /**
     * Quante camere fisiche esistono di quella tipologia, <b>stato operativo
     * compreso</b>: una stanza in manutenzione oggi non dice niente su come
     * sara' fra due mesi. Vedi {@code CameraRepository.countByTipologiaCameraId},
     * dove la distinzione e' scritta per esteso.
     */
    long getTotale();
}
