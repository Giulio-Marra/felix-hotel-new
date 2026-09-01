package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.AliquotaTassaSoggiornoRequest;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TassaSoggiornoMapper;
import com.felixhotel.backend.repository.AliquotaTassaSoggiornoRepository;
import com.felixhotel.backend.service.AliquotaTassaSoggiornoService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Implementazione del calendario della tassa di soggiorno.
 *
 * <p>Nella forma e' il gemello di {@code PeriodoTariffarioServiceImpl} — stesso
 * controllo di sovrapposizione con lo stesso 409 che dice con chi, stessa verifica
 * sui due decimali, stesso rapporto fra la cortesia del Service e la garanzia del
 * vincolo di esclusione — ma e' <b>piu' semplice di lui in un punto e piu'
 * pericoloso in un altro</b>, e sono le due cose che vale la pena leggere.
 *
 * <p><b>Piu' semplice: non c'e' nessun padre.</b> I periodi tariffari sono di una
 * tipologia, quindi ogni metodo cominciava col verificare che la tipologia
 * esistesse e che il periodo fosse suo. Qui l'aliquota e' dell'albergo — la decide
 * il comune — quindi non c'e' nessun id da incrociare e nessun 404 di
 * appartenenza. Per la stessa ragione il controllo di sovrapposizione guarda le
 * sole date, mentre quello dei periodi doveva restringersi alla tipologia.
 *
 * <p><b>Piu' pericoloso: qui si tocca il conto di chi e' gia' in albergo.</b>
 * L'{@code importoTotale} di una prenotazione e' una fotografia, quindi cambiare
 * una tariffa non riscrive niente di gia' venduto; la tassa invece non e' scritta
 * da nessuna parte e si ricalcola ad ogni richiesta, quindi una PUT o una DELETE
 * qui cambiano quel che deve al comune anche chi ha gia' fatto il check-in.
 * <b>Non e' un difetto da correggere</b> — la tassa e' un debito verso il comune e
 * vale quello che il regolamento dice — ma e' il motivo per cui il contratto dice
 * di rispondere a un cambio di aliquota <i>aggiungendone</i> una nuova accanto
 * alla vecchia invece di riscrivere quella vecchia.
 */
@Service
public class AliquotaTassaSoggiornoServiceImpl implements AliquotaTassaSoggiornoService {

    private final AliquotaTassaSoggiornoRepository aliquotaRepository;
    private final TassaSoggiornoMapper tassaSoggiornoMapper;
    private final ApiResponseMapper apiResponseMapper;

    public AliquotaTassaSoggiornoServiceImpl(AliquotaTassaSoggiornoRepository aliquotaRepository,
                                             TassaSoggiornoMapper tassaSoggiornoMapper,
                                             ApiResponseMapper apiResponseMapper) {
        this.aliquotaRepository = aliquotaRepository;
        this.tassaSoggiornoMapper = tassaSoggiornoMapper;
        this.apiResponseMapper = apiResponseMapper;
    }

    /**
     * L'elenco paginato.
     *
     * <p><b>Nessun controllo di esistenza da fare prima</b>, al contrario delle
     * sottorisorse: non c'e' nessun padre che possa non esserci, quindi una pagina
     * vuota vuol dire una cosa sola — nessuna aliquota configurata — e quella non
     * e' un errore.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size) {
        Page<AliquotaTassaSoggiorno> pagina =
                aliquotaRepository.findAllByOrderByDataInizioAsc(PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK,
                "Aliquote della tassa di soggiorno recuperate",
                tassaSoggiornoMapper.toResponseList(pagina.getContent()),
                pagina);
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(AliquotaTassaSoggiornoRequest request) {
        verificaRichiesta(request);
        assicuraNessunaSovrapposizione(request, null);

        AliquotaTassaSoggiorno aliquota = new AliquotaTassaSoggiorno();
        applica(aliquota, request);

        AliquotaTassaSoggiorno salvata = salvaGestendoLaSovrapposizione(aliquota);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Aliquota creata",
                tassaSoggiornoMapper.toResponse(salvata));
    }

    /**
     * Riscrive un'aliquota per intero.
     *
     * <p>Il controllo di sovrapposizione <b>esclude l'aliquota stessa</b>:
     * riconfermarle le proprie date non e' un conflitto, altrimenti correggere il
     * solo importo sarebbe impossibile. E' la stessa forma gia' usata per i periodi
     * tariffari e per il documento di un ospite che si ricorregge.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long aliquotaId, AliquotaTassaSoggiornoRequest request) {
        verificaRichiesta(request);

        AliquotaTassaSoggiorno aliquota = trovaOrElseThrow(aliquotaId);
        assicuraNessunaSovrapposizione(request, aliquotaId);

        applica(aliquota, request);

        AliquotaTassaSoggiorno salvata = salvaGestendoLaSovrapposizione(aliquota);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Aliquota aggiornata",
                tassaSoggiornoMapper.toResponse(salvata));
    }

    /**
     * Cancella un'aliquota.
     *
     * <p><b>Nessun controllo su chi la stesse usando</b>, e non e' una svista: la
     * tassa non e' mai stata scritta su nessuna riga, quindi non esiste nessuna
     * chiave esterna verso questa tabella e non c'e' niente da tradurre in 409. La
     * conseguenza e' che le notti coperte smettono semplicemente di essere tassate,
     * ed e' scritta nel contratto perche' e' l'unico posto in cui fa danno.
     */
    @Override
    @Transactional
    public ApiBaseResponse elimina(Long aliquotaId) {
        AliquotaTassaSoggiorno aliquota = trovaOrElseThrow(aliquotaId);

        aliquotaRepository.delete(aliquota);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Aliquota eliminata", null);
    }

    private AliquotaTassaSoggiorno trovaOrElseThrow(Long aliquotaId) {
        return aliquotaRepository.findById(aliquotaId)
                .orElseThrow(() -> new NotFoundException("Aliquota non trovata"));
    }

    /**
     * I controlli che guardano la sola richiesta, prima di toccare il database.
     */
    private void verificaRichiesta(AliquotaTassaSoggiornoRequest request) {
        verificaOrdineDate(request);
        verificaDecimali(request);
    }

    /**
     * La data di fine non puo' precedere quella di inizio, ma puo' coincidere:
     * un'aliquota di una notte sola e' legittima, come un periodo tariffario di
     * Capodanno.
     *
     * <p>Il vincolo esiste anche in database ({@code CHECK} del V11); qui c'e' per
     * la ragione della regola 21 — senza, il valore arriverebbe fino a Postgres e
     * tornerebbe a chi chiama come un 500 invece che come un 400.
     */
    private void verificaOrdineDate(AliquotaTassaSoggiornoRequest request) {
        if (request.getDataFine().isBefore(request.getDataInizio())) {
            throw new BadRequestException(
                    "La data di fine non puo' precedere quella di inizio");
        }
    }

    /**
     * Rifiuta un importo con piu' di due decimali.
     *
     * <p>Stessa regola gia' applicata ai prezzi, e per la stessa ragione: la
     * colonna e' {@code NUMERIC(10,2)} e Postgres troncherebbe in silenzio, quindi
     * la stessa aliquota direbbe due importi diversi a seconda di quando la si
     * chiede. Su un importo che si versa al comune, "dipende da quando lo chiedi"
     * non e' una risposta.
     */
    private void verificaDecimali(AliquotaTassaSoggiornoRequest request) {
        BigDecimal importo = request.getImportoPerPersonaNotte();
        if (importo.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(
                    "L'importo per persona a notte non puo' avere piu' di due decimali");
        }
    }

    /**
     * 409 se le date si accavallano con un'altra aliquota, con un messaggio che
     * dice quale.
     *
     * <p>E' la <b>cortesia</b>: la garanzia e' il vincolo di esclusione del V11,
     * che regge anche quando due richieste arrivano nello stesso istante. Lo stesso
     * rapporto che c'e' fra gli {@code existsBy} dei repository e gli indici unici.
     */
    private void assicuraNessunaSovrapposizione(AliquotaTassaSoggiornoRequest request, Long esclusa) {
        List<AliquotaTassaSoggiorno> sovrapposte = aliquotaRepository.trovaSovrapposte(
                request.getDataInizio(), request.getDataFine(), esclusa);

        if (!sovrapposte.isEmpty()) {
            AliquotaTassaSoggiorno prima = sovrapposte.getFirst();
            throw new ConflictException("Le date si sovrappongono all'aliquota dal "
                    + prima.getDataInizio() + " al " + prima.getDataFine());
        }
    }

    private void applica(AliquotaTassaSoggiorno aliquota, AliquotaTassaSoggiornoRequest request) {
        aliquota.setDataInizio(request.getDataInizio());
        aliquota.setDataFine(request.getDataFine());
        aliquota.setImportoPerPersonaNotte(request.getImportoPerPersonaNotte());
        // Facoltativi: omessi vuol dire azzerati, che e' quel che una PUT promette.
        // Qui la conseguenza si vede sul conto di qualcuno — niente tetto di notti,
        // e i bambini che cominciano a pagare — ed e' scritto nel contratto.
        aliquota.setNottiMassimeTassate(request.getNottiMassimeTassate());
        aliquota.setEtaEsenzione(request.getEtaEsenzione());
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione del vincolo di esclusione. E' la rete sotto ad
     * {@link #assicuraNessunaSovrapposizione}: copre la richiesta gemella arrivata
     * nel frattempo, che nessun controllo preventivo puo' vedere.
     *
     * <p>Il messaggio qui e' piu' povero di quello del controllo preventivo — non
     * puo' dire con quale aliquota — ed e' accettato: e' il caso raro, e chi lo
     * riceve rilegge l'elenco.
     */
    private AliquotaTassaSoggiorno salvaGestendoLaSovrapposizione(AliquotaTassaSoggiorno aliquota) {
        try {
            return aliquotaRepository.saveAndFlush(aliquota);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ConflictException(
                    "Le date si sovrappongono a un'altra aliquota gia' registrata", ex);
        }
    }
}
