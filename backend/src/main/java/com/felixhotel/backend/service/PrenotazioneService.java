package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.StatoPrenotazione;

/**
 * Ciclo di vita di una prenotazione, dall'apertura del carrello
 * all'annullamento.
 *
 * <p><b>Qui l'autorizzazione non e' tutta sul Controller</b>, ed e' la
 * differenza rispetto agli altri Service del progetto. Il {@code @PreAuthorize}
 * sa dire "serve un ruolo", ma non sa dire "solo le tue": chi puo' vedere o
 * toccare una prenotazione dipende da <b>quale</b> prenotazione, e quella
 * risposta ha bisogno della riga. Il Controller quindi si limita a pretendere
 * un account autenticato, e la parte che dipende dai dati sta qui.
 *
 * <p>Come gli altri Service restituisce direttamente la busta standard; gli
 * errori viaggiano come sottoclassi di {@code AppException}.
 */
public interface PrenotazioneService {

    /**
     * Pagina di prenotazioni, dalla piu' recente per data di arrivo.
     *
     * <p><b>L'ambito lo decide il token, non un parametro</b>: un USER riceve
     * solo le proprie, STAFF e ADMIN le ricevono tutte. Un filtro che il client
     * puo' scegliere e' un filtro che il client puo' non mandare.
     *
     * @param page  numero di pagina, 0-based
     * @param size  quanti elementi per pagina; il tetto e' nello spec
     * @param stato se null, non filtra per stato
     */
    ApiBaseResponsePaginated elenca(int page, int size, StatoPrenotazione stato);

    /**
     * Singola prenotazione. Solleva {@code NotFoundException} se l'id non esiste
     * <b>o se non e' del chiamante</b> e il chiamante non e' del personale: sono
     * due situazioni diverse che devono dare la stessa risposta, altrimenti la
     * differenza fra le due dice a un estraneo quali id esistono.
     */
    ApiBaseResponse dettaglio(Long id);

    /**
     * Apre una prenotazione in stato IN_ATTESA — il carrello, che non riserva
     * niente finche' non viene confermato.
     *
     * <p>Solleva {@code BadRequestException} se la tipologia non esiste, se le
     * date non stanno in piedi, se gli ospiti superano la capienza, o se
     * {@code utenteId}/{@code canale} sono usati da chi non puo' (un USER) o
     * omessi da chi deve (STAFF e ADMIN); {@code ConflictException} se per quel
     * periodo non resta nessuna camera di quella tipologia.
     */
    ApiBaseResponse crea(PrenotazioneRequest request);

    /**
     * Porta la prenotazione da IN_ATTESA a CONFERMATA, ricontrollando la
     * disponibilita' <b>adesso</b>: e' il controllo che conta, quello fatto in
     * creazione era una cortesia.
     *
     * <p>Solleva {@code NotFoundException} se non esiste o non e' visibile a chi
     * chiama, {@code ConflictException} se non e' IN_ATTESA, se il giorno di
     * arrivo e' ormai passato, o se nel frattempo il posto e' finito. In
     * quest'ultimo caso <b>la prenotazione resta IN_ATTESA</b> e non viene
     * toccata: non e' andata perduta, va cambiata di date.
     *
     * <p>Il controllo sull'arrivo passato c'e' <b>anche se la creazione lo
     * faceva gia'</b>: fra le due chiamate puo' passare qualsiasi tempo, e un
     * carrello non scade.
     */
    ApiBaseResponse conferma(Long id);

    /**
     * Porta la prenotazione in ANNULLATA, registrando l'istante e — se indicato
     * — il motivo.
     *
     * <p>Solleva {@code NotFoundException} se non esiste o non e' visibile a chi
     * chiama, {@code ConflictException} se e' gia' annullata o se il soggiorno e'
     * gia' cominciato.
     *
     * @param request corpo facoltativo: se null, l'annullamento avviene lo
     *                stesso e il motivo resta vuoto
     */
    ApiBaseResponse annulla(Long id, PrenotazioneAnnullamentoRequest request);
}
