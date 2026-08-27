package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.DisponibilitaApi;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.service.DisponibilitaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ricerca di disponibilita'. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link DisponibilitaApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Niente {@code @PreAuthorize}</b>, ma per la ragione opposta a quella
 * delle prenotazioni: li' mancava perche' la domanda era "e' tua?", qui perche'
 * non c'e' nessuna domanda da fare. La rotta e' fra i {@code permitAll} di
 * {@code SecurityConfig} (regola 14), come il catalogo e la galleria: e' quello
 * che un visitatore chiede prima di avere un account.
 */
@RestController
@RequiredArgsConstructor
public class DisponibilitaController implements DisponibilitaApi {

    private final DisponibilitaService disponibilitaService;

    @Override
    public ResponseEntity<ApiBaseResponsePaginated> cercaDisponibilita(
            LocalDate dataCheckIn, LocalDate dataCheckOut, Integer numeroOspiti,
            BigDecimal prezzoMinimo, BigDecimal prezzoMassimo, Integer page, Integer size) {
        // Le due date non sono mai null (lo spec le dichiara obbligatorie); i tre
        // filtri si', ed e' il caso normale — nessun filtro e' come si apre la ricerca.
        // page e size hanno un default nello spec.
        ApiBaseResponsePaginated response = disponibilitaService.cerca(
                dataCheckIn, dataCheckOut, numeroOspiti, prezzoMinimo, prezzoMassimo, page, size);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
