package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.BloccoRequest;

import java.time.LocalDate;

/**
 * Le camere che non si possono vendere pur non essendo prenotate da nessuno.
 *
 * <p><b>Tre operazioni e non quattro</b>: si elenca, si crea, si toglie. La correzione
 * non c'e' perche' un blocco e' fatto di una camera e due date, e non c'e' niente da
 * correggere che non sia piu' semplice cancellare e rifare — al contrario di un ospite,
 * dove la PUT esiste perche' un registro di legge non deve attraversare un istante in
 * cui la persona non risulta.
 */
public interface BloccoDisponibilitaService {

    /**
     * L'elenco per il backoffice.
     *
     * @param da inizio del periodo di interesse; il filtro prende le <b>sovrapposizioni</b>,
     *           non i contenimenti — chi guarda settembre deve vedere anche il blocco che
     *           comincia il 28 agosto, perche' e' quello che gli impedisce di vendere
     */
    ApiBaseResponsePaginated elenca(Long tipologiaCameraId, Long cameraId,
                                    LocalDate da, LocalDate a, int page, int size);

    /** Rende non vendibile una unita' di una tipologia, nominando la camera o no. */
    ApiBaseResponse crea(BloccoRequest request);

    /** Rimette in vendita: la camera torna a contare e il check-in puo' assegnarla. */
    ApiBaseResponse elimina(Long bloccoId);
}
