package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.CodificheMinisterialiApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.TipoCodifica;
import com.felixhotel.backend.dto.VoceCodifica;
import com.felixhotel.backend.service.VoceCodificaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Le tabelle di codifica pubblicate dal Ministero. Rotte, DTO e annotazioni
 * Swagger arrivano da {@link CodificheMinisterialiApi}, generata dallo spec.
 *
 * <p><b>Due livelli di permesso, come per le tariffe</b>: leggere e' di STAFF o
 * ADMIN perche' serve a chi compila una schedina al banco; importare e' solo degli
 * ADMIN. Niente di pubblico, ed e' la regola 20 applicata a una risorsa — non c'e'
 * nessuna ragione perche' un cliente scarichi l'elenco dei comuni italiani da qui.
 *
 * <p><b>Qui il ruolo basta</b>, come per le tariffe e al contrario degli ospiti:
 * il contenuto non sono dati di nessuno, sono codici pubblici che la Questura
 * pretende. Resta pero' che questa e' l'ennesima risorsa a fidarsi del solo ruolo
 * — vedi il gap sulla regola "ruolo <i>e</i> tipo dell'account", che questo branch
 * fa crescere di uno.
 */
@RestController
@RequiredArgsConstructor
public class VoceCodificaController implements CodificheMinisterialiApi {

    private final VoceCodificaService voceCodificaService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaCodifiche(
            TipoCodifica tipo, String filtro, Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi e il
        // @RequestParam generato lo applica prima di arrivare qui. Il filtro invece si',
        // ed e' il caso normale: nessun filtro vuol dire l'elenco intero.
        ApiBaseResponsePaginated response = voceCodificaService.elenca(tipo, filtro, page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> importaCodifiche(
            TipoCodifica tipo, List<VoceCodifica> voci) {
        ApiBaseResponse response = voceCodificaService.importa(tipo, voci);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
