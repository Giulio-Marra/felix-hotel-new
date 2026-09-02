package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.AlloggiatiWebApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.service.AlloggiatiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * L'export delle schedine per il portale Alloggiati Web. Rotte, DTO e annotazioni
 * Swagger arrivano da {@link AlloggiatiWebApi}, generata dallo spec.
 *
 * <p><b>STAFF o ADMIN</b>, come il registro degli ospiti da cui il file nasce: e' il
 * gesto di ogni mattina alla reception, e il contenuto sono documenti d'identita' —
 * un cliente non ci arriva nemmeno per la propria prenotazione. Niente di pubblico.
 *
 * <p><b>Qui il ruolo non basta</b>, come per gli ospiti e al contrario delle
 * tariffe: il Service pretende anche che l'account sia di tipo PERSONALE. E' la
 * seconda risorsa del progetto a farlo, ed e' per lo stesso motivo — quel che passa
 * di qui sono dati personali di terzi.
 */
@RestController
@RequiredArgsConstructor
public class AlloggiatiController implements AlloggiatiWebApi {

    private final AlloggiatiService alloggiatiService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> esportaSchedineAlloggiati(LocalDate data) {
        ApiBaseResponse response = alloggiatiService.esportaSchedine(data);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
