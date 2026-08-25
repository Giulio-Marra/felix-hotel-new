package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.CameraResponse;
// Lo stato esiste in due enum omonimi: quello di dominio e quello generato dallo
// spec. Qui si importa il secondo e si scrive per esteso il primo — uno dei due
// deve restare qualificato comunque, e in questa classe il DTO ricorre di piu'.
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.entity.Camera;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -> DTO per {@link Camera}. Scritta a mano per scelta di
 * progetto (niente MapStruct); i DTO di destinazione sono generati dallo spec.
 *
 * <p>La tipologia della camera passa dal suo mapper, in versione
 * {@code Sintesi}: vedi {@link TipologiaCameraMapper#toSintesi} per il perche'
 * non sia la risposta completa.
 */
@Component
@RequiredArgsConstructor
public class CameraMapper {

    private final TipologiaCameraMapper tipologiaCameraMapper;

    /**
     * <b>Va chiamato dentro la transazione</b> che ha caricato l'entity: la
     * tipologia e' una relazione LAZY e il progetto ha
     * {@code open-in-view=false}. Le query del repository la caricano gia' con
     * {@code @EntityGraph}, quindi nella pratica non c'e' niente da inizializzare
     * — ma se un domani nascesse una query senza quel fetch, il guasto
     * comparirebbe qui.
     */
    public CameraResponse toResponse(Camera camera) {
        return new CameraResponse()
                .id(camera.getId())
                .numero(camera.getNumero())
                .piano(camera.getPiano())
                .stato(toStatoDto(camera.getStato()))
                .tipologia(tipologiaCameraMapper.toSintesi(camera.getTipologiaCamera()));
    }

    /** Versione per l'endpoint di lista: stessa conversione, applicata a una pagina di risultati. */
    public List<CameraResponse> toResponseList(List<Camera> camere) {
        return camere.stream().map(this::toResponse).toList();
    }

    /**
     * Traduce lo stato dall'enum di dominio a quello generato dallo spec.
     *
     * <p>Sono due enum distinti con gli stessi nomi, e la conversione passa dal
     * nome invece che dall'ordinale: cosi' il giorno che i due elenchi
     * divergessero — una costante aggiunta di qua e non di la' — si prende una
     * {@code IllegalArgumentException} rumorosa in fase di test, invece di uno
     * stato sbagliato restituito in silenzio.
     */
    private StatoCamera toStatoDto(com.felixhotel.backend.entity.StatoCamera stato) {
        return StatoCamera.fromValue(stato.name());
    }

    /** Il verso opposto, per gli stati che arrivano dalle richieste. */
    public com.felixhotel.backend.entity.StatoCamera toStatoEntity(StatoCamera stato) {
        return com.felixhotel.backend.entity.StatoCamera.valueOf(stato.getValue());
    }
}
