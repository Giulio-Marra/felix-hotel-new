package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.ImpostazioniApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ImpostazioniHotelRequest;
import com.felixhotel.backend.service.ImpostazioniHotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anagrafica della struttura. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link ImpostazioniApi}, generata dallo spec OpenAPI: qui si implementa solo
 * il corpo dei metodi, che chiamano il Service e rigirano la busta usando come
 * status HTTP quello che la busta gia' dichiara.
 *
 * <p><b>Due letture con due permessi diversi</b>, e la divisione fra le due e'
 * il punto di questa risorsa. {@code /api/impostazioni/pubbliche} e' l'unica
 * rotta aperta e la dichiara {@code SecurityConfig}, dove stanno tutti i
 * {@code permitAll} del progetto elencati per path esatto; tutto il resto —
 * cioe' la risorsa vera, con partita IVA, CIN e codice per Alloggiati Web —
 * ricade nel default autenticato piu' il {@code @PreAuthorize} qui sotto.
 *
 * <p><b>Il verso della divisione non e' casuale.</b> La rotta pubblica e' quella
 * <i>lunga</i>, e la risorsa completa sta sul path base: cosi' il default e'
 * chiuso e ad aprirsi e' una sola eccezione dichiarata, invece che il
 * contrario. E' la regola 20 applicata a un path — chi domani aggiunge un campo
 * alle impostazioni non lo pubblica per distrazione, perche' per pubblicarlo
 * deve scriverlo anche nel DTO pubblico, che e' un tipo separato.
 *
 * <p><b>Un livello di permesso e non tre</b>, come sugli account del personale e
 * al contrario delle camere: la' lo STAFF legge l'inventario perche' e' il
 * lavoro di ogni turno, qui i dati che non sono gia' pubblici sono l'identita'
 * fiscale della societa'. Gli orari e i recapiti, che a chi sta al banco
 * servono davvero, li legge dalla rotta pubblica come chiunque altro.
 */
@RestController
@RequiredArgsConstructor
public class ImpostazioniHotelController implements ImpostazioniApi {

    private final ImpostazioniHotelService impostazioniHotelService;

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> dettaglioImpostazioni() {
        ApiBaseResponse response = impostazioniHotelService.leggi();
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> dettaglioImpostazioniPubbliche() {
        ApiBaseResponse response = impostazioniHotelService.leggiPubbliche();
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaImpostazioni(ImpostazioniHotelRequest request) {
        ApiBaseResponse response = impostazioniHotelService.aggiorna(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
