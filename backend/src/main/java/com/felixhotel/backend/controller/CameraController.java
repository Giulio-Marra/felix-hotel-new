package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.CamereApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.service.CameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventario delle camere fisiche. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link CamereApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Qui non c'e' niente di pubblico</b>, ed e' la differenza rispetto al
 * catalogo e alle dotazioni: nessun path di questa risorsa compare fra i
 * {@code permitAll} di {@code SecurityConfig}, quindi tutti ricadono nel default
 * {@code anyRequest().authenticated()} e chi arriva senza token si ferma con un
 * 401. Il cliente prenota una tipologia e non la stanza: numeri di camera e
 * stato operativo non sono dati che il sito mostra.
 *
 * <p><b>Tre livelli, non due.</b> E' il primo posto del progetto in cui il ruolo
 * {@code STAFF} protegge qualcosa invece di essere solo un valore in tabella:
 * <ul>
 *   <li><b>leggere</b> l'inventario e' lavoro di reception: STAFF o ADMIN;</li>
 *   <li><b>cambiare lo stato</b> di una camera e' l'operazione di ogni turno —
 *       liberata, da pulire, guasta — quindi STAFF o ADMIN: chiederle i
 *       privilegi di amministratore vorrebbe dire che non la fa nessuno;</li>
 *   <li><b>creare, modificare o eliminare</b> una camera cambia la struttura
 *       dell'albergo, non il suo stato: solo ADMIN.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class CameraController implements CamereApi {

    private final CameraService cameraService;

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaCamere(
            Integer page, Integer size, Long tipologiaCameraId, StatoCamera stato) {
        // page e size non sono mai null (lo spec dichiara un default); i due filtri
        // invece si', ed e' il caso normale: nessun filtro e' come si apre la pagina.
        ApiBaseResponsePaginated response = cameraService.elenca(page, size, tipologiaCameraId, stato);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<ApiBaseResponse> dettaglioCamera(Long id) {
        ApiBaseResponse response = cameraService.dettaglio(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaCamera(CameraRequest request) {
        ApiBaseResponse response = cameraService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaCamera(Long id, CameraRequest request) {
        ApiBaseResponse response = cameraService.aggiorna(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaCamera(Long id) {
        ApiBaseResponse response = cameraService.elimina(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<ApiBaseResponse> impostaStatoCamera(Long id, CameraStatoRequest request) {
        ApiBaseResponse response = cameraService.impostaStato(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
