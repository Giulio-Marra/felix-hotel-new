package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.SorgentiCalendarioApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.SorgenteCalendarioRequest;
import com.felixhotel.backend.service.SorgenteCalendarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * I calendari esterni che leggiamo. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link SorgentiCalendarioApi}, generata dallo spec.
 *
 * <p><b>Un tag suo e non quello di {@link CalendarioController}</b>, che pure racconta
 * l'altra meta' della stessa sincronia. La ragione e' meccanica prima che estetica: con
 * {@code useTags}, un tag genera <i>una</i> interfaccia, e due {@code @RestController} che
 * la implementassero registrerebbero due volte le stesse rotte — l'applicazione non
 * partirebbe affatto. Che poi sia anche la divisione giusta e' un di piu': quello pubblica
 * un feed, questo ne legge di altrui.
 *
 * <p><b>Leggere e' di STAFF e ADMIN, scrivere solo degli ADMIN</b>, ed e' la stessa
 * divisione delle tariffe e delle aliquote: chi sta al banco deve poter capire perche' una
 * camera risulta occupata — e' il suo lavoro — mentre decidere da quali canali ci si fa
 * dire cosa e' vendibile non e' una decisione di turno. E' anche coerente con il feed in
 * uscita, che e' degli ADMIN per la stessa ragione.
 *
 * <p><b>La sincronizzazione manuale e' degli ADMIN</b> e non dello STAFF, pur essendo
 * un'operazione che non cambia nessuna configurazione: fa partire richieste verso indirizzi
 * che qualcun altro ha scelto, e chi non puo' configurarli non ha ragione di poterli
 * interrogare a comando.
 */
@RestController
@RequiredArgsConstructor
public class SorgenteCalendarioController implements SorgentiCalendarioApi {

    private final SorgenteCalendarioService sorgenteService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaSorgentiCalendario(
            Long cameraId, Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi. Il
        // filtro sulla camera si', ed e' il caso normale — nessun filtro vuol dire tutte.
        ApiBaseResponsePaginated response = sorgenteService.elenca(cameraId, page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaSorgenteCalendario(SorgenteCalendarioRequest request) {
        ApiBaseResponse response = sorgenteService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaSorgenteCalendario(Long sorgenteId) {
        ApiBaseResponse response = sorgenteService.elimina(sorgenteId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * <b>200 anche quando qualche canale e' andato storto</b>, e non e' una svista: il giro
     * e' stato fatto, ed e' il contenuto a dire cosa ha trovato. Un 500 direbbe che la
     * richiesta non e' stata eseguita, che e' falso, e nasconderebbe le sorgenti che invece
     * hanno funzionato.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> sincronizzaSorgentiCalendario() {
        ApiBaseResponse response = sorgenteService.sincronizzaTutte();
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
