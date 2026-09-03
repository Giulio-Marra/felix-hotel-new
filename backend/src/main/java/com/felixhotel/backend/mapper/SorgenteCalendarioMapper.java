package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.EsitoSincronizzazione;
import com.felixhotel.backend.dto.SorgenteCalendarioResponse;
import com.felixhotel.backend.entity.SorgenteCalendario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Conversione Entity -&gt; DTO per {@link SorgenteCalendario}. Scritta a mano per scelta di
 * progetto (niente MapStruct).
 *
 * <p><b>L'indirizzo esce per esteso</b>, al contrario del token del calendario in uscita
 * che non si rimostra mai. Non e' un'incoerenza: quel token lo emettiamo noi e chi lo
 * perde lo rigenera, questo indirizzo lo ha incollato una persona e deve poterlo
 * rileggere per correggerlo. Chi puo' vederlo poteva gia' vedere la stessa occupazione da
 * {@code GET /api/blocchi}.
 *
 * <p><b>Tocca due relazioni LAZY</b> — la camera e la sua tipologia — quindi va chiamato
 * dentro la transazione (regola 15). Le query del repository le caricano con
 * {@code EntityGraph}.
 */
@Component
@RequiredArgsConstructor
public class SorgenteCalendarioMapper {

    private final CameraMapper cameraMapper;

    public SorgenteCalendarioResponse toResponse(SorgenteCalendario sorgente) {
        return new SorgenteCalendarioResponse()
                .id(sorgente.getId())
                .camera(cameraMapper.toSintesi(sorgente.getCamera()))
                .nome(sorgente.getNome())
                .url(sorgente.getUrl())
                .ultimaSincronizzazione(toOffset(sorgente.getUltimaSincronizzazione()))
                // Null finche' non e' mai stata sincronizzata, che e' l'unico modo di
                // distinguere "non e' ancora partita" da "e' andata bene".
                .ultimoEsito(sorgente.getUltimoEsito() == null
                        ? null
                        : EsitoSincronizzazione.fromValue(sorgente.getUltimoEsito().name()))
                .ultimoMessaggio(sorgente.getUltimoMessaggio());
    }

    /** Versione per l'elenco. L'ordine della lista in ingresso viene conservato. */
    public List<SorgenteCalendarioResponse> toResponseList(List<SorgenteCalendario> sorgenti) {
        return sorgenti.stream().map(this::toResponse).toList();
    }

    /**
     * Da {@code LocalDateTime} a {@code OffsetDateTime}, come in
     * {@code PrenotazioneMapper}: l'offset e' quello di sistema e non UTC fisso, perche' in
     * database la colonna e' un {@code TIMESTAMP} senza fuso — cioe' l'ora locale della
     * macchina che l'ha scritta — e dichiararla UTC la sposterebbe senza cambiarne il
     * valore.
     *
     * <p>Accetta null perche' una sorgente appena registrata non e' mai stata letta.
     */
    private OffsetDateTime toOffset(LocalDateTime istante) {
        if (istante == null) {
            return null;
        }

        return istante.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
