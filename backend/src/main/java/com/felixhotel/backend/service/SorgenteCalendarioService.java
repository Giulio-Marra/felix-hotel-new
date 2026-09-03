package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.SorgenteCalendarioRequest;

/**
 * I calendari altrui che leggiamo per sapere quando un canale ha venduto una nostra
 * camera.
 *
 * <p><b>Tre operazioni piu' una</b>: si elenca, si registra, si toglie — e in piu' si puo'
 * far partire subito il giro che altrimenti parte da solo. La correzione non c'e', come
 * per i blocchi: una sorgente e' una camera, un nome e un indirizzo, e non c'e' niente da
 * correggere che non sia piu' semplice cancellare e rifare.
 */
public interface SorgenteCalendarioService {

    /** L'elenco per il backoffice, con l'esito dell'ultimo giro di ognuna. */
    ApiBaseResponsePaginated elenca(Long cameraId, int page, int size);

    /**
     * Registra un indirizzo da leggere per una camera.
     *
     * <p><b>Non scarica niente</b>: l'indirizzo si prova al primo giro, o subito con
     * {@link #sincronizzaTutte()}. Un canale lento o momentaneamente giu' non deve
     * impedire di salvare una configurazione giusta.
     */
    ApiBaseResponse crea(SorgenteCalendarioRequest request);

    /**
     * Smette di leggere quel calendario, <b>e toglie i blocchi che aveva scritto</b>:
     * quelle notti tornano vendibili all'istante.
     */
    ApiBaseResponse elimina(Long sorgenteId);

    /** Rilegge adesso tutti i calendari e restituisce com'e' andato il giro. */
    ApiBaseResponse sincronizzaTutte();
}
