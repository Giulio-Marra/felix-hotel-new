package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.DotazioneResponse;
import com.felixhotel.backend.entity.Dotazione;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -> DTO per {@link Dotazione}. Scritta a mano per scelta di
 * progetto (niente MapStruct); il DTO di destinazione e' invece generato dallo
 * spec OpenAPI.
 *
 * <p>Come gli altri mapper del progetto converte solo in uscita: riempire
 * l'entity con i dati della richiesta resta nel Service, dove si decide quali
 * campi sono modificabili da fuori.
 */
@Component
public class DotazioneMapper {

    public DotazioneResponse toResponse(Dotazione dotazione) {
        return new DotazioneResponse()
                .id(dotazione.getId())
                .nome(dotazione.getNome())
                .descrizione(dotazione.getDescrizione());
    }

    /**
     * Versione per l'endpoint di lista: stessa conversione, applicata a una
     * pagina di risultati. L'ordine e' quello in cui arrivano — lo decide la
     * query, che e' l'unico posto in cui puo' essere deciso senza rompere la
     * paginazione.
     */
    public List<DotazioneResponse> toResponseList(List<Dotazione> dotazioni) {
        return dotazioni.stream().map(this::toResponse).toList();
    }
}
