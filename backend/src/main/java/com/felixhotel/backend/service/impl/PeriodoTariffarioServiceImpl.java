package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.dto.PrezzoGiorno;
import com.felixhotel.backend.entity.PeriodoTariffario;
import com.felixhotel.backend.entity.PrezzoGiornoSettimana;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.PeriodoTariffarioMapper;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.PeriodoTariffarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementazione del calendario dei prezzi.
 *
 * <p>Nella forma e' un CRUD di sottorisorsa come gia' se ne sono visti — la
 * tipologia si risolve per prima, il figlio si cerca sempre <i>dentro</i> di lei
 * — e le uniche due cose che vale la pena leggere sono queste.
 *
 * <p><b>La prima: la sovrapposizione si verifica qui e si garantisce la'.</b>
 * {@link #verificaSovrapposizione} risponde 409 nominando il periodo con cui si
 * accavalla, che e' l'unica risposta utile a chi sta configurando; a impedire
 * davvero il doppio prezzo e' il vincolo di esclusione del V9, che regge anche
 * quando due richieste arrivano nello stesso istante. E' la stessa divisione dei
 * compiti fra gli {@code existsBy} e gli indici unici gia' in uso nel progetto,
 * ed e' anche il motivo per cui il salvataggio passa da
 * {@link #salvaGestendoLaSovrapposizione}: la finestra fra il controllo e la
 * scrittura esiste, ed e' la' che il database ha l'ultima parola.
 *
 * <p><b>La seconda: i prezzi per giorno si sostituiscono, non si integrano.</b>
 * La PUT riceve l'insieme completo e un giorno che non compare torna a costare
 * il prezzo base — stessa forma della PUT sulle dotazioni di una tipologia, e
 * per la stessa ragione: un'operazione che si puo' ripetere senza calcolare
 * differenze. Il pezzo delicato e' che quella sostituzione deve passare da
 * {@code PeriodoTariffario.sostituisciPrezziGiorno}, perche' {@code
 * orphanRemoval} funziona solo se la stessa lista viene svuotata e riempita.
 */
@Service
@RequiredArgsConstructor
public class PeriodoTariffarioServiceImpl implements PeriodoTariffarioService {

    private final PeriodoTariffarioRepository periodoTariffarioRepository;

    /**
     * Serve solo a risolvere la tipologia del percorso: senza, un id inesistente
     * darebbe una pagina vuota invece di un 404, e non si distinguerebbe piu'
     * "questa camera non ha tariffe" da "questa camera non esiste".
     */
    private final TipologiaCameraRepository tipologiaCameraRepository;

    private final PeriodoTariffarioMapper periodoTariffarioMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(Long tipologiaCameraId, int page, int size) {
        assicuraTipologiaEsistente(tipologiaCameraId);

        Page<PeriodoTariffario> pagina = periodoTariffarioRepository
                .findByTipologiaCameraIdOrderByDataInizioAsc(tipologiaCameraId, PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Periodi tariffari recuperati",
                periodoTariffarioMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional
    public ApiBaseResponse crea(Long tipologiaCameraId, PeriodoTariffarioRequest request) {
        verificaRichiesta(request);

        // La tipologia serve come riferimento e basta: la risposta non la contiene,
        // quindi si legge senza le collezioni. E' lo stesso metodo che usano le camere
        // e le foto, e per la stessa ragione.
        TipologiaCamera tipologia = tipologiaCameraRepository.trovaSenzaCollezioni(tipologiaCameraId)
                .orElseThrow(() -> new NotFoundException("Tipologia di camera non trovata"));

        verificaSovrapposizione(tipologiaCameraId, request, null);

        PeriodoTariffario periodo = new PeriodoTariffario();
        periodo.setTipologiaCamera(tipologia);
        applicaCampi(periodo, request);

        PeriodoTariffario salvato = salvaGestendoLaSovrapposizione(periodo);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Periodo tariffario creato",
                periodoTariffarioMapper.toResponse(salvato));
    }

    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long tipologiaCameraId, Long periodoId,
                                    PeriodoTariffarioRequest request) {
        verificaRichiesta(request);

        PeriodoTariffario periodo = trovaOrElseThrow(tipologiaCameraId, periodoId);

        // Escludendo se stesso: senza, riconfermare a un periodo le proprie date
        // darebbe 409 contro il periodo stesso.
        verificaSovrapposizione(tipologiaCameraId, request, periodoId);

        applicaCampi(periodo, request);

        PeriodoTariffario salvato = salvaGestendoLaSovrapposizione(periodo);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Periodo tariffario aggiornato",
                periodoTariffarioMapper.toResponse(salvato));
    }

    @Override
    @Transactional
    public ApiBaseResponse elimina(Long tipologiaCameraId, Long periodoId) {
        PeriodoTariffario periodo = trovaOrElseThrow(tipologiaCameraId, periodoId);

        // I prezzi per giorno se ne vanno con lui: la chiave esterna ha ON DELETE
        // CASCADE lato database e la collezione ha orphanRemoval lato JPA. Sono due
        // reti per lo stesso caso, ed e' voluto — la seconda serve quando si passa di
        // qui, la prima quando si cancella la tipologia intera.
        periodoTariffarioRepository.delete(periodo);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Periodo tariffario eliminato", null);
    }

    /**
     * I controlli che riguardano la <b>sola richiesta</b>, cioe' quelli che non
     * hanno bisogno di leggere niente.
     *
     * <p>Stanno insieme e vengono per primi perche' una richiesta malformata va
     * detta malformata anche quando per giunta la tipologia non esiste: sono
     * errori di chi chiama, e farglieli scoprire uno alla volta dopo un giro di
     * query e' solo piu' lento.
     */
    private void verificaRichiesta(PeriodoTariffarioRequest request) {
        verificaOrdineDate(request);
        verificaDecimali(request);
        verificaGiorniDistinti(request);
    }

    /**
     * La data di fine non puo' precedere quella di inizio, ma puo' coincidere:
     * un periodo di una notte sola e' la notte di Capodanno, ed e' legittimo.
     *
     * <p>Il vincolo esiste anche in database ({@code CHECK} del V9); qui c'e'
     * per la ragione della regola 21 — senza, il valore arriverebbe fino a
     * Postgres e tornerebbe a chi chiama come un 500 invece che come un 400.
     */
    private void verificaOrdineDate(PeriodoTariffarioRequest request) {
        if (request.getDataFine().isBefore(request.getDataInizio())) {
            throw new BadRequestException(
                    "La data di fine del periodo non puo' precedere quella di inizio");
        }
    }

    /**
     * Rifiuta un prezzo con piu' di due decimali, sul periodo e su ognuno dei
     * suoi giorni.
     *
     * <p>Stessa regola gia' applicata al listino della tipologia, e per la
     * stessa ragione: la colonna e' {@code NUMERIC(10,2)} e Postgres
     * arrotonderebbe in silenzio, quindi la stessa risorsa direbbe due prezzi
     * diversi a seconda di quando la si chiede. Su un importo, "dipende da
     * quando lo chiedi" non e' una risposta.
     */
    private void verificaDecimali(PeriodoTariffarioRequest request) {
        assicuraDueDecimali(request.getPrezzoNotte(), "Il prezzo per notte");

        request.getPrezziGiorno().forEach(prezzo ->
                assicuraDueDecimali(prezzo.getPrezzo(), "Il prezzo di " + prezzo.getGiorno().getValue()));
    }

    private void assicuraDueDecimali(BigDecimal prezzo, String soggetto) {
        if (prezzo.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(soggetto + " non puo' avere piu' di due decimali");
        }
    }

    /**
     * Lo stesso giorno della settimana non puo' comparire due volte.
     *
     * <p>Sarebbero due prezzi per lo stesso sabato, e nessun criterio per
     * sceglierne uno: e' la sovrapposizione dei periodi in piccolo. <b>400 e non
     * 409</b>, al contrario di quella: non c'e' nessuno stato salvato con cui la
     * richiesta confligga — e' la richiesta stessa a contraddirsi, e lo si vede
     * senza leggere niente.
     *
     * <p>L'indice unico del V9 dice la stessa cosa al database. Qui il controllo
     * serve perche' arrivarci darebbe un errore di vincolo su una richiesta che
     * si poteva rifiutare guardandola.
     */
    private void verificaGiorniDistinti(PeriodoTariffarioRequest request) {
        List<String> duplicati = request.getPrezziGiorno().stream()
                .collect(Collectors.groupingBy(PrezzoGiorno::getGiorno, Collectors.counting()))
                .entrySet().stream()
                .filter(voce -> voce.getValue() > 1)
                .map(voce -> voce.getKey().getValue())
                .sorted()
                .toList();

        if (!duplicati.isEmpty()) {
            throw new BadRequestException(
                    "Ogni giorno della settimana puo' avere un prezzo solo: " + String.join(", ", duplicati));
        }
    }

    /**
     * Rifiuta un periodo che si accavalla con un altro della stessa tipologia,
     * dicendo con quale.
     *
     * <p><b>Il nome del periodo nel messaggio non e' un vezzo</b>: chi configura
     * un calendario ha sotto gli occhi delle etichette, non degli id, e "si
     * sovrappone a un periodo esistente" lo costringerebbe a cercare a mano
     * quale. E' anche il motivo per cui la query restituisce i periodi invece di
     * un {@code boolean}.
     *
     * <p>Fra <b>tipologie diverse</b> la sovrapposizione non si guarda nemmeno,
     * ed e' il caso normale: l'alta stagione e' la stessa per tutte le camere.
     */
    private void verificaSovrapposizione(Long tipologiaCameraId, PeriodoTariffarioRequest request,
                                         Long esclusa) {
        List<PeriodoTariffario> sovrapposti = periodoTariffarioRepository.trovaSovrapposti(
                tipologiaCameraId, request.getDataInizio(), request.getDataFine(), esclusa);

        if (!sovrapposti.isEmpty()) {
            PeriodoTariffario primo = sovrapposti.getFirst();
            throw new ConflictException("Le date si sovrappongono al periodo \"" + primo.getNome()
                    + "\" (" + primo.getDataInizio() + " - " + primo.getDataFine() + ")");
        }
    }

    /**
     * Copia i campi della richiesta sull'entita'. Vale per la creazione e per
     * l'aggiornamento, che e' una PUT: ogni campo viene riscritto, anche quelli
     * che la richiesta non ha cambiato.
     *
     * <p>{@code soggiornoMinimo} non ha bisogno di un default qui: lo spec ne
     * dichiara uno e il DTO generato arriva gia' con 1 quando il campo e'
     * omesso.
     */
    private void applicaCampi(PeriodoTariffario periodo, PeriodoTariffarioRequest request) {
        periodo.setNome(request.getNome());
        periodo.setDataInizio(request.getDataInizio());
        periodo.setDataFine(request.getDataFine());
        periodo.setPrezzoNotte(request.getPrezzoNotte());
        periodo.setSoggiornoMinimo(request.getSoggiornoMinimo());

        List<PrezzoGiornoSettimana> prezzi = periodoTariffarioMapper.toPrezziGiorno(request);
        periodo.sostituisciPrezziGiorno(prezzi);
    }

    /**
     * Salva traducendo in 409 la sovrapposizione che il database rifiuta.
     *
     * <p>Serve alla richiesta gemella arrivata mentre questa era a meta': il
     * controllo di {@link #verificaSovrapposizione} ha guardato una fotografia,
     * e fra quella e la scrittura c'e' una finestra. Senza questo blocco la
     * seconda richiesta prenderebbe un 500 per un errore che non e' un guasto —
     * stessa forma gia' usata da {@code MediaCameraServiceImpl} e da
     * {@code OspiteServiceImpl} sui loro indici unici.
     *
     * <p>Il {@code saveAndFlush} e' quello che rende possibile intercettarla:
     * con un {@code save} normale l'INSERT partirebbe alla chiusura della
     * transazione, cioe' fuori da questo try.
     */
    private PeriodoTariffario salvaGestendoLaSovrapposizione(PeriodoTariffario periodo) {
        try {
            return periodoTariffarioRepository.saveAndFlush(periodo);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Le date si sovrappongono a un altro periodo di questa tipologia");
        }
    }

    /**
     * Il periodo del percorso, <b>cercato dentro la sua tipologia</b>.
     *
     * <p>Un periodo che esiste ma appartiene a un'altra tipologia da' 404 come
     * se non ci fosse: la tipologia fa parte della chiave di ricerca, non e' un
     * controllo aggiunto dopo. Stessa scelta gia' fatta per le foto e per gli
     * ospiti, e per lo stesso motivo — un controllo lasciato a chi chiama e' un
     * controllo che il prossimo metodo dimentica.
     */
    private PeriodoTariffario trovaOrElseThrow(Long tipologiaCameraId, Long periodoId) {
        assicuraTipologiaEsistente(tipologiaCameraId);

        return periodoTariffarioRepository.findByIdAndTipologiaCameraId(periodoId, tipologiaCameraId)
                .orElseThrow(() -> new NotFoundException("Periodo tariffario non trovato"));
    }

    /**
     * <b>Due 404 con due messaggi diversi</b>: "tipologia non trovata" e
     * "periodo tariffario non trovato". Costa una {@code existsById} in piu' su
     * ogni scrittura, e la paga volentieri — chi ha sbagliato l'id della
     * tipologia e chi ha sbagliato quello del periodo devono poterlo capire
     * senza provare a indovinare quale dei due.
     *
     * <p>Per l'elenco non e' nemmeno una scelta di messaggi ma l'unico modo di
     * rispondere: li' di periodi non se ne cerca nessuno, e senza questo
     * controllo una tipologia inesistente darebbe una pagina vuota — cioe' la
     * stessa risposta di un calendario non ancora configurato.
     */
    private void assicuraTipologiaEsistente(Long tipologiaCameraId) {
        if (!tipologiaCameraRepository.existsById(tipologiaCameraId)) {
            throw new NotFoundException("Tipologia di camera non trovata");
        }
    }
}
