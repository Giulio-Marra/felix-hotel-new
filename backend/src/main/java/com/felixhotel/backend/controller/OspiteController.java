package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.OspitiApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.service.OspiteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gli ospiti registrati su una prenotazione. Rotte, DTO e annotazioni Swagger
 * arrivano da {@link OspitiApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Un controller suo, pur essendo una sottorisorsa della prenotazione</b>,
 * per la stessa ragione gia' valsa per la galleria fotografica: il tag
 * {@code Ospiti} nello spec genera un'interfaccia separata, e
 * {@link PrenotazioneController} con il suo test di integrazione copre gia' sei
 * operazioni e un ciclo di vita intero. Il percorso resta annidato perche'
 * descrive dove la risorsa vive; il codice si divide per quanto ce n'e' da
 * leggere in una volta.
 *
 * <p><b>Un livello di permesso solo: STAFF o ADMIN.</b> Non c'e' niente di
 * pubblico e non c'e' niente per il cliente, nemmeno sulla propria prenotazione
 * — che e' la differenza da tutte le altre rotte sotto
 * {@code /api/prenotazioni}, dove la domanda e' "e' tua?" e un USER passa. Qui
 * la domanda torna a essere "che ruolo hai", come per il check-in: registrare un
 * documento e' un adempimento di legge che si fa al banco, con il documento in
 * mano, e il contenuto sono dati personali di <i>terzi</i> — gli accompagnatori,
 * che con l'account non c'entrano niente. Un cliente prende <b>403</b>.
 *
 * <p><b>L'annotazione pero' non basta, ed e' scritto anche qui perche' e'
 * esattamente il posto in cui verrebbe da crederlo.</b> {@code @PreAuthorize}
 * verifica il ruolo; che l'account sia davvero del personale — cioe' che stia
 * nella tabella giusta — lo verifica il Service con
 * {@code ChiamanteCorrente.personale()}. Le due meta' della stessa regola stanno
 * in due posti perche' vivono in due mondi diversi: il ruolo e' nelle
 * authority, il tipo e' nel principal, e un'espressione SpEL il secondo non lo
 * sa leggere.
 */
@RestController
@RequiredArgsConstructor
public class OspiteController implements OspitiApi {

    private final OspiteService ospiteService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> elencaOspitiPrenotazione(Long id) {
        ApiBaseResponse response = ospiteService.elenca(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiungiOspitePrenotazione(Long id, OspiteRequest request) {
        ApiBaseResponse response = ospiteService.aggiungi(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaOspitePrenotazione(
            Long id, Long ospiteId, OspiteRequest request) {
        ApiBaseResponse response = ospiteService.aggiorna(id, ospiteId, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaOspitePrenotazione(Long id, Long ospiteId) {
        ApiBaseResponse response = ospiteService.elimina(id, ospiteId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
