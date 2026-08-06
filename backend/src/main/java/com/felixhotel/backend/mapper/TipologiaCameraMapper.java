package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.TipologiaCameraResponse;
import com.felixhotel.backend.entity.TipologiaCamera;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -> DTO per {@link TipologiaCamera}. Scritta a mano per
 * scelta di progetto (niente MapStruct); il DTO di destinazione e' invece
 * generato dallo spec OpenAPI.
 *
 * <p>Qui si converte solo in uscita. Il senso opposto — riempire l'entity con
 * i dati della richiesta — resta nel Service, come gia' fa {@code AuthServiceImpl}
 * con {@code Utente}: e' li' che si decide quali campi sono modificabili da
 * fuori, e non e' una conversione ma una regola.
 */
@Component
public class TipologiaCameraMapper {

    public TipologiaCameraResponse toResponse(TipologiaCamera tipologia) {
        return new TipologiaCameraResponse()
                .id(tipologia.getId())
                .nome(tipologia.getNome())
                .descrizione(tipologia.getDescrizione())
                .capienzaMax(tipologia.getCapienzaMax())
                .prezzoNotte(tipologia.getPrezzoNotte());
    }

    /** Versione per gli endpoint di lista: stessa conversione, applicata a una pagina di risultati. */
    public List<TipologiaCameraResponse> toResponseList(List<TipologiaCamera> tipologie) {
        return tipologie.stream().map(this::toResponse).toList();
    }
}
