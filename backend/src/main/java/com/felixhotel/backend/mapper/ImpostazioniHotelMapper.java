package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.ImpostazioniHotelPubblicheResponse;
import com.felixhotel.backend.dto.ImpostazioniHotelResponse;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import org.springframework.stereotype.Component;

/**
 * Conversione Entity -&gt; DTO per {@link ImpostazioniHotel}. Scritta a mano per
 * scelta di progetto (niente MapStruct); i DTO di destinazione sono invece
 * generati dallo spec OpenAPI.
 *
 * <p><b>Due metodi perche' ci sono due pubblici</b>, ed e' la parte di questa
 * classe che vale la pena leggere. Chiunque puo' sapere come si chiama
 * l'albergo, dove sta e a che ora si consegnano le camere: sono le righe in
 * fondo a ogni pagina di un sito d'albergo. Partita IVA, CIN, codice ISTAT del
 * comune e codice per Alloggiati Web sono un'altra cosa — l'ultimo in
 * particolare e' di fatto una credenziale verso la Questura — e escono solo
 * dall'endpoint riservato agli ADMIN.
 *
 * <p><b>La separazione sta nei tipi, non in un filtro.</b>
 * {@link ImpostazioniHotelPubblicheResponse} quei campi non ce li ha proprio,
 * quindi non esiste nessun punto in cui qualcuno possa dimenticarsi di
 * azzerarli. Con un DTO solo e i campi riservati messi a null il risultato
 * sarebbe identico finche' nessuno tocca la classe — e il giorno che qualcuno
 * aggiunge un campo, quel campo esce pubblico senza che nessuno l'abbia deciso.
 * E' la stessa logica della regola 14 sui {@code permitAll} elencati a mano:
 * l'aggiunta distratta deve fallire chiusa, non aperta.
 *
 * <p>Come gli altri mapper del progetto converte solo in uscita: riempire
 * l'entity con i dati della richiesta resta nel Service, dove si decide quali
 * campi sono modificabili da fuori.
 */
@Component
public class ImpostazioniHotelMapper {

    /**
     * La vista completa, per gli ADMIN.
     *
     * <p>Non porta l'id, al contrario di ogni altra risposta del progetto: la
     * riga e' una sola e nessuna rotta la indirizza per id, quindi pubblicarlo
     * direbbe che esiste una collezione da cui sceglierla.
     */
    public ImpostazioniHotelResponse toResponse(ImpostazioniHotel impostazioni) {
        return new ImpostazioniHotelResponse()
                .nome(impostazioni.getNome())
                .indirizzo(impostazioni.getIndirizzo())
                .telefono(impostazioni.getTelefono())
                .email(impostazioni.getEmail())
                .orarioCheckInDefault(impostazioni.getOrarioCheckInDefault())
                .orarioCheckOutDefault(impostazioni.getOrarioCheckOutDefault())
                .ragioneSociale(impostazioni.getRagioneSociale())
                .partitaIva(impostazioni.getPartitaIva())
                .codiceFiscale(impostazioni.getCodiceFiscale())
                .cin(impostazioni.getCin())
                .comune(impostazioni.getComune())
                .codiceIstatComune(impostazioni.getCodiceIstatComune())
                .codiceStrutturaAlloggiati(impostazioni.getCodiceStrutturaAlloggiati());
    }

    /** La vista pubblica: recapiti e orari, niente identita' fiscale. */
    public ImpostazioniHotelPubblicheResponse toPubblicheResponse(ImpostazioniHotel impostazioni) {
        return new ImpostazioniHotelPubblicheResponse()
                .nome(impostazioni.getNome())
                .indirizzo(impostazioni.getIndirizzo())
                .telefono(impostazioni.getTelefono())
                .email(impostazioni.getEmail())
                .orarioCheckInDefault(impostazioni.getOrarioCheckInDefault())
                .orarioCheckOutDefault(impostazioni.getOrarioCheckOutDefault());
    }
}
