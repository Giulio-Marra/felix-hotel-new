package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.PrenotazioniApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.StatoPrenotazione;
import com.felixhotel.backend.service.PrenotazioneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prenotazioni. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link PrenotazioniApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Qui non c'e' nessun {@code @PreAuthorize}</b>, ed e' la differenza
 * rispetto a tutti gli altri Controller del progetto — non una dimenticanza.
 * Nessun path delle prenotazioni compare fra i {@code permitAll} di
 * {@code SecurityConfig}, quindi tutti ricadono nel default
 * {@code anyRequest().authenticated()} e chi arriva senza token si ferma con un
 * 401: il livello "serve un account" e' gia' garantito, e sopra a quello non
 * c'e' nessuna regola che dipenda dal <b>solo</b> ruolo.
 *
 * <p>Il motivo e' che qui la domanda non e' "che ruolo hai" ma <b>"e' tua?"</b>,
 * e a quella un'espressione SpEL non sa rispondere senza aver letto la riga. Il
 * ruolo entra comunque nella decisione — STAFF e ADMIN vedono e toccano tutto —
 * ma sempre in coppia con l'intestatario della prenotazione, quindi la
 * decisione sta tutta nel Service, dove i dati ci sono. Spezzarla in due,
 * meta' qui e meta' la', vorrebbe dire leggerne solo una meta' quando si va a
 * verificare com'e' protetta.
 *
 * <p>Il Controller resta quindi quello che e' sempre: chiama il Service e
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
    public ResponseEntity<ApiBaseResponse> annullaPrenotazione(
            Long id, PrenotazioneAnnullamentoRequest request) {
        // Il corpo e' facoltativo nello spec, quindi qui arriva null quando chi annulla
        // non ha detto perche'. Non e' un caso da respingere: e' il caso comune.
        ApiBaseResponse response = prenotazioneService.annulla(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
