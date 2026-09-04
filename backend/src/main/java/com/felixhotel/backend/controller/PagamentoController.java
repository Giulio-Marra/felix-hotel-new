package com.felixhotel.backend.controller;

import com.felixhotel.backend.api.PagamentiApi;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.PagamentoRequest;
import com.felixhotel.backend.service.PagamentoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Il registro dei pagamenti di una prenotazione. Rotte, DTO e annotazioni Swagger
 * arrivano da {@link PagamentiApi}, generata dallo spec.
 *
 * <p><b>Le due rotte hanno permessi diversi, ed e' la cosa da guardare qui.</b> La
 * lettura non porta {@code @PreAuthorize}: la vede anche il <b>cliente sulla propria</b>
 * prenotazione — sono i suoi soldi — e chi puo' vedere cosa lo decide il Service, che e'
 * l'unico a sapere di chi sia quella prenotazione. La scrittura invece e' <b>STAFF o
 * ADMIN</b>: dichiarare di aver pagato non e' un gesto che possa fare chi paga.
 *
 * <p><b>Anche sulla scrittura il ruolo non basta</b>, come per gli ospiti e le schedine:
 * il Service pretende pure che l'account sia di tipo PERSONALE, perche' qui si scrive in
 * un registro di denaro e {@code @PreAuthorize} guarda il ruolo senza sapere niente della
 * tabella da cui l'account viene.
 */
@RestController
@RequiredArgsConstructor
public class PagamentoController implements PagamentiApi {

    private final PagamentoService pagamentoService;

    @Override
    public ResponseEntity<ApiBaseResponse> elencaPagamentiPrenotazione(Long id) {
        ApiBaseResponse response = pagamentoService.elenca(id);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Override
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    public ResponseEntity<ApiBaseResponse> registraPagamento(Long id, PagamentoRequest pagamentoRequest) {
        ApiBaseResponse response = pagamentoService.registra(id, pagamentoRequest);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
