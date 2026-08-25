package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.TipologiaCameraResponse;
import com.felixhotel.backend.entity.TipologiaCamera;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class TipologiaCameraMapper {

    /**
     * Le dotazioni le converte il loro mapper, invece di ricopiarne i tre campi
     * qui: sono la stessa conversione che fa l'endpoint {@code /api/dotazioni},
     * e averla in due posti vorrebbe dire che un campo aggiunto domani compare
     * in uno solo dei due.
     */
    private final DotazioneMapper dotazioneMapper;

    /**
     * <b>Va chiamato dentro la transazione</b> che ha caricato l'entity: la
     * collezione delle dotazioni e' LAZY e il progetto ha
     * {@code open-in-view=false}, quindi fuori dalla transazione sarebbe una
     * {@code LazyInitializationException}. Non e' un vincolo nuovo — tutti i
     * Service mappano gia' dentro il proprio metodo transazionale — ma prima di
     * questo campo non c'era modo di accorgersi di averlo violato.
     */
    public TipologiaCameraResponse toResponse(TipologiaCamera tipologia) {
        return new TipologiaCameraResponse()
                .id(tipologia.getId())
                .nome(tipologia.getNome())
                .descrizione(tipologia.getDescrizione())
                .capienzaMax(tipologia.getCapienzaMax())
                .prezzoNotte(tipologia.getPrezzoNotte())
                .dotazioni(dotazioneMapper.toResponseListOrdinata(tipologia.getDotazioni()));
    }

    /** Versione per gli endpoint di lista: stessa conversione, applicata a una pagina di risultati. */
    public List<TipologiaCameraResponse> toResponseList(List<TipologiaCamera> tipologie) {
        return tipologie.stream().map(this::toResponse).toList();
    }
}
