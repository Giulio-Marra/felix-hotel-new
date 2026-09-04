package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.PagamentoRequest;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import com.felixhotel.backend.entity.Pagamento;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.PagamentoMapper;
import com.felixhotel.backend.repository.ImpostazioniHotelRepository;
import com.felixhotel.backend.repository.PagamentoRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.security.AccessoPrenotazioni;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.service.PagamentoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Il registro dei pagamenti: chi ha versato quanto su quale prenotazione.
 *
 * <p><b>Registra incassi avvenuti, non li esegue.</b> Nessun denaro si muove qui dentro:
 * questo Service scrive nel registro quel che e' gia' successo al banco o sul conto. Il
 * pagamento online, in cui il denaro si muove davvero, arrivera' con l'integrazione del
 * fornitore.
 *
 * <p><b>Tre numeri si ricavano e nessuno si memorizza</b>, ed e' la decisione portante:
 * <i>incassato</i> e' la somma dei versamenti, <i>caparra dovuta</i> una percentuale
 * dell'importo totale, <i>residuo</i> una sottrazione. Nessuno dei tre ha una colonna,
 * perche' un totale scritto e' una seconda verita' libera di divergere dalla prima — e in
 * un registro di denaro due numeri che non tornano sono peggio di un numero solo.
 *
 * <p><b>Le due macchine sono indipendenti.</b> Confermare non pretende di aver incassato e
 * incassare non conferma niente: si versa la caparra su una prenotazione ancora in attesa
 * (e' anzi il caso normale, la caparra precede la conferma) e si conferma senza aver
 * incassato (chi prenota per telefono e paga arrivando). Per la stessa ragione lo stato del
 * pagamento non e' un sesto valore di {@link StatoPrenotazione}: quello dice chi occupa una
 * camera, e una prenotazione confermata la occupa tanto se e' pagata quanto se non lo e'.
 *
 * <p><b>Quel che si registra non si cancella</b>: non c'e' nessun metodo che tolga un
 * pagamento. Un incasso sbagliato si corregge come si corregge in contabilita', con una
 * scrittura che lo compensa — che questo branch non ha ancora, e che sta nei debiti.
 */
@Service
public class PagamentoServiceImpl implements PagamentoService {

    /** I decimali di un importo in euro, cioe' quelli della colonna: {@code NUMERIC(10,2)}. */
    private static final int DECIMALI_EURO = 2;

    private static final BigDecimal CENTO = new BigDecimal("100");

    private final PagamentoRepository pagamentoRepository;
    private final PrenotazioneRepository prenotazioneRepository;
    private final ImpostazioniHotelRepository impostazioniHotelRepository;
    private final StaffRepository staffRepository;
    private final PagamentoMapper pagamentoMapper;
    private final ApiResponseMapper apiResponseMapper;
    private final AccessoPrenotazioni accessoPrenotazioni;
    private final ChiamanteCorrente chiamanteCorrente;
    private final Clock clock;

    @Autowired
    public PagamentoServiceImpl(PagamentoRepository pagamentoRepository,
                                PrenotazioneRepository prenotazioneRepository,
                                ImpostazioniHotelRepository impostazioniHotelRepository,
                                StaffRepository staffRepository,
                                PagamentoMapper pagamentoMapper,
                                ApiResponseMapper apiResponseMapper,
                                AccessoPrenotazioni accessoPrenotazioni,
                                ChiamanteCorrente chiamanteCorrente) {
        this(pagamentoRepository, prenotazioneRepository, impostazioniHotelRepository, staffRepository,
                pagamentoMapper, apiResponseMapper, accessoPrenotazioni, chiamanteCorrente,
                Clock.systemDefaultZone());
    }

    /**
     * Costruttore per i test, che con un {@link Clock} pilotabile decidono che ora e':
     * serve a provare il rifiuto di un incasso datato nel futuro senza dipendere
     * dall'istante di esecuzione.
     */
    public PagamentoServiceImpl(PagamentoRepository pagamentoRepository,
                                PrenotazioneRepository prenotazioneRepository,
                                ImpostazioniHotelRepository impostazioniHotelRepository,
                                StaffRepository staffRepository,
                                PagamentoMapper pagamentoMapper,
                                ApiResponseMapper apiResponseMapper,
                                AccessoPrenotazioni accessoPrenotazioni,
                                ChiamanteCorrente chiamanteCorrente,
                                Clock clock) {
        this.pagamentoRepository = pagamentoRepository;
        this.prenotazioneRepository = prenotazioneRepository;
        this.impostazioniHotelRepository = impostazioniHotelRepository;
        this.staffRepository = staffRepository;
        this.pagamentoMapper = pagamentoMapper;
        this.apiResponseMapper = apiResponseMapper;
        this.accessoPrenotazioni = accessoPrenotazioni;
        this.chiamanteCorrente = chiamanteCorrente;
        this.clock = clock;
    }

    /**
     * Il riepilogo di una prenotazione, ricalcolato adesso.
     *
     * <p><b>Elenco vuoto e non 404 se non ha ancora pagato niente</b>: "non ha versato
     * niente" e' una risposta, e i tre numeri del riepilogo ci sono lo stesso — anzi, e'
     * proprio la lettura che serve a chi deve chiedere la caparra.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse elenca(Long prenotazioneId) {
        Prenotazione prenotazione = accessoPrenotazioni.visibileOrElseThrow(prenotazioneId);
        List<Pagamento> pagamenti = pagamentoRepository
                .findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(prenotazioneId);

        // La somma la fa il database anche se le righe sono gia' qui: e' lo stesso
        // numero che il controllo dell'incasso confronta col dovuto, e farlo dire da due
        // pezzi di codice diversi vorrebbe dire due risposte possibili alla domanda
        // "quanto e' entrato". Su una manciata di righe la query in piu' non si misura.
        BigDecimal incassato = pagamentoRepository.sommaIncassata(prenotazioneId);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Pagamenti della prenotazione",
                pagamentoMapper.toRiepilogo(prenotazione.getImportoTotale(),
                        caparraDovuta(prenotazione), incassato,
                        residuo(prenotazione, incassato), pagamenti));
    }

    /**
     * Scrive un incasso.
     *
     * <p><b>L'ordine dei controlli non e' casuale</b>: prima chi chiama (un cliente non
     * arriva nemmeno a sapere se quella prenotazione esista), poi la prenotazione, poi il
     * suo stato, poi i decimali dell'importo, e <b>per ultimo il residuo</b> — che e'
     * l'unico che ha bisogno del lock, e un lock si prende il piu' tardi possibile.
     */
    @Override
    @Transactional
    public ApiBaseResponse registra(Long prenotazioneId, PagamentoRequest request) {
        assicuraPersonale();

        Prenotazione prenotazione = accessoPrenotazioni.visibileOrElseThrow(prenotazioneId);

        // Incassare su un soggiorno che non avverra' e' quasi sempre uno sbaglio di
        // persona. E se davvero l'albergo trattiene una penale, quella e' una scrittura
        // contabile con una causale sua: registrarla come "pagamento della prenotazione"
        // direbbe che quel soggiorno e' stato pagato, che non e' vero.
        if (prenotazione.getStato() == StatoPrenotazione.ANNULLATA) {
            throw new ConflictException(
                    "La prenotazione e' annullata: su un soggiorno che non avverra' non si incassa");
        }

        verificaDecimali(request.getImporto());
        LocalDateTime incassatoIl = istanteIncasso(request);

        verificaResiduo(prenotazione, request.getImporto());

        Pagamento pagamento = new Pagamento();
        pagamento.setPrenotazione(prenotazione);
        pagamento.setImporto(request.getImporto());
        pagamento.setMetodo(pagamentoMapper.toMetodoEntity(request.getMetodo()));
        pagamento.setIncassatoIl(incassatoIl);
        pagamento.setRiferimento(request.getRiferimento());
        pagamento.setRegistratoDa(staffChiamante());

        Pagamento salvato = pagamentoRepository.save(pagamento);

        return apiResponseMapper.toResponse(HttpStatus.CREATED, "Pagamento registrato",
                pagamentoMapper.toResponse(salvato));
    }

    /**
     * Quanto si sarebbe dovuto versare in anticipo, secondo la politica di <b>oggi</b>.
     *
     * <p><b>Non e' una fotografia come l'importo totale</b>, ed e' una differenza voluta:
     * il prezzo e' un accordo gia' preso col cliente e resta quello del giorno della
     * prenotazione, la caparra e' una regola di incasso della struttura e vale quella in
     * vigore. Se l'albergo alza la percentuale, cambia quel che risulta dovuto anche sulle
     * prenotazioni gia' prese — che e' esattamente cosa vuol dire cambiare una politica di
     * incasso.
     *
     * <p>Arrotonda a due decimali: il 30% di 361,55 fa 108,465, e un centesimo va deciso
     * qui invece di lasciarlo decidere al troncamento di Postgres.
     */
    private BigDecimal caparraDovuta(Prenotazione prenotazione) {
        BigDecimal percentuale = trovaImpostazioni().getPercentualeCaparra();

        return prenotazione.getImportoTotale()
                .multiply(percentuale)
                .divide(CENTO, DECIMALI_EURO, RoundingMode.HALF_UP);
    }

    /**
     * Quanto resta da incassare.
     *
     * <p><b>Non e' mai negativo</b>, e non perche' venga tagliato: {@link #verificaResiduo}
     * rifiuta gli incassi che supererebbero il totale, quindi la sottrazione non puo'
     * scendere sotto zero. Aggiungere qui un {@code max(0)} vorrebbe dire un ramo che
     * nessuna richiesta puo' percorrere, cioe' una difesa che nessun test puo' vedere
     * agire.
     */
    private BigDecimal residuo(Prenotazione prenotazione, BigDecimal incassato) {
        return prenotazione.getImportoTotale().subtract(incassato);
    }

    /**
     * Che l'incasso non superi quel che resta da pagare.
     *
     * <p><b>Non e' pignoleria contabile ma la difesa dall'errore di digitazione</b>: un
     * 1080 al posto di 108 e' lo sbaglio che capita davvero, e senza questo controllo
     * finirebbe nel registro senza che nessuno se ne accorga fino ai conti di fine mese.
     * Il messaggio dice il residuo, cosi' chi ha sbagliato una cifra lo vede subito.
     *
     * <p><b>Il lock serializza chi incassa sulla stessa prenotazione</b>, ed e' lo stesso
     * ragionamento del lock sulla vendita: leggere la somma e scrivere la riga sono due
     * gesti, e fra i due un'altra transazione puo' fare lo stesso conto — due saldi
     * registrati insieme passerebbero tutti e due, e il totale incassato supererebbe il
     * dovuto senza che nessuno abbia sbagliato a digitare. La riga bloccata e' la
     * prenotazione, cioe' l'oggetto di cui si sta contando il residuo.
     */
    private void verificaResiduo(Prenotazione prenotazione, BigDecimal importo) {
        prenotazioneRepository.bloccaPerIncasso(prenotazione.getId());

        BigDecimal residuo = residuo(prenotazione, pagamentoRepository.sommaIncassata(prenotazione.getId()));

        if (importo.compareTo(residuo) > 0) {
            throw new ConflictException("Su questa prenotazione restano da incassare "
                    + residuo.setScale(DECIMALI_EURO, RoundingMode.HALF_UP)
                    + " euro: non si puo' registrare un versamento di " + importo);
        }
    }

    /**
     * L'istante dell'incasso: quello dichiarato, oppure adesso.
     *
     * <p><b>Un incasso nel futuro e' 400</b>: un versamento che deve ancora avvenire non e'
     * un versamento, e registrarlo vorrebbe dire un registro che dice di aver preso soldi
     * che non ci sono. E' l'unico controllo sulla data — indietro non c'e' limite, perche'
     * un bonifico di tre settimane fa registrato oggi e' un caso normale e non un errore.
     */
    private LocalDateTime istanteIncasso(PagamentoRequest request) {
        // Letto una volta sola in una variabile, e non e' pignoleria: chiamare due volte
        // un getter dichiarato @Nullable — una per il controllo e una per l'uso — e'
        // il rilievo NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE che SpotBugs ha gia' fermato
        // due volte in questo progetto. Per il compilatore sono due letture diverse.
        OffsetDateTime dichiarato = request.getIncassatoIl();

        if (dichiarato == null) {
            return LocalDateTime.now(clock);
        }

        LocalDateTime incassatoIl = pagamentoMapper.toLocale(dichiarato);
        if (incassatoIl.isAfter(LocalDateTime.now(clock))) {
            throw new BadRequestException(
                    "La data dell'incasso e' nel futuro: si registra un versamento avvenuto");
        }

        return incassatoIl;
    }

    /**
     * Rifiuta un importo con piu' di due decimali.
     *
     * <p>Stessa regola dei prezzi e delle aliquote, e per la stessa ragione: la colonna e'
     * {@code NUMERIC(10,2)} e Postgres troncherebbe in silenzio, quindi la risposta
     * direbbe un numero e il registro ne conserverebbe un altro. Su una somma di denaro,
     * "dipende da quando la guardi" non e' una risposta.
     */
    private void verificaDecimali(BigDecimal importo) {
        if (importo.stripTrailingZeros().scale() > DECIMALI_EURO) {
            throw new BadRequestException("L'importo non puo' avere piu' di due decimali");
        }
    }

    /**
     * Che chi chiama sia davvero del personale, e non solo che ne porti il ruolo. Copia di
     * forma da {@code AlloggiatiServiceImpl}, dove sta anche il perche' esteso.
     */
    private void assicuraPersonale() {
        if (!chiamanteCorrente.personale(chiamanteCorrente.autenticato())) {
            throw new UnauthorizedException("L'account autenticato non e' quello di un membro del personale");
        }
    }

    /**
     * Chi sta registrando l'incasso, da scrivere nel registro.
     *
     * <p>Non ricontrolla il tipo dell'account: ci si arriva solo dopo
     * {@link #assicuraPersonale()}. Non trovare la riga resta invece possibile ed e' un
     * 401, come altrove: il token e' valido per un account che non c'e' piu'.
     */
    private Staff staffChiamante() {
        AppUserPrincipal chiamante = chiamanteCorrente.autenticato();

        return staffRepository.findById(chiamante.getUserId())
                .orElseThrow(() -> new UnauthorizedException("L'account autenticato non esiste piu'"));
    }

    /**
     * L'unica riga dell'anagrafica, da cui si legge la percentuale della caparra.
     *
     * <p>Se non c'e', il guasto non e' del client: la riga la scrive la migration. Da qui
     * l'{@link IllegalStateException}, cioe' un 500 — stessa scelta di
     * {@code ImpostazioniHotelServiceImpl}, dove sta il perche' esteso.
     */
    private ImpostazioniHotel trovaImpostazioni() {
        return impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA)
                .orElseThrow(() -> new IllegalStateException(
                        "Le impostazioni della struttura non esistono: "
                                + "la riga creata dalla migration e' stata rimossa"));
    }
}
