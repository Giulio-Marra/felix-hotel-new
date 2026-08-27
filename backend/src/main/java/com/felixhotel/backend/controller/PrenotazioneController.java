package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.PrenotazioniApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneCheckInRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.StatoPrenotazione;
import com.felixhotel.backend.service.PrenotazioneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prenotazioni. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link PrenotazioniApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Qui il {@code @PreAuthorize} c'e' su due metodi soli su sette</b>, ed e'
 * la distinzione che questo Controller esiste per mostrare. Sul resto non c'e',
 * e non e' una dimenticanza: nessun path delle prenotazioni compare fra i
 * {@code permitAll} di {@code SecurityConfig}, quindi tutti ricadono nel default
 * {@code anyRequest().authenticated()} e chi arriva senza token si ferma con un
 * 401 — il livello "serve un account" e' gia' garantito.
 *
 * <p>Sopra a quello, per creare, leggere, confermare e annullare, la domanda non
 * e' "che ruolo hai" ma <b>"e' tua?"</b>, e a quella un'espressione SpEL non sa
 * rispondere senza aver letto la riga. Il ruolo entra nella decisione — STAFF e
 * ADMIN vedono e toccano tutto — ma sempre in coppia con l'intestatario, quindi
 * la decisione sta nel Service, dove i dati ci sono. Spezzarla in due, meta' qui
 * e meta' la', vorrebbe dire leggerne una meta' sola quando si va a verificare
 * com'e' protetta.
 *
 * <p><b>Check-in e check-out sono l'eccezione, e lo sono per la ragione
 * opposta</b>: li' la domanda torna a essere esattamente "che ruolo hai".
 * Registrare un arrivo e' un'operazione di turno, e nessun cliente la fa sulla
 * propria prenotazione nemmeno essendone il titolare — non e' lui a consegnare
 * le chiavi. Non c'e' niente da leggere per deciderlo, quindi il posto giusto e'
 * l'annotazione: metterlo nel Service vorrebbe dire scrivere a mano un controllo
 * che il framework fa meglio, e per giunta nasconderlo dove nessuno lo cerca.
 *
 * <p>Il Controller resta per il resto quello che e' sempre: chiama il Service e
 * rigira la busta, usando come status HTTP quello che la busta gia' dichiara.
 */
@RestController
@RequiredArgsConstructor
public class PrenotazioneController implements PrenotazioniApi {

    private final PrenotazioneService prenotazioneService;

    @Override
    public ResponseEntity<ApiBaseResponsePaginated> elencaPrenotazioni(
            Integer page, Integer size, StatoPrenotazione stato) {
        // page e size non sono mai null (lo spec dichiara un default); lo stato si',
        // ed e' il caso normale: nessun filtro e' come si apre la pagina.
        ApiBaseResponsePaginated response = prenotazioneService.elenca(page, size, stato);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> dettaglioPrenotazione(Long id) {
        ApiBaseResponse response = prenotazioneService.dettaglio(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> creaPrenotazione(PrenotazioneRequest request) {
        ApiBaseResponse response = prenotazioneService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> confermaPrenotazione(Long id) {
        ApiBaseResponse response = prenotazioneService.conferma(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> checkInPrenotazione(
            Long id, PrenotazioneCheckInRequest request) {
        // Il corpo e' facoltativo nello spec: null vuol dire "scegli tu la camera", che
        // e' il caso normale. Chi vuole una stanza precisa la nomina.
        ApiBaseResponse response = prenotazioneService.checkIn(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> checkOutPrenotazione(Long id) {
        ApiBaseResponse response = prenotazioneService.checkOut(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> annullaPrenotazione(
            Long id, PrenotazioneAnnullamentoRequest request) {
        // Il corpo e' facoltativo nello spec, quindi qui arriva null quando chi annulla
        // non ha detto perche'. Non e' un caso da respingere: e' il caso comune.
        ApiBaseResponse response = prenotazioneService.annulla(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
