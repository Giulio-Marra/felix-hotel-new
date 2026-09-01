package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.TariffeApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.service.PeriodoTariffarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Il calendario dei prezzi di una tipologia di camera. Rotte, DTO e annotazioni
 * Swagger arrivano da {@link TariffeApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Un controller suo, pur essendo una sottorisorsa della tipologia</b>, per
 * la stessa ragione gia' valsa per la galleria fotografica e per gli ospiti: il
 * tag {@code Tariffe} nello spec genera un'interfaccia separata, e
 * {@link TipologiaCameraController} copre gia' il CRUD del catalogo piu'
 * l'endpoint delle dotazioni. Il percorso resta annidato perche' descrive dove
 * la risorsa vive; il codice si divide per quanto ce n'e' da leggere in una
 * volta.
 *
 * <p><b>Due livelli di permesso, come per le camere fisiche</b>, e non uno solo
 * come per le foto. Leggere e' di STAFF o ADMIN: chi sta al banco deve poter
 * dire al telefono quanto costa una settimana di agosto, ed e' il lavoro di ogni
 * turno — la stessa ragione per cui lo STAFF legge l'inventario. Scrivere e'
 * solo degli ADMIN: decidere il prezzo di agosto e' una scelta commerciale, non
 * un'operazione di turno, ed e' lo stesso confine che tiene la pubblicazione
 * delle foto agli ADMIN.
 *
 * <p><b>Niente di pubblico</b>, al contrario del catalogo e della galleria. La
 * domanda del cliente sui prezzi ha gia' il suo endpoint —
 * {@code GET /api/disponibilita}, che risponde per le date che gli interessano —
 * e il listino intero e' un documento commerciale. E' la regola 20 applicata a
 * una risorsa: il default e' chiuso, e ad aprire e' una ragione, non l'assenza
 * di una ragione contraria.
 *
 * <p><b>Qui il ruolo basta davvero</b>, ed e' la differenza da
 * {@link OspiteController}, dove {@code @PreAuthorize} non basta e il Service
 * pretende anche il tipo dell'account. La' il contenuto sono documenti
 * d'identita' di terzi e serve la certezza che dietro il token ci sia davvero
 * una persona del personale; qui sono prezzi di listino, e chi ha il ruolo di
 * ADMIN ha per definizione il diritto di deciderli. Resta pero' che questa
 * risorsa e' l'ennesima a fidarsi del solo ruolo — vedi il gap aperto sulla
 * regola "ruolo <i>e</i> tipo dell'account", che questo branch fa crescere di
 * uno.
 */
@RestController
@RequiredArgsConstructor
public class PeriodoTariffarioController implements TariffeApi {

    private final PeriodoTariffarioService periodoTariffarioService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaTariffeTipologiaCamera(
            Long id, Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi e il
        // @RequestParam generato lo applica prima di arrivare qui.
        ApiBaseResponsePaginated response = periodoTariffarioService.elenca(id, page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaTariffaTipologiaCamera(
            Long id, PeriodoTariffarioRequest request) {
        ApiBaseResponse response = periodoTariffarioService.crea(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaTariffaTipologiaCamera(
            Long id, Long tariffaId, PeriodoTariffarioRequest request) {
        ApiBaseResponse response = periodoTariffarioService.aggiorna(id, tariffaId, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaTariffaTipologiaCamera(Long id, Long tariffaId) {
        ApiBaseResponse response = periodoTariffarioService.elimina(id, tariffaId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
