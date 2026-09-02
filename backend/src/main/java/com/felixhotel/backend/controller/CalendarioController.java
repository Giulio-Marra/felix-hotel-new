package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.CalendarioApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.service.CalendarioCameraService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * I calendari iCal delle camere. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link CalendarioApi}, generata dallo spec.
 *
 * <p><b>Due rotte con due permessi opposti, ed e' voluto.</b> Il feed e' <b>pubblico</b>
 * perche' lo scarica un servizio esterno che non ha modo di autenticarsi: a difenderlo
 * c'e' il token, che ha 256 bit. Generare l'indirizzo e' invece degli <b>ADMIN</b>, e non
 * dello STAFF: pubblicare la disponibilita' di una camera verso l'esterno non e'
 * un'operazione di turno, ed e' anche l'unico gesto che possa invalidare un feed che un
 * canale sta gia' leggendo.
 */
@RestController
@RequiredArgsConstructor
public class CalendarioController implements CalendarioApi {

    private final CalendarioCameraService calendarioService;

    /**
     * <b>L'unica rotta del progetto che non restituisce la busta standard.</b> Il tipo di
     * contenuto lo si dichiara qui a mano perche' un {@code text/calendar} non passa dal
     * convertitore JSON: quel che esce e' il file, byte per byte, come lo vuole chi legge.
     */
    @Override
    public ResponseEntity<String> feedCalendarioCamera(String token) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .body(calendarioService.feed(token));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> generaIndirizzoCalendario(Long id) {
        ApiBaseResponse response = calendarioService.generaIndirizzo(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
