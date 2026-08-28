package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.OspiteRequest;

/**
 * Gli ospiti registrati su una prenotazione: chi dorme davvero in albergo.
 *
 * <p>E' una <b>sottorisorsa</b>, come la galleria di una tipologia: ogni metodo
 * prende come primo argomento l'id della prenotazione, e non per comodita' di
 * firma — un ospite esiste solo dentro il soggiorno per cui e' stato
 * registrato. Non c'e' nessuna operazione che parta dal solo id di un ospite, e
 * non e' un'omissione: averla vorrebbe dire poter leggere o cancellare il
 * documento di qualcuno scrivendo nell'URL la prenotazione di un altro.
 *
 * <p>Da questo discendono i due 404 diversi che i metodi qui sotto sollevano con
 * lo stesso tipo di eccezione: <i>la prenotazione non esiste</i> e <i>questo
 * ospite non e' di questa prenotazione</i>. Al client arrivano uguali di
 * proposito; dentro restano due controlli separati.
 *
 * <p><b>Tutte le operazioni sono di backoffice</b>: il ruolo lo fa rispettare
 * {@code @PreAuthorize} sul Controller, ma <b>non basta</b>, e qui sta l'unica
 * differenza vera rispetto agli altri Service del progetto. Un'annotazione
 * guarda il ruolo e non sa niente della tabella da cui l'account viene, mentre
 * dal 2026-08-27 i privilegi del personale pretendono le due cose insieme:
 * l'implementazione lo verifica con {@code ChiamanteCorrente.personale()} prima
 * di ogni altra cosa. E' il motivo per cui quella classe esiste.
 *
 * <p><b>La finestra in cui si scrive e' la stessa per tutti e tre i metodi che
 * scrivono</b>: prenotazione CONFERMATA o gia' in CHECK_IN, altrimenti
 * {@code ConflictException}. La lettura invece non ha finestra — un registro si
 * rilegge anche dopo, e soprattutto <i>dopo</i>.
 */
public interface OspiteService {

    /**
     * Gli ospiti della prenotazione, nell'ordine in cui sono stati registrati.
     *
     * <p>Non e' paginato: il tetto non e' una costante ma {@code numeroOspiti}
     * della prenotazione, che a sua volta non puo' superare la capienza della
     * tipologia. E' una lista di dimensione nota per costruzione.
     *
     * <p>Una prenotazione senza ospiti e' una lista vuota; una prenotazione che
     * non esiste e' {@code NotFoundException}. I due casi vanno tenuti distinti:
     * chi sta al banco deve poter capire se non ha ancora registrato nessuno o
     * se ha aperto la prenotazione sbagliata.
     */
    ApiBaseResponse elenca(Long prenotazioneId);

    /**
     * Registra un ospite. Solleva {@code NotFoundException} se la prenotazione
     * non esiste, e {@code ConflictException} in tre casi: la prenotazione non
     * e' in una fase in cui si registra ({@code CONFERMATA} o {@code CHECK_IN}),
     * gli ospiti sono gia' {@code numeroOspiti}, oppure quel documento e' gia'
     * registrato su questa prenotazione.
     */
    ApiBaseResponse aggiungi(Long prenotazioneId, OspiteRequest request);

    /**
     * Corregge i dati di un ospite gia' registrato. Stesse eccezioni
     * dell'aggiunta, meno il tetto — correggere non aumenta il conto — e con il
     * controllo sul documento che <b>ignora l'ospite stesso</b>: rimandare il
     * proprio numero non e' un conflitto, altrimenti correggere il solo nome
     * sarebbe impossibile.
     *
     * <p>E' una PUT: un campo facoltativo omesso viene azzerato, non lasciato
     * com'era.
     */
    ApiBaseResponse aggiorna(Long prenotazioneId, Long ospiteId, OspiteRequest request);

    /**
     * Cancella la registrazione di un ospite. Solleva {@code NotFoundException}
     * se la prenotazione non esiste, se l'ospite non esiste, o se esiste ma
     * appartiene a un'altra prenotazione; {@code ConflictException} se la
     * prenotazione non e' nella finestra in cui si scrive.
     *
     * <p>Su una prenotazione in CHECK_IN riporta il conto sotto
     * {@code numeroOspiti}, cioe' sotto la condizione che il check-in aveva
     * preteso. Non c'e' niente da rifare — il check-in e' gia' avvenuto — ma il
     * registro da quel momento dice meno di quel che dice la prenotazione, ed e'
     * una cosa che chi lo rilegge deve poter vedere.
     */
    ApiBaseResponse elimina(Long prenotazioneId, Long ospiteId);
}
