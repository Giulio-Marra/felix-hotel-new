package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.StaffApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffAggiornamentoRequest;
import com.felixhotel.backend.dto.StaffAttivazioneRequest;
import com.felixhotel.backend.dto.StaffPasswordRequest;
import com.felixhotel.backend.dto.StaffRequest;
import com.felixhotel.backend.service.StaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account del personale. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link StaffApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Un livello solo: ADMIN.</b> E' la differenza rispetto alle camere, che
 * distinguono fra il lavoro di turno e le modifiche alla struttura: qui non c'e'
 * niente che assomigli al lavoro di turno. Leggere l'elenco vuol dire vedere
 * nomi, recapiti e privilegi dei colleghi; scrivere vuol dire distribuire quei
 * privilegi. Nessuna delle due e' un'operazione da reception, quindi non c'e'
 * nessun {@code hasAnyRole} in questa classe.
 *
 * <p><b>Niente e' pubblico</b>: nessun path di questa risorsa compare fra i
 * {@code permitAll} di {@code SecurityConfig}, quindi tutti ricadono nel default
 * {@code anyRequest().authenticated()} e chi arriva senza token si ferma con un
 * 401 prima ancora del controllo di ruolo.
 *
 * <p><b>Non c'e' un DELETE</b>, e la sua assenza e' una decisione: il personale
 * e' referenziato dalle prenotazioni che ha gestito, quindi chi non lavora piu'
 * qui si disattiva con {@code PUT /api/staff/{id}/attivazione}. Vedi
 * {@code StaffServiceImpl.impostaAttivazione}.
 */
@RestController
@RequiredArgsConstructor
public class StaffController implements StaffApi {

    private final StaffService staffService;

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaStaff(
            Integer page, Integer size, RuoloStaff ruolo, Boolean attivo) {
        // page e size non sono mai null (lo spec dichiara un default); i due filtri
        // invece si', ed e' il caso normale: nessun filtro e' come si apre la pagina.
        ApiBaseResponsePaginated response = staffService.elenca(page, size, ruolo, attivo);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> dettaglioStaff(Long id) {
        ApiBaseResponse response = staffService.dettaglio(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaStaff(StaffRequest request) {
        ApiBaseResponse response = staffService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaStaff(Long id, StaffAggiornamentoRequest request) {
        ApiBaseResponse response = staffService.aggiorna(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> impostaAttivazioneStaff(
            Long id, StaffAttivazioneRequest request) {
        ApiBaseResponse response = staffService.impostaAttivazione(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> impostaPasswordStaff(Long id, StaffPasswordRequest request) {
        ApiBaseResponse response = staffService.impostaPassword(id, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
