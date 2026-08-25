package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.entity.Dotazione;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.repository.DotazioneRepository;
import com.felixhotel.backend.service.DotazioneService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementazione dell'elenco delle dotazioni.
 *
 * <p>Ricalca {@link TipologiaCameraServiceImpl}, che e' il modello di ogni CRUD
 * di questo progetto, e ne eredita i due meccanismi che vale la pena leggere
 * una volta sola.
 *
 * <p><b>Il duplicato si controlla due volte, non per distrazione.</b> Prima con
 * un {@code existsBy}, che permette di rispondere 409 con un messaggio
 * comprensibile; poi lasciando parlare l'indice unico del database, perche' fra
 * il controllo e la scrittura ci sta un'altra richiesta identica. Il primo
 * controllo e' cortesia, il secondo e' la garanzia.
 *
 * <p><b>La violazione di vincolo si intercetta dov'e' generata.</b> Le scritture
 * passano da {@code saveAndFlush} dentro il try: senza il flush esplicito,
 * Hibernate rimanderebbe la SQL al commit della transazione, cioe' <b>fuori</b>
 * da questo metodo e dal catch — e la violazione tornerebbe al client come 500
 * invece che come 409.
 */
@Service
@RequiredArgsConstructor
public class DotazioneServiceImpl implements DotazioneService {

    private final DotazioneRepository dotazioneRepository;
    private final DotazioneMapper dotazioneMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Elenco paginato, ordinato per nome. L'ordine e' esplicito e non lasciato
     * al database: senza {@code ORDER BY}, Postgres puo' restituire le righe in
     * ordine diverso fra una pagina e l'altra, e un elemento finirebbe per
     * comparire due volte o mai.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size) {
        Page<Dotazione> pagina = dotazioneRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "nome")));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Dotazioni recuperate",
                dotazioneMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse dettaglio(Long id) {
        Dotazione dotazione = trovaOrElseThrow(id);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Dotazione recuperata",
                dotazioneMapper.toResponse(dotazione));
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(DotazioneRequest request) {
        if (dotazioneRepository.existsByNomeIgnoreCase(request.getNome())) {
            throw new ConflictException("Esiste gia' una dotazione con questo nome");
        }

        Dotazione dotazione = new Dotazione();
        applicaCampi(dotazione, request);

        Dotazione salvata = salvaGestendoIlDuplicato(dotazione);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Dotazione creata",
                dotazioneMapper.toResponse(salvata));
    }

    /**
     * Aggiornamento completo: e' una PUT, quindi i campi assenti dalla richiesta
     * vengono azzerati e non lasciati al valore precedente. Non e' un effetto
     * collaterale ma il significato del verbo.
     *
     * <p>Le tipologie che hanno gia' questa dotazione non vengono toccate: il
     * legame e' per id, quindi continuano ad averla col nome nuovo. E' il
     * comportamento voluto — rinominare "Wi-Fi" in "Wi-Fi 6" e' correggere
     * un'etichetta, non togliere il servizio a chi lo offre.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long id, DotazioneRequest request) {
        Dotazione dotazione = trovaOrElseThrow(id);

        // Escludendo se stessa: senza IdNot, salvare una dotazione senza cambiarle il
        // nome darebbe 409 contro il proprio nome.
        if (dotazioneRepository.existsByNomeIgnoreCaseAndIdNot(request.getNome(), id)) {
            throw new ConflictException("Esiste gia' un'altra dotazione con questo nome");
        }

        applicaCampi(dotazione, request);

        Dotazione salvata = salvaGestendoIlDuplicato(dotazione);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Dotazione aggiornata",
                dotazioneMapper.toResponse(salvata));
    }

    /**
     * Eliminazione. <b>Qui non c'e' il 409 che protegge le tipologie di camera</b>,
     * ed e' una scelta e non una dimenticanza: la chiave esterna della tabella di
     * legame ha {@code ON DELETE CASCADE} (vedi V1__init_schema.sql), quindi i
     * riferimenti alle tipologie spariscono insieme alla dotazione.
     *
     * <p>La differenza fra i due casi e' cosa si porta via la cancellata. Una
     * tipologia usata trascina con se' lo storico delle prenotazioni, che serve
     * anche quando quella tipologia non e' piu' in listino; una dotazione tolta
     * dall'elenco e' una voce che la struttura non offre piu', e lasciarla
     * appesa alle schede sarebbe l'unico esito sbagliato.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long id) {
        Dotazione dotazione = trovaOrElseThrow(id);

        dotazioneRepository.delete(dotazione);

        // 'data' null: dopo un'eliminazione non c'e' niente da restituire. Lo status e'
        // 200 e non 204 perche' la busta standard vale per ogni endpoint del progetto,
        // e un 204 per definizione non ha corpo.
        return apiResponseMapper.toResponse(HttpStatus.OK, "Dotazione eliminata", null);
    }

    /** Lettura per id, con il 404 gia' pronto: e' il preambolo di tre metodi su cinque. */
    private Dotazione trovaOrElseThrow(Long id) {
        return dotazioneRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Dotazione non trovata"));
    }

    /**
     * Copia nell'entity i campi che il client puo' decidere. Sta qui e non nel
     * mapper perche' non e' una conversione: e' l'elenco di cosa e' modificabile
     * da fuori — id e date di audit non compaiono, e non e' una dimenticanza.
     */
    private void applicaCampi(Dotazione dotazione, DotazioneRequest request) {
        dotazione.setNome(request.getNome());
        dotazione.setDescrizione(request.getDescrizione());
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico sul nome (vedi la nota in testa alla classe).
     * E' la rete sotto al controllo {@code existsBy}: copre la richiesta gemella
     * arrivata nel frattempo, che nessun controllo preventivo puo' vedere.
     */
    private Dotazione salvaGestendoIlDuplicato(Dotazione dotazione) {
        try {
            return dotazioneRepository.saveAndFlush(dotazione);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Esiste gia' una dotazione con questo nome", ex);
        }
    }
}
