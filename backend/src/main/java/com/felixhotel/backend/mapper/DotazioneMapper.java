package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.DotazioneResponse;
import com.felixhotel.backend.entity.Dotazione;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
     * La conversione applicata a una collezione, nell'ordine in cui la si
     * percorre. Per l'endpoint di lista quell'ordine lo decide la query, che e'
     * l'unico posto in cui puo' essere deciso senza rompere la paginazione.
     *
     * <p>Prende una {@link Collection} e non una {@link List} perche' e' anche
     * il pezzo finale di {@link #toResponseListOrdinata}: la conversione e' la
     * stessa e l'ordine e' l'unica cosa che cambia fra i due casi — scriverla
     * una volta sola vuol dire che resta l'unica cosa che cambia.
     */
    public List<DotazioneResponse> toResponseList(Collection<Dotazione> dotazioni) {
        return dotazioni.stream().map(this::toResponse).toList();
    }

    /**
     * Versione per le dotazioni di una tipologia di camera, che sono un
     * {@link Set} e quindi non hanno un ordine proprio.
     *
     * <p>L'ordine va deciso qui e non dalla query: senza, la stessa scheda di
     * camera elencherebbe le proprie dotazioni in una sequenza diversa da una
     * lettura all'altra, e il frontend le mostrerebbe a caso. Il confronto
     * ignora le maiuscole per la stessa ragione per cui le ignora il vincolo di
     * unicita': "Wi-Fi" e "aria condizionata" vanno ordinate come le leggerebbe
     * una persona, non secondo il codice dei caratteri.
     */
    public List<DotazioneResponse> toResponseListOrdinata(Set<Dotazione> dotazioni) {
        return toResponseList(dotazioni.stream()
                .sorted(Comparator.comparing(Dotazione::getNome, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }
}
