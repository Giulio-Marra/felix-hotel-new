package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.TassaDiSoggiornoApi;
import com.felixhotel.backend.dto.AliquotaTassaSoggiornoRequest;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.service.AliquotaTassaSoggiornoService;
import com.felixhotel.backend.service.TassaSoggiornoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * La tassa di soggiorno: le aliquote che la definiscono e il conto di una
 * prenotazione. Rotte, DTO e annotazioni Swagger arrivano da
 * {@link TassaDiSoggiornoApi}, generata dallo spec OpenAPI.
 *
 * <p><b>Un Controller e due Service</b>, ed e' il primo caso del progetto. Il tag
 * dello spec e' uno perche' il dominio e' uno — chi cerca "tassa di soggiorno" in
 * Swagger vuole trovare tutto insieme — ma le due responsabilita' dietro sono
 * distinte davvero: {@link AliquotaTassaSoggiornoService} e' il CRUD di una
 * configurazione e ha una dipendenza sola, {@link TassaSoggiornoService} e' un
 * calcolo su una prenotazione e ne ha quattro. Fonderli avrebbe prodotto una
 * classe in cui tre dipendenze su quattro servono a un metodo solo.
 *
 * <p><b>Due domande di permesso diverse sulle rotte dello stesso Controller</b>, e
 * questa e' la cosa che vale la pena leggere due volte:
 * <ul>
 *   <li>sulle <b>aliquote</b> la domanda e' "che ruolo hai": leggere e' di STAFF o
 *       ADMIN, scrivere solo di ADMIN, e lo decide {@code @PreAuthorize} qui;</li>
 *   <li>sul <b>conto</b> la domanda e' "e' tua": non c'e' nessun
 *       {@code @PreAuthorize}, perche' un cliente ha il diritto di vedere la tassa
 *       della propria prenotazione e nessun'altra. La decisione sta nel Service,
 *       dove i dati per prenderla ci sono — e' la stessa forma di
 *       {@link PrenotazioneController}.</li>
 * </ul>
 * Non e' un'incoerenza: e' che le due rotte parlano di due cose diverse. Le
 * aliquote sono la trascrizione di un regolamento comunale, il conto e' un importo
 * che qualcuno paghera'.
 *
 * <p><b>Perche' il cliente vede il conto e non il registro degli ospiti</b>, che
 * pure e' la rotta piu' vicina: il registro contiene documenti d'identita' di terzi
 * — gli accompagnatori, che con l'account di chi ha prenotato non c'entrano niente
 * — mentre questo conto contiene nomi, importi e nient'altro. Il numero di
 * documento non compare, e la frase da rileggere prima di aggiungere qualunque
 * campo alla risposta sta su {@code TassaSoggiornoMapper}.
 */
@RestController
@RequiredArgsConstructor
public class TassaSoggiornoController implements TassaDiSoggiornoApi {

    private final AliquotaTassaSoggiornoService aliquotaTassaSoggiornoService;
    private final TassaSoggiornoService tassaSoggiornoService;

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponsePaginated> elencaAliquoteTassaSoggiorno(
            Integer page, Integer size) {
        // page e size non sono mai null: lo spec dichiara un default per entrambi e il
        // @RequestParam generato lo applica prima di arrivare qui.
        ApiBaseResponsePaginated response = aliquotaTassaSoggiornoService.elenca(page, size);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> creaAliquotaTassaSoggiorno(
            AliquotaTassaSoggiornoRequest request) {
        ApiBaseResponse response = aliquotaTassaSoggiornoService.crea(request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> aggiornaAliquotaTassaSoggiorno(
            Long aliquotaId, AliquotaTassaSoggiornoRequest request) {
        ApiBaseResponse response = aliquotaTassaSoggiornoService.aggiorna(aliquotaId, request);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiBaseResponse> eliminaAliquotaTassaSoggiorno(Long aliquotaId) {
        ApiBaseResponse response = aliquotaTassaSoggiornoService.elimina(aliquotaId);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * <b>Senza {@code @PreAuthorize}</b>, e non e' una dimenticanza: qui la domanda
     * non e' che ruolo abbia chi chiama ma se la prenotazione sia sua, e a quella
     * risponde il Service. Un'annotazione che pretendesse un ruolo terrebbe fuori
     * proprio il cliente che ha il diritto di vedere quanto paghera'.
     */
    @Override
    public ResponseEntity<ApiBaseResponse> calcolaTassaSoggiornoPrenotazione(Long id) {
        ApiBaseResponse response = tassaSoggiornoService.calcola(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
