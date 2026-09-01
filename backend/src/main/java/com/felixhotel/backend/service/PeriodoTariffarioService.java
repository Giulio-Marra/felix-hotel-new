package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PeriodoTariffarioRequest;

/**
 * Il calendario dei prezzi di una tipologia di camera: in che date costa quanto,
 * e quante notti bisogna fermarsi come minimo.
 *
 * <p>E' una <b>sottorisorsa</b>, come la galleria fotografica e come il registro
 * degli ospiti: ogni metodo prende come primo argomento l'id della tipologia, e
 * non per comodita' di firma — un periodo tariffario non vuol dire niente
 * staccato dalla tipologia di cui mette il prezzo. Non c'e' nessuna operazione
 * che parta dal solo id di un periodo, e non e' un'omissione: averla vorrebbe
 * dire poter cambiare il prezzo di una tipologia scrivendo nell'URL un'altra.
 *
 * <p><b>Il livello di permesso e' quello delle camere e non quello delle
 * foto</b>: leggere e' di STAFF o ADMIN, scrivere solo di ADMIN. Chi sta al
 * banco deve poter dire al telefono quanto costa una settimana di agosto — e'
 * il lavoro di ogni turno — ma decidere quel prezzo e' una scelta commerciale.
 * Il ruolo lo fa rispettare {@code @PreAuthorize} sul Controller.
 *
 * <p><b>La regola che tiene in piedi tutto il resto e' una sola: un giorno, un
 * prezzo.</b> Due periodi della stessa tipologia non possono sovrapporsi, e
 * senza quel vincolo la domanda "quanto costa la notte del 15 agosto" avrebbe
 * due risposte e nessun criterio non arbitrario per sceglierne una. Qui la
 * verifica risponde 409 dicendo con quale periodo si accavalla; la garanzia vera
 * e' il vincolo di esclusione del V9, che regge anche quando due richieste
 * arrivano nello stesso istante.
 *
 * <p><b>Niente di quel che succede qui tocca le prenotazioni gia' fatte.</b> Il
 * loro {@code importoTotale} e' una fotografia presa alla creazione: cambiare o
 * cancellare un periodo non riscrive la storia di chi ha gia' comprato, ed e'
 * il motivo per cui queste sono operazioni ordinarie e non pericolose.
 */
public interface PeriodoTariffarioService {

    /**
     * I periodi della tipologia, dal piu' vecchio al piu' recente.
     *
     * <p>Paginato, al contrario delle altre due sottorisorse del progetto: i
     * periodi si accumulano di anno in anno e niente li limita, mentre foto e
     * ospiti hanno un tetto per costruzione.
     *
     * <p>Una tipologia senza periodi e' una pagina vuota; una tipologia che non
     * esiste e' {@code NotFoundException}. I due casi vanno tenuti distinti: un
     * calendario vuoto vuol dire che si vende al prezzo di listino, un id
     * sbagliato vuol dire che si sta guardando la camera di qualcun altro.
     */
    ApiBaseResponsePaginated elenca(Long tipologiaCameraId, int page, int size);

    /**
     * Aggiunge un periodo. Solleva {@code NotFoundException} se la tipologia non
     * esiste, {@code BadRequestException} se la data di fine precede quella di
     * inizio, se un prezzo ha piu' di due decimali o se lo stesso giorno della
     * settimana compare due volte, e {@code ConflictException} se le date si
     * sovrappongono a un altro periodo della stessa tipologia.
     */
    ApiBaseResponse crea(Long tipologiaCameraId, PeriodoTariffarioRequest request);

    /**
     * Riscrive un periodo per intero. Stesse eccezioni della creazione, piu' il
     * {@code NotFoundException} del periodo che non esiste o che appartiene a
     * un'altra tipologia, e con il controllo di sovrapposizione che <b>ignora il
     * periodo stesso</b>: riconfermargli le proprie date non e' un conflitto,
     * altrimenti correggere il solo prezzo sarebbe impossibile.
     *
     * <p>E' una PUT: {@code prezziGiorno} sostituisce l'insieme intero, e un
     * giorno che non compare torna a costare il prezzo base del periodo.
     */
    ApiBaseResponse aggiorna(Long tipologiaCameraId, Long periodoId, PeriodoTariffarioRequest request);

    /**
     * Cancella un periodo, e con lui i suoi prezzi per giorno della settimana.
     *
     * <p>Le date tornano al prezzo di listino della tipologia, non diventano non
     * prenotabili: e' la ragione per cui {@code TipologiaCamera.prezzoNotte} non
     * e' stato tolto quando sono nate le tariffe.
     */
    ApiBaseResponse elimina(Long tipologiaCameraId, Long periodoId);
}
