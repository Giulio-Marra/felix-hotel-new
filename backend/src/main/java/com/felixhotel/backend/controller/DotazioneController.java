package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.DotazioniApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.service.DotazioneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Elenco delle dotazioni assegnabili alle tipologie di camera. Rotte, DTO e
 * annotazioni Swagger arrivano da {@link DotazioniApi}, generata dallo spec
 * OpenAPI: qui si implementa solo il corpo dei metodi, che chiamano il Service
 * e rigirano la busta usando come status HTTP quello che la busta gia' dichiara.
 *
 * <p><b>Lettura pubblica, scrittura agli ADMIN</b>, con la stessa divisione del
 * catalogo: che le GET siano pubbliche lo dice {@code SecurityConfig}, dove
 * stanno tutti i {@code permitAll} del progetto elencati per path; che le
 * scritture vogliano il ruolo ADMIN lo dicono le {@code @PreAuthorize} qui
 * sotto, accanto al metodo che proteggono.
 */
@RestController
@RequiredArgsConstructor
public class DotazioneController implements DotazioniApi {

    private final DotazioneService dotazioneService;

    @Override
    public ResponseEntity<ApiBaseResponsePaginated> elencaDotazioni(Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi e il
        // @RequestParam generato lo applica prima di arrivare qui.
        ApiBaseResponsePaginated response = dotazioneService.elenca(page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> dettaglioDotazione(Long id) {
        ApiBaseResponse response = dotazioneService.dettaglio(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaDotazione(DotazioneRequest request) {
        ApiBaseResponse response = dotazioneService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaDotazione(Long id, DotazioneRequest request) {
        ApiBaseResponse response = dotazioneService.aggiorna(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaDotazione(Long id) {
        ApiBaseResponse response = dotazioneService.elimina(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
