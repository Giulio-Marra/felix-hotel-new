package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ImpostazioniHotelRequest;

/**
 * Anagrafica della struttura: recapiti, orari e — dal 2026-08-28 — identita'
 * fiscale e codici degli adempimenti.
 *
 * <p><b>Non e' un CRUD e i metodi lo dicono</b>: non c'e' nessun {@code crea} e
 * nessun {@code elimina}. La riga e' una sola, nasce con la migration
 * {@code V8__identita_struttura.sql} e non muore mai; le uniche operazioni
 * sensate sono leggerla e riscriverla.
 *
 * <p><b>Due letture, non una con un filtro.</b> {@link #leggiPubbliche()}
 * restituisce cio' che una struttura pubblica di sua volonta' — nome,
 * indirizzo, recapiti e orari — e la chiama chiunque; {@link #leggi()}
 * restituisce anche partita IVA, CIN, comune ISTAT e codice per Alloggiati Web,
 * ed e' riservata agli ADMIN. Sono due metodi perche' sono due DTO, e sono due
 * DTO perche' un campo aggiunto a quello riservato non deve poter diventare
 * pubblico per distrazione (vedi {@code ImpostazioniHotelMapper}).
 *
 * <p>Il controllo del ruolo sta sul Controller, con {@code @PreAuthorize}: qui
 * si assume gia' fatto. Come gli altri Service del progetto restituisce
 * direttamente la busta standard, scegliendo messaggio e status.
 */
public interface ImpostazioniHotelService {

    /**
     * Le impostazioni complete, per gli ADMIN. E' anche la vista da cui si
     * riempie il modulo di modifica: la {@link #aggiorna} e' una PUT, quindi chi
     * non legge tutto non puo' riscrivere senza cancellare cio' che non vede.
     */
    ApiBaseResponse leggi();

    /** Il sottoinsieme pubblico: nome, indirizzo, recapiti e orari. */
    ApiBaseResponse leggiPubbliche();

    /**
     * Sostituisce tutti i campi modificabili. E' una PUT, non una PATCH: i campi
     * assenti dalla richiesta vengono azzerati e non lasciati al valore
     * precedente.
     */
    ApiBaseResponse aggiorna(ImpostazioniHotelRequest request);
}
