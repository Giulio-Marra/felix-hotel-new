package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponsePaginated;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * La ricerca del cliente: quali tipologie sono prenotabili in un periodo, quante
 * camere ne restano e quanto costerebbe il soggiorno.
 *
 * <p><b>Non e' il catalogo con due parametri in piu'.</b> Il catalogo risponde a
 * "cosa offrite", questa a "cosa posso avere dal 10 al 13": la seconda dipende da
 * un intervallo di date e da cosa hanno gia' preso gli altri, e nessuna riga a
 * database la contiene. E' anche il motivo per cui vive in un service suo invece
 * che dentro {@link TipologiaCameraService}, che di date non sa niente.
 */
public interface DisponibilitaService {

    /**
     * Tipologie prenotabili nel periodo, impaginate, con la loro disponibilita'.
     *
     * <p><b>Restituisce anche le tipologie esaurite</b>, con zero camere
     * disponibili. Non e' una comodita' per il client: togliere le righe dopo
     * aver impaginato darebbe pagine di dimensione variabile — sarebbe filtrare
     * in memoria cio' che si e' impaginato in database.
     *
     * @param dataCheckIn   giorno di arrivo cercato. A differenza della
     *                      prenotazione, qui puo' stare nel passato: si sta
     *                      guardando, non prenotando
     * @param dataCheckOut  giorno di partenza, che deve essere successivo
     *                      all'arrivo, altrimenti {@code BadRequestException}
     * @param numeroOspiti  se valorizzato, tiene solo le tipologie che ospitano
     *                      almeno quelle persone
     * @param prezzoMinimo  se valorizzato, esclude chi costa meno di tanto a notte
     * @param prezzoMassimo se valorizzato, esclude chi costa piu' di tanto a notte
     */
    ApiBaseResponsePaginated cerca(LocalDate dataCheckIn, LocalDate dataCheckOut,
                                   Integer numeroOspiti, BigDecimal prezzoMinimo,
                                   BigDecimal prezzoMassimo, int page, int size);
}
