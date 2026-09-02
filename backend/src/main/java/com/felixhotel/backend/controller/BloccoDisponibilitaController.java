package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.BlocchiApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.BloccoRequest;
import com.felixhotel.backend.service.BloccoDisponibilitaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Le camere non vendibili. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link BlocchiApi}, generata dallo spec.
 *
 * <p><b>Un livello di permesso solo: STAFF o ADMIN</b>, in lettura come in scrittura. E'
 * diverso dalle tariffe e dalle aliquote, dove leggere e' di tutti e due e scrivere solo
 * degli ADMIN, e la ragione e' che li' si decide un prezzo — una scelta commerciale —
 * mentre qui si constata che una camera non e' utilizzabile. Chiudere la stanza col
 * bagno rotto e' una decisione di turno, e chi sta al banco la deve poter prendere senza
 * cercare un amministratore.
 *
 * <p><b>Niente di pubblico</b>: quante camere restino lo dice gia' la ricerca di
 * disponibilita', mentre <i>perche'</i> non siano vendibili e' un fatto interno.
 *
 * <p><b>Qui il ruolo basta</b>, al contrario degli ospiti e delle schedine: da queste
 * rotte non passano dati di nessuno — una camera, due date e una nota.
 */
@RestController
@RequiredArgsConstructor
public class BloccoDisponibilitaController implements BlocchiApi {

    private final BloccoDisponibilitaService bloccoService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaBlocchi(
            Long tipologiaCameraId, Long cameraId, LocalDate da, LocalDate a,
            Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi. I
        // quattro filtri si', ed e' il caso normale — nessun filtro vuol dire tutto.
        ApiBaseResponsePaginated response =
                bloccoService.elenca(tipologiaCameraId, cameraId, da, a, page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaBlocco(BloccoRequest request) {
        ApiBaseResponse response = bloccoService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaBlocco(Long bloccoId) {
        ApiBaseResponse response = bloccoService.elimina(bloccoId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
