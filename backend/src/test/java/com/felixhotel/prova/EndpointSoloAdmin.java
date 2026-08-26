package com.felixhotel.prova;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint riservato agli ADMIN che esiste <b>solo nei test</b>: serve a
 * esercitare {@code @PreAuthorize} sul percorso in cui l'accesso viene negato.
 *
 * <p>Senza, la scelta di lasciare l'{@code AccessDeniedException} alla filter
 * chain (vedi {@code GlobalExceptionHandler.handleAccessoNegato}) resterebbe
 * senza rete: un domani, il primo endpoint riservato agli ADMIN scoprirebbe solo
 * a runtime che un permesso negato risponde 500 invece di 403.
 *
 * <p>Come gli altri endpoint di questo package, esiste solo nei contesti che lo
 * chiedono: vedi {@link EndpointDiProva} per il come e il perche'.
 */
@RestController
public class EndpointSoloAdmin {

    /** Il prefisso la tiene fuori da /api, dove vive il dominio vero. */
    public static final String PATH = "/test-only/solo-admin";

    @GetMapping(PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> soloAdmin() {
        // Il corpo non conta: quello che si verifica e' chi ci arriva e cosa
        // succede a chi non ci arriva.
        return ResponseEntity.ok("ok");
    }
}
