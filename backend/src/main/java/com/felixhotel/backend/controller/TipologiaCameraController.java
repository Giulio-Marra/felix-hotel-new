package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.TipologieCameraApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.TipologiaCameraRequest;
import com.felixhotel.backend.service.TipologiaCameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogo delle tipologie di camera. Rotte, DTO e annotazioni Swagger
 * arrivano da {@link TipologieCameraApi}, generata dallo spec OpenAPI: qui si
 * implementa solo il corpo dei metodi, che chiamano il Service e rigirano la
 * busta usando come status HTTP quello che la busta gia' dichiara.
 *
 * <p><b>Lettura pubblica, scrittura agli ADMIN.</b> Le due cose si dichiarano
 * in due posti diversi, ed e' voluto:
 * <ul>
 *   <li>che le GET siano pubbliche lo dice {@code SecurityConfig}, dove stanno
 *       tutti i {@code permitAll} del progetto, elencati per path — averli in
 *       un elenco solo e' cio' che permette di rispondere alla domanda "cosa e'
 *       aperto?" leggendo un file;</li>
 *   <li>che le scritture vogliano il ruolo ADMIN lo dicono le
 *       {@code @PreAuthorize} qui sotto, accanto al metodo che proteggono.</li>
 * </ul>
 * Le scritture non compaiono fra i {@code permitAll}, quindi restano coperte
 * anche dal default {@code anyRequest().authenticated()}: chi arriva senza
 * token si ferma prima, con un 401. Il {@code @PreAuthorize} e' cio' che
 * distingue un cliente autenticato da un amministratore, e risponde 403.
 *
 * <p>Sono i primi {@code @PreAuthorize} di produzione del progetto: fino a qui
 * la strada del 403 era esercitata solo da un controller che esiste nei test.
 */
@RestController
@RequiredArgsConstructor
public class TipologiaCameraController implements TipologieCameraApi {

    private final TipologiaCameraService tipologiaCameraService;

    @Override
    public ResponseEntity<ApiBaseResponsePaginated> elencaTipologieCamera(Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi e il
        // @RequestParam generato lo applica prima di arrivare qui.
        ApiBaseResponsePaginated response = tipologiaCameraService.elenca(page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    public ResponseEntity<ApiBaseResponse> dettaglioTipologiaCamera(Long id) {
        ApiBaseResponse response = tipologiaCameraService.dettaglio(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaTipologiaCamera(TipologiaCameraRequest request) {
        ApiBaseResponse response = tipologiaCameraService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaTipologiaCamera(Long id, TipologiaCameraRequest request) {
        ApiBaseResponse response = tipologiaCameraService.aggiorna(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaTipologiaCamera(Long id) {
        ApiBaseResponse response = tipologiaCameraService.elimina(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
