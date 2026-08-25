package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.MediaCameraResponse;
import com.felixhotel.backend.entity.MediaCamera;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -&gt; DTO per {@link MediaCamera}. Scritta a mano per scelta
 * di progetto (niente MapStruct); il DTO di destinazione e' generato dallo spec.
 *
 * <p>E' il mapper piu' semplice del progetto e lo e' per una ragione, non per
 * caso: dei tre campi dell'entita' ne escono due. La <b>tipologia</b> non serve
 * — chi legge l'ha appena scritta nell'URL — e questo e' anche il motivo per cui
 * qui, unico caso, non c'e' nessun avvertimento sul chiamare il metodo dentro la
 * transazione: non si tocca nessuna relazione LAZY. L'<b>ordine</b> non esce per
 * scelta di contratto: la sequenza e' quella della lista, vedi
 * {@code MediaCameraResponse} nello spec.
 */
@Component
public class MediaCameraMapper {

    public MediaCameraResponse toResponse(MediaCamera media) {
        return new MediaCameraResponse()
                .id(media.getId())
                .url(media.getUrl());
    }

    /**
     * Versione per la galleria intera. <b>L'ordine della lista in ingresso viene
     * conservato</b>, ed e' tutto quel che porta l'informazione di sequenza: qui
     * non si ordina niente, perche' chi chiama ha gia' deciso — la query con il
     * suo {@code order by}, o il riordino con la sequenza appena applicata.
     */
    public List<MediaCameraResponse> toResponseList(List<MediaCamera> media) {
        return media.stream().map(this::toResponse).toList();
    }
}
