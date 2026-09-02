package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;

import java.time.LocalDate;

/**
 * L'export delle schedine per il portale Alloggiati Web della Polizia di Stato.
 *
 * <p><b>Un metodo solo, e la forma dice cos'e' questa risorsa</b>: non ha una CRUD
 * perche' non c'e' niente da creare — le schedine non sono righe di una tabella, sono
 * una <i>vista</i> del registro degli ospiti nel formato che la Questura pretende. Il
 * dato esiste gia' e questa interfaccia lo riscrive; il giorno in cui si conservasse
 * quel che e' stato mandato, quella sarebbe un'entita' nuova e un'altra decisione.
 *
 * <p><b>Ci si ferma al file.</b> L'invio automatico pretenderebbe le credenziali che
 * la Questura da' all'albergatore, cifrate a riposo — il primo segreto per-struttura
 * del progetto — ed e' una decisione indipendente da questa: deciso il 2026-09-02,
 * vedi CLAUDE.md.
 */
public interface AlloggiatiService {

    /**
     * Il file delle schedine per gli arrivi di un giorno.
     *
     * @param data il giorno di arrivo, quasi sempre ieri: l'obbligo si assolve entro
     *             ventiquattro ore
     * @return busta con {@code SchedineAlloggiatiResponse}, anche quando le schedine
     *         sono zero — una giornata senza arrivi e' una risposta, non un errore
     */
    ApiBaseResponse esportaSchedine(LocalDate data);
}
