package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.BloccoResponse;
import com.felixhotel.backend.dto.OrigineBlocco;
import com.felixhotel.backend.entity.BloccoDisponibilita;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -&gt; DTO per {@link BloccoDisponibilita}. Scritta a mano per scelta
 * di progetto (niente MapStruct).
 *
 * <p><b>Tipologia e camera escono in sintesi</b>, riusando i mapper che le possiedono:
 * chi guarda l'elenco delle indisponibilita' vuole riconoscerle, non leggerne la scheda.
 *
 * <p><b>Tocca due relazioni LAZY</b>, quindi va chiamato dentro la transazione (regola
 * 15) — e la camera puo' essere nulla, che e' il caso normale di un blocco anonimo.
 */
@Component
@RequiredArgsConstructor
public class BloccoDisponibilitaMapper {

    private final TipologiaCameraMapper tipologiaCameraMapper;
    private final CameraMapper cameraMapper;

    public BloccoResponse toResponse(BloccoDisponibilita blocco) {
        return new BloccoResponse()
                .id(blocco.getId())
                .tipologiaCamera(tipologiaCameraMapper.toSintesi(blocco.getTipologiaCamera()))
                // Null per i blocchi che dicono "una unita' qualsiasi": non c'e' nessuna
                // camera da mostrare, ed e' un'informazione e non un dato mancante.
                .camera(blocco.getCamera() == null ? null : cameraMapper.toSintesi(blocco.getCamera()))
                .dataInizio(blocco.getDataInizio())
                .dataFine(blocco.getDataFine())
                .origine(OrigineBlocco.fromValue(blocco.getOrigine().name()))
                .note(blocco.getNote());
    }

    /** Versione per l'elenco. L'ordine della lista in ingresso viene conservato. */
    public List<BloccoResponse> toResponseList(List<BloccoDisponibilita> blocchi) {
        return blocchi.stream().map(this::toResponse).toList();
    }
}
