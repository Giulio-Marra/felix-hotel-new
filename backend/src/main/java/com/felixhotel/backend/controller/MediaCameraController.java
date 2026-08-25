package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.MediaCameraApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.service.MediaCameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Galleria fotografica delle tipologie di camera. Rotte, DTO e annotazioni
 * Swagger arrivano da {@link MediaCameraApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Un controller suo, pur essendo una sottorisorsa della tipologia.</b>
 * Le foto vivono sotto {@code /api/tipologie-camera/{id}/...} ma non stanno in
 * {@link TipologiaCameraController}: quella classe e il suo test di integrazione
 * coprono gia' la risorsa e il sottopercorso delle dotazioni, e l'IT era arrivato
 * a settecento righe. Il tag {@code MediaCamera} nello spec genera
 * un'interfaccia separata, e da li' discende tutto il resto — controller,
 * service e test propri. Il percorso resta annidato perche' descrive dove la
 * risorsa vive; il codice si divide per quanto ce n'e' da leggere in una volta.
 *
 * <p><b>I permessi ricalcano il catalogo, non l'inventario.</b> La lettura e'
 * pubblica — sono le fotografie che il sito mostra a chi sta scegliendo dove
 * dormire, e tenerle dietro a un login vorrebbe dire un catalogo senza immagini
 * — mentre aggiungere, togliere e riordinare sono da <b>ADMIN</b>: decidere che
 * faccia ha una tipologia sul sito e' pubblicare, non un'operazione di turno, e
 * per questo non e' aperta allo STAFF come invece lo e' il cambio di stato di
 * una camera.
 *
 * <p>Il {@code permitAll} della sola GET sta in {@code SecurityConfig} ed e'
 * elencato per esteso ({@code /api/tipologie-camera/*}{@code /media}): e' il
 * primo sottopercorso pubblico del progetto, cioe' il caso che quella
 * configurazione aveva previsto per iscritto quando ha rifiutato un
 * {@code /**} comodo.
 */
@RestController
@RequiredArgsConstructor
public class MediaCameraController implements MediaCameraApi {

    private final MediaCameraService mediaCameraService;

    @Override
    public ResponseEntity<ApiBaseResponse> elencaMediaTipologiaCamera(Long id) {
        ApiBaseResponse response = mediaCameraService.elenca(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiungiMediaTipologiaCamera(Long id, MediaCameraRequest request) {
        ApiBaseResponse response = mediaCameraService.aggiungi(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaMediaTipologiaCamera(Long id, Long mediaId) {
        ApiBaseResponse response = mediaCameraService.elimina(id, mediaId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> riordinaMediaTipologiaCamera(
            Long id, MediaCameraOrdineRequest request) {
        ApiBaseResponse response = mediaCameraService.riordina(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
