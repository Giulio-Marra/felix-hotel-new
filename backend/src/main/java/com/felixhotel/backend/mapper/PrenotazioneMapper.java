package com.felixhotel.backend.mapper;

// Stato e canale esistono in due enum omonimi ciascuno: quello di dominio e
// quello generato dallo spec. Qui si importano i secondi e si scrivono per
// esteso i primi — uno dei due deve restare qualificato comunque, e in questa
// classe ricorrono di piu' i DTO. E' la stessa scelta gia' fatta in
// CameraMapper per StatoCamera.
import com.felixhotel.backend.dto.CanalePrenotazione;
import com.felixhotel.backend.dto.PrenotazioneResponse;
import com.felixhotel.backend.dto.StatoPrenotazione;
import com.felixhotel.backend.entity.Prenotazione;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Conversione Entity -> DTO per {@link Prenotazione}. Scritta a mano per scelta
 * di progetto (niente MapStruct); i DTO di destinazione sono generati dallo
 * spec.
 *
 * <p>Cliente, tipologia, personale e camera passano dai rispettivi mapper in
 * versione {@code Sintesi}: dentro una prenotazione servono per essere
 * riconosciuti, non per essere letti per intero.
 */
@Component
@RequiredArgsConstructor
public class PrenotazioneMapper {

    private final UtenteMapper utenteMapper;
    private final TipologiaCameraMapper tipologiaCameraMapper;
    private final StaffMapper staffMapper;
    private final CameraMapper cameraMapper;

    /**
     * <b>Va chiamato dentro la transazione</b> che ha caricato l'entity: le
     * relazioni sono LAZY e il progetto ha {@code open-in-view=false}. Le query
     * del repository le caricano gia' con {@code @EntityGraph}, quindi nella
     * pratica non c'e' niente da inizializzare — ma se un domani nascesse una
     * query senza quel fetch, il guasto comparirebbe qui.
     *
     * <p><b>La camera fisica c'e' solo dopo il check-in</b>, e prima e' null.
     * Non e' un dato che manca: e' una decisione che non e' ancora stata presa.
     */
    public PrenotazioneResponse toResponse(Prenotazione prenotazione) {
        return new PrenotazioneResponse()
                .id(prenotazione.getId())
                .dataCheckIn(prenotazione.getDataCheckIn())
                .dataCheckOut(prenotazione.getDataCheckOut())
                .numeroOspiti(prenotazione.getNumeroOspiti())
                .stato(toStatoDto(prenotazione.getStato()))
                .canale(toCanaleDto(prenotazione.getCanale()))
                .importoTotale(prenotazione.getImportoTotale())
                .note(prenotazione.getNote())
                .utente(utenteMapper.toSintesi(prenotazione.getUtente()))
                .tipologia(tipologiaCameraMapper.toSintesi(prenotazione.getTipologiaCamera()))
                .camera(cameraMapper.toSintesi(prenotazione.getCamera()))
                .gestitaDa(staffMapper.toSintesi(prenotazione.getGestitaDaStaff()))
                .motivoCancellazione(prenotazione.getMotivoCancellazione())
                .dataCancellazione(toOffset(prenotazione.getDataCancellazione()))
                .dataCreazione(toOffset(prenotazione.getCreatedAt()));
    }

    /** Versione per l'endpoint di lista: stessa conversione, applicata a una pagina di risultati. */
    public List<PrenotazioneResponse> toResponseList(List<Prenotazione> prenotazioni) {
        return prenotazioni.stream().map(this::toResponse).toList();
    }

    /**
     * Traduce lo stato dall'enum di dominio a quello generato dallo spec.
     *
     * <p>Come per {@code CameraMapper}, la conversione passa dal <b>nome</b> e
     * non dall'ordinale: il giorno che i due elenchi divergessero — una costante
     * aggiunta di qua e non di la' — si prende una
     * {@code IllegalArgumentException} rumorosa in fase di test, invece di uno
     * stato sbagliato restituito in silenzio.
     */
    private StatoPrenotazione toStatoDto(com.felixhotel.backend.entity.enums.StatoPrenotazione stato) {
        return StatoPrenotazione.fromValue(stato.name());
    }

    /** Il verso opposto, per gli stati che arrivano come filtro dalla query string. */
    public com.felixhotel.backend.entity.enums.StatoPrenotazione toStatoEntity(StatoPrenotazione stato) {
        return com.felixhotel.backend.entity.enums.StatoPrenotazione.valueOf(stato.getValue());
    }

    /** Stessa traduzione per nome, sull'altro enum. */
    private CanalePrenotazione toCanaleDto(com.felixhotel.backend.entity.enums.CanalePrenotazione canale) {
        return CanalePrenotazione.fromValue(canale.name());
    }

    /** Il verso opposto, per il canale che il personale indica nella richiesta. */
    public com.felixhotel.backend.entity.enums.CanalePrenotazione toCanaleEntity(CanalePrenotazione canale) {
        return com.felixhotel.backend.entity.enums.CanalePrenotazione.valueOf(canale.getValue());
    }

    /**
     * Da {@code LocalDateTime} a {@code OffsetDateTime}, che e' il tipo che il
     * generatore produce per un {@code format: date-time}.
     *
     * <p>L'offset e' quello di sistema e non UTC fisso: le date scritte in
     * database sono {@code TIMESTAMP} senza fuso — cioe' l'ora locale della
     * macchina che le ha scritte — e dichiararle UTC le sposterebbe di qualche
     * ora senza cambiarne il valore. Il giorno che le colonne diventassero
     * {@code TIMESTAMPTZ}, questa conversione va rifatta e non adattata.
     *
     * <p>Accetta null perche' {@code dataCancellazione} lo e' su tutto cio' che
     * non e' annullato, che e' la maggioranza delle prenotazioni.
     */
    private OffsetDateTime toOffset(LocalDateTime istante) {
        if (istante == null) {
            return null;
        }

        return istante.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
