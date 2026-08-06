package com.felixhotel.backend.support;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint che esiste <b>solo nei test</b> (sta in {@code src/test/java}, quindi
 * non finisce in nessun artefatto): serve a esercitare {@code @PreAuthorize},
 * che oggi nessun endpoint di produzione usa ancora.
 *
 * <p>Senza, la scelta di lasciare l'{@code AccessDeniedException} alla filter
 * chain (vedi {@code GlobalExceptionHandler.handleAccessoNegato}) resterebbe
 * senza rete: un domani, il primo endpoint riservato agli ADMIN scoprirebbe
 * solo a runtime che un permesso negato risponde 500 invece di 403. Questo
 * controller anticipa quel momento a oggi.
 *
 * <p>Viene raccolto dal component scan perche' sta nel package
 * {@code com.felixhotel.backend}, che e' quello scansionato da
 * {@code BackendApplication}, e le classi di test sono sul classpath dei test.
 * Non serve importarlo: gli IT lo trovano gia' nel contesto, e cosi' non se ne
 * crea uno secondo (che vorrebbe dire un secondo container Postgres).
 */
@RestController
public class SoloAdminTestController {

    /** Rotta usata dagli IT sulla sicurezza. Il prefisso la tiene fuori da /api. */
    public static final String PATH = "/test-only/solo-admin";

    @GetMapping(PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> soloAdmin() {
        // Il corpo non conta: quello che si verifica e' chi ci arriva e cosa
        // succede a chi non ci arriva.
        return ResponseEntity.ok("ok");
    }
}
