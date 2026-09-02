package com.felixhotel.backend.mapper;

import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.entity.enums.TipoCodifica;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione fra {@link VoceCodifica} e il DTO generato dallo spec. Scritta a
 * mano per scelta di progetto (niente MapStruct).
 *
 * <p><b>Fa il verso opposto piu' di quanto faccia quello dritto</b>, al contrario
 * di ogni altro mapper del progetto, e la ragione dice cos'e' questa risorsa: da
 * qui non esce quasi niente di elaborato — un codice e una descrizione — mentre
 * ci entra un elenco intero da trasformare in righe. E' un dato di riferimento,
 * non un pezzo di dominio: si ripete, non si produce.
 *
 * <p><b>Il tipo non e' nel DTO ed e' il mapper a metterlo</b>: sta nel percorso
 * della richiesta, quindi ripeterlo in ognuna delle ottomila righe di un import
 * vorrebbe dire spedire ottomila volte una cosa che il chiamante ha appena
 * scritto nell'URL. E' la stessa scelta gia' fatta per la prenotazione degli
 * ospiti e per la tipologia delle foto.
 */
@Component
public class VoceCodificaMapper {

    public com.felixhotel.backend.dto.VoceCodifica toResponse(VoceCodifica voce) {
        return new com.felixhotel.backend.dto.VoceCodifica()
                .codice(voce.getCodice())
                .descrizione(voce.getDescrizione())
                .provincia(voce.getProvincia());
    }

    /**
     * Versione per l'elenco. <b>L'ordine della lista in ingresso viene
     * conservato</b>: lo ha gia' deciso la query, per descrizione crescente.
     */
    public List<com.felixhotel.backend.dto.VoceCodifica> toResponseList(List<VoceCodifica> voci) {
        return voci.stream().map(this::toResponse).toList();
    }

    /**
     * Le entita' da scrivere, a partire dall'elenco mandato per l'import.
     *
     * <p>Sta qui e non nel Service per la regola 11 — nei Service non si assembla
     * nessun DTO ne' nessuna entita' — e perche' e' letteralmente la stessa
     * traduzione di {@link #toResponse} letta da destra a sinistra: tenerle nella
     * stessa classe e' cio' che rende visibile a colpo d'occhio che siano coerenti.
     */
    public List<VoceCodifica> toEntita(TipoCodifica tipo,
                                       List<com.felixhotel.backend.dto.VoceCodifica> voci) {
        return voci.stream().map(voce -> toEntita(tipo, voce)).toList();
    }

    private VoceCodifica toEntita(TipoCodifica tipo, com.felixhotel.backend.dto.VoceCodifica dto) {
        VoceCodifica voce = new VoceCodifica();
        voce.setTipo(tipo);
        voce.setCodice(dto.getCodice());
        voce.setDescrizione(dto.getDescrizione());
        // Ha senso solo sui comuni. Non viene rifiutata sulle altre famiglie ma non
        // la legge nessuno: far fallire un import per una colonna di troppo in un
        // file che non abbiamo scritto noi sarebbe severita' verso la persona
        // sbagliata.
        voce.setProvincia(dto.getProvincia());
        return voce;
    }
}
