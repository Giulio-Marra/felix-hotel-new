package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.PagamentoRequest;

/**
 * Il registro dei pagamenti di una prenotazione.
 *
 * <p><b>Registra incassi avvenuti, non li esegue.</b> Nessun denaro si muove per effetto
 * di questi metodi: il pagamento online, quello in cui il denaro si muove davvero,
 * arrivera' con l'integrazione del fornitore e avra' una porta sua.
 */
public interface PagamentoService {

    /**
     * Il riepilogo dei pagamenti di una prenotazione: quanto costa, quanto se ne doveva
     * in anticipo, quanto e' entrato e quanto resta.
     *
     * <p>La leggono il personale su qualunque prenotazione e il cliente sulla propria.
     */
    ApiBaseResponse elenca(Long prenotazioneId);

    /**
     * Scrive un incasso nel registro. Solo personale: dichiarare di aver pagato non e' un
     * gesto che possa fare chi paga.
     */
    ApiBaseResponse registra(Long prenotazioneId, PagamentoRequest request);
}
