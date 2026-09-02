package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipoAlloggiato;
import com.felixhotel.backend.entity.TipoCodifica;
import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.AlloggiatiMapper;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.VoceCodificaRepository;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.service.AlloggiatiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementazione dell'export delle schedine alloggiati.
 *
 * <p><b>E' fatto di tre stadi, e tenerli separati e' l'unica decisione di struttura
 * del branch</b>: prima si <i>raccoglie</i> chi e' arrivato, poi si <i>verifica</i>
 * che ogni schedina sia compilabile, poi si <i>scrive</i>. L'ordine non e' comodita':
 * il file va prodotto tutto o niente. Un export che scrivesse le prime venti righe e
 * si fermasse sulla ventunesima lascerebbe chi sta al banco con un file parziale che
 * sembra buono, e la Questura non ha modo di sapere che mancano le ultime persone.
 * Quindi ogni controllo passa <b>su tutti</b> prima che si scriva un solo carattere.
 *
 * <p><b>Il permesso non finisce sul Controller</b>, come per gli ospiti e per la
 * stessa ragione: qui dentro passano documenti d'identita' di persone che non sono
 * nemmeno clienti dell'albergo. {@code @PreAuthorize} guarda il ruolo, questo Service
 * pretende anche il tipo dell'account.
 *
 * <p><b>Perche' i 409 nominano la persona.</b> Un messaggio come "dati incompleti"
 * costringerebbe a riaprire una per una le prenotazioni della giornata per scoprire
 * chi manca. Il messaggio dice cognome, nome e campo, ed e' cio' che rende
 * l'operazione riparabile in un minuto invece che in mezz'ora. <b>Non e' una fuga di
 * dati</b>: a questa rotta arriva solo chi ha appena avuto quei documenti in mano.
 *
 * <p><b>Nessun dato personale finisce nei log</b>, e la differenza con la riga qui
 * sopra e' voluta: il messaggio va a chi ha chiesto l'export ed e' autorizzato a
 * leggerlo, un log lo legge chiunque abbia accesso alla macchina. Qui non si logga
 * niente, come nel resto del progetto.
 */
@Service
@RequiredArgsConstructor
public class AlloggiatiServiceImpl implements AlloggiatiService {

    /**
     * Gli stati che vogliono dire "questa persona e' arrivata davvero".
     *
     * <p><b>Non e' {@code StatoPrenotazione.occupaCamera()}</b>, che comprende anche
     * CONFERMATA, ed e' la stessa distinzione gia' fatta il 2026-08-27 fra "occupa una
     * camera" e "c'e' qualcuno dentro". Qui la domanda e' una terza ancora:
     * <i>qualcuno si e' presentato al banco?</i>. Una prenotazione confermata e mai
     * arrivata non e' un arrivo, e comunicarla vorrebbe dire dichiarare alla Questura
     * che qualcuno dorme qui quando non c'e' — cioe' scrivere il falso in una
     * comunicazione di legge, che e' peggio di non mandarla.
     *
     * <p>CHECK_OUT c'e' perche' un export si rigenera anche giorni dopo, quando gli
     * ospiti se ne sono gia' andati: e' anzi il caso normale quando il portale rifiuta
     * un file e lo si rifa'.
     */
    private static final List<StatoPrenotazione> STATI_ARRIVATI =
            List.of(StatoPrenotazione.CHECK_IN, StatoPrenotazione.CHECK_OUT);

    private final PrenotazioneRepository prenotazioneRepository;
    private final OspiteRepository ospiteRepository;
    private final VoceCodificaRepository voceCodificaRepository;
    private final AlloggiatiMapper alloggiatiMapper;
    private final ApiResponseMapper apiResponseMapper;
    private final ChiamanteCorrente chiamanteCorrente;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse esportaSchedine(LocalDate data) {
        assicuraPersonale();

        List<Prenotazione> arrivi =
                prenotazioneRepository.arriviDelGiorno(data, STATI_ARRIVATI);

        // Gli ospiti di tutte le prenotazioni in una query sola, poi raggruppati in
        // memoria: gli arrivi di una giornata sono decine di prenotazioni, e una query
        // per ognuna sarebbe una N+1 su un'operazione che si fa ogni mattina.
        Map<Long, List<Ospite>> ospitiPerPrenotazione = ospiteRepository
                .findByPrenotazioneIdInOrderByPrenotazioneIdAscIdAsc(
                        arrivi.stream().map(Prenotazione::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ospite -> ospite.getPrenotazione().getId()));

        // Stadio 1: si mette in fila chi va sul file, gruppo per gruppo.
        List<Schedina> schedine = new ArrayList<>();
        for (Prenotazione prenotazione : arrivi) {
            List<Ospite> ospiti = ospitiPerPrenotazione.getOrDefault(prenotazione.getId(), List.of());
            verificaGruppo(prenotazione, ospiti);
            ospiti.forEach(ospite -> {
                verificaCompletezza(ospite);
                schedine.add(new Schedina(prenotazione, ospite));
            });
        }

        // Stadio 2: le codifiche, quattro query in tutto, e il controllo che ogni
        // codice che finira' sul file esista davvero.
        Codifiche codifiche = caricaCodifiche(schedine);
        schedine.forEach(schedina -> verificaCodici(schedina, codifiche));

        // Stadio 3: solo adesso si scrive.
        String contenuto = schedine.stream()
                .map(schedina -> TracciatoAlloggiati.formatta(rigaDi(schedina, codifiche)))
                .collect(Collectors.joining(TracciatoAlloggiati.FINE_RIGA));
        if (!contenuto.isEmpty()) {
            // Anche l'ultima riga e' terminata: un file di record a lunghezza fissa non
            // finisce a meta' del terminatore. Con zero schedine il file resta vuoto
            // davvero, invece di contenere un CRLF solo.
            contenuto += TracciatoAlloggiati.FINE_RIGA;
        }

        return apiResponseMapper.toResponse(HttpStatus.OK,
                schedine.size() + " schedine per gli arrivi del " + data,
                alloggiatiMapper.toResponse(data, schedine.size(), contenuto));
    }

    /**
     * Che chi chiama sia davvero del personale, e non solo che ne porti il ruolo.
     * Copia di forma da {@code OspiteServiceImpl}, dove sta anche il perche' esteso:
     * il ruolo dice cosa un account puo' fare, il tipo dice dove vive, e per arrivare
     * ai documenti degli ospiti servono tutte e due le risposte.
     */
    private void assicuraPersonale() {
        if (!chiamanteCorrente.personale(chiamanteCorrente.autenticato())) {
            throw new UnauthorizedException("L'account autenticato non e' quello di un membro del personale");
        }
    }

    /**
     * Che il gruppo di una prenotazione stia in piedi come lo intende il tracciato.
     *
     * <p><b>Perche' questo controllo non sta sulla registrazione dell'ospite.</b>
     * Pretenderlo li' vorrebbe dire rifiutare la seconda riga di un modulo che si sta
     * ancora compilando — chi registra quattro persone passa per forza da uno stato in
     * cui ce n'e' una sola — cioe' obbligare chi sta al banco a inserirle in un ordine
     * preciso. Il gruppo si puo' giudicare solo quando e' finito, e l'unico momento in
     * cui e' finito per certo e' quando lo si comunica.
     *
     * <p><b>Due persone entrambe "ospite singolo" sono legittime</b>, e non e' una
     * svista: due colleghi che dividono una stanza presentano due documenti e fanno
     * due schedine indipendenti. La regola vera non e' "ci vuole un capo", e' che
     * <i>chi dichiara di essere accompagnato deve avere qualcuno che lo accompagna</i>.
     */
    private void verificaGruppo(Prenotazione prenotazione, List<Ospite> ospiti) {
        if (ospiti.isEmpty()) {
            // Raggiungibile: il check-in pretende gli ospiti al completo, ma cancellarne
            // uno dopo e' permesso (chi doveva venire non e' venuto). Cancellarli tutti
            // lascia una prenotazione arrivata e senza registro, che e' un buco vero.
            throw new ConflictException("La prenotazione " + prenotazione.getId()
                    + " risulta arrivata ma non ha nessun ospite registrato");
        }

        long capifamiglia = conta(ospiti, TipoAlloggiato.CAPOFAMIGLIA);
        long capigruppo = conta(ospiti, TipoAlloggiato.CAPOGRUPPO);

        if (capifamiglia > 1 || capigruppo > 1) {
            throw new ConflictException("La prenotazione " + prenotazione.getId()
                    + " ha piu' di un capo dichiarato: sulla schedina il capo e' uno solo");
        }
        if (conta(ospiti, TipoAlloggiato.FAMILIARE) > 0 && capifamiglia == 0) {
            throw new ConflictException("La prenotazione " + prenotazione.getId()
                    + " ha dei familiari ma nessun capofamiglia");
        }
        if (conta(ospiti, TipoAlloggiato.MEMBRO_GRUPPO) > 0 && capigruppo == 0) {
            throw new ConflictException("La prenotazione " + prenotazione.getId()
                    + " ha dei membri del gruppo ma nessun capogruppo");
        }
    }

    private long conta(List<Ospite> ospiti, TipoAlloggiato tipo) {
        return ospiti.stream().filter(ospite -> ospite.getTipoAlloggiato() == tipo).count();
    }

    /**
     * Che di questa persona ci sia tutto quel che la schedina pretende.
     *
     * <p>I sei campi della schedina sono facoltativi sull'ospite di proposito (V13):
     * il registro si scrive al banco anche di notte, la schedina si manda entro
     * ventiquattro ore, e un dato mancante deve fermare la seconda e non la prima.
     * Questo metodo e' il posto in cui quell'obbligo torna a valere.
     */
    private void verificaCompletezza(Ospite ospite) {
        TipoAlloggiato tipoAlloggiato = ospite.getTipoAlloggiato();
        if (tipoAlloggiato == null) {
            throw incompleta(ospite, "manca il tipo di alloggiato");
        }
        if (ospite.getSesso() == null) {
            throw incompleta(ospite, "manca il sesso");
        }
        if (ospite.getCittadinanza() == null) {
            throw incompleta(ospite, "manca la cittadinanza");
        }

        boolean natoInItalia = ospite.getComuneNascita() != null;
        boolean natoAllEstero = ospite.getStatoNascita() != null;
        if (!natoInItalia && !natoAllEstero) {
            throw incompleta(ospite, "manca il luogo di nascita");
        }
        if (natoInItalia && natoAllEstero) {
            // Il CHECK del V13 lo vieta gia': se si arriva qui, qualcuno ha scritto nel
            // database a mano. Il controllo resta perche' la riga sbagliata produrrebbe
            // altrimenti una schedina con due caselle piene dove ne va una.
            throw incompleta(ospite, "ha sia il comune sia lo stato di nascita");
        }

        if (!tipoAlloggiato.portaDocumento()) {
            return;
        }
        if (ospite.getTipoDocumento() == null) {
            // Il caso vero dietro questo messaggio e' un minorenne registrato senza
            // documento (V10) a cui e' stato dato un tipo di alloggiato da capo. Il
            // messaggio dice cosa fare invece di dire solo cosa non va: la strada
            // giusta e' registrarlo sotto chi lo accompagna.
            throw incompleta(ospite, "e' dichiarato come " + tipoAlloggiato.name()
                    + " ma non ha un documento: chi non ne ha uno va registrato come"
                    + " familiare o membro del gruppo");
        }
        if (ospite.getLuogoRilascioDocumento() == null) {
            throw incompleta(ospite, "manca il luogo di rilascio del documento");
        }
    }

    private ConflictException incompleta(Ospite ospite, String cosa) {
        return new ConflictException("Schedina non compilabile per "
                + ospite.getCognome() + " " + ospite.getNome() + ": " + cosa);
    }

    /**
     * Le voci di codifica che servono a questo export, in quattro query.
     *
     * <p><b>Si chiedono solo i codici nominati</b> e non le famiglie intere: quella dei
     * comuni ne ha circa ottomila, e un giorno di arrivi ne nomina una decina.
     *
     * <p>Il luogo di rilascio del documento finisce in tutte e due le famiglie
     * geografiche, ed e' l'unico campo del progetto che lo faccia: il tracciato ha una
     * casella sola per "comune italiano oppure stato estero", quindi si cerca in
     * entrambe e vale se lo si trova in una.
     */
    private Codifiche caricaCodifiche(List<Schedina> schedine) {
        Set<String> luoghiRilascio = valori(schedine, s -> s.ospite().getLuogoRilascioDocumento());

        Set<String> comuni = new HashSet<>(valori(schedine, s -> s.ospite().getComuneNascita()));
        comuni.addAll(luoghiRilascio);

        Set<String> stati = new HashSet<>(valori(schedine, s -> s.ospite().getStatoNascita()));
        stati.addAll(valori(schedine, s -> s.ospite().getCittadinanza()));
        stati.addAll(luoghiRilascio);

        Set<String> tipiDocumento = schedine.stream()
                .map(s -> s.ospite().getTipoDocumento())
                .filter(Objects::nonNull)
                .map(CodiciAlloggiati::codice)
                .collect(Collectors.toSet());

        Set<String> tipiAlloggiato = schedine.stream()
                .map(s -> CodiciAlloggiati.codice(s.ospite().getTipoAlloggiato()))
                .collect(Collectors.toSet());

        return new Codifiche(
                comuniPerCodice(comuni),
                codici(TipoCodifica.STATO, stati),
                codici(TipoCodifica.TIPO_DOCUMENTO, tipiDocumento),
                codici(TipoCodifica.TIPO_ALLOGGIATO, tipiAlloggiato));
    }

    private Set<String> valori(List<Schedina> schedine, Function<Schedina, String> campo) {
        return schedine.stream().map(campo).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * I comuni per codice: dei comuni serve la riga intera, perche' il tracciato
     * chiede la <b>provincia</b> in una casella sua e quella sta scritta li'. Copiarla
     * sull'ospite sarebbe stata una seconda fonte per lo stesso fatto.
     */
    private Map<String, VoceCodifica> comuniPerCodice(Set<String> codici) {
        return voceCodificaRepository.findByTipoAndCodiceIn(TipoCodifica.COMUNE, codici).stream()
                .collect(Collectors.toMap(VoceCodifica::getCodice, Function.identity(),
                        // La coppia (tipo, codice) e' unica per l'indice del V12, quindi due
                        // righe con lo stesso codice non esistono. La funzione di merge c'e'
                        // perche' toMap la pretende, e tenere la prima e' la scelta che non
                        // nasconde niente: se un giorno l'indice sparisse, il file sarebbe
                        // deterministico invece di dipendere dall'ordine della query.
                        (prima, seconda) -> prima, HashMap::new));
    }

    private Set<String> codici(TipoCodifica tipo, Set<String> richiesti) {
        return voceCodificaRepository.findByTipoAndCodiceIn(tipo, richiesti).stream()
                .map(VoceCodifica::getCodice)
                .collect(Collectors.toSet());
    }

    /**
     * Che ogni codice che finira' sul file esista nella codifica pubblicata dal
     * Ministero.
     *
     * <p><b>E' la rete sotto {@link CodiciAlloggiati}</b>, ed e' il motivo per cui
     * quella classe puo' permettersi di contenere costanti che nessuno ha ancora
     * confrontato col file del Ministero: una traduzione sbagliata non produce una
     * schedina rifiutata due giorni dopo senza spiegazioni, produce un 409 che dice
     * quale codice manca in quale famiglia.
     *
     * <p><b>Copre anche il caso piu' probabile di tutti</b>, che non e' una costante
     * sbagliata ma un'installazione nuova: le tabelle di codifica nascono vuote di
     * proposito (V12) e vanno importate dal portale. Senza questo controllo il primo
     * export di una struttura appena installata produrrebbe un file di codici
     * inventati — cioe' esattamente il difetto che la quarta riga della regola 24
     * esiste per evitare.
     */
    private void verificaCodici(Schedina schedina, Codifiche codifiche) {
        Ospite ospite = schedina.ospite();

        assicuraCodice(codifiche.tipiAlloggiato(), CodiciAlloggiati.codice(ospite.getTipoAlloggiato()),
                TipoCodifica.TIPO_ALLOGGIATO, ospite);

        if (ospite.getComuneNascita() != null) {
            VoceCodifica comune = codifiche.comuni().get(ospite.getComuneNascita());
            if (comune == null) {
                throw codiceIgnoto(ospite, ospite.getComuneNascita(), TipoCodifica.COMUNE);
            }
            if (comune.getProvincia() == null) {
                // Non e' un codice sbagliato, e' una riga importata senza provincia: il
                // tracciato ha una casella per lei e lasciarla vuota fa scartare la
                // schedina. Il messaggio manda a rifare l'import, che e' la riparazione.
                throw new ConflictException("Il comune " + comune.getCodice() + " ("
                        + comune.getDescrizione() + ") non ha la provincia nella codifica importata:"
                        + " la schedina la pretende, va reimportata la famiglia COMUNE");
            }
        } else {
            assicuraCodice(codifiche.stati(), ospite.getStatoNascita(), TipoCodifica.STATO, ospite);
        }

        assicuraCodice(codifiche.stati(), ospite.getCittadinanza(), TipoCodifica.STATO, ospite);

        if (ospite.getTipoDocumento() != null) {
            assicuraCodice(codifiche.tipiDocumento(), CodiciAlloggiati.codice(ospite.getTipoDocumento()),
                    TipoCodifica.TIPO_DOCUMENTO, ospite);

            // L'unico campo cercato in due famiglie: il tracciato ha una casella sola per
            // "dove e' stato rilasciato", e un documento puo' essere stato emesso da un
            // comune italiano o da uno stato estero.
            String luogo = ospite.getLuogoRilascioDocumento();
            if (!codifiche.comuni().containsKey(luogo) && !codifiche.stati().contains(luogo)) {
                throw new ConflictException("Il luogo di rilascio " + luogo + " di "
                        + ospite.getCognome() + " " + ospite.getNome()
                        + " non esiste ne' fra i comuni ne' fra gli stati importati");
            }
        }
    }

    private void assicuraCodice(Set<String> importati, String codice, TipoCodifica famiglia, Ospite ospite) {
        if (!importati.contains(codice)) {
            throw codiceIgnoto(ospite, codice, famiglia);
        }
    }

    private ConflictException codiceIgnoto(Ospite ospite, String codice, TipoCodifica famiglia) {
        return new ConflictException("Il codice " + codice + " di " + ospite.getCognome() + " "
                + ospite.getNome() + " non esiste nella codifica " + famiglia.name()
                + ": va importata da GET /api/codifiche/" + famiglia.name());
    }

    /**
     * La riga del tracciato per una schedina gia' verificata.
     *
     * <p>Qui non c'e' piu' nessun controllo, ed e' il segno che i due stadi precedenti
     * hanno fatto il loro lavoro: tutto quel che si legge da qui in avanti e' gia'
     * stato provato esistente. Il metodo traduce e basta.
     */
    private RigaSchedina rigaDi(Schedina schedina, Codifiche codifiche) {
        Ospite ospite = schedina.ospite();
        Prenotazione prenotazione = schedina.prenotazione();

        VoceCodifica comune = ospite.getComuneNascita() == null
                ? null : codifiche.comuni().get(ospite.getComuneNascita());

        boolean conDocumento = ospite.getTipoAlloggiato().portaDocumento();

        return new RigaSchedina(
                CodiciAlloggiati.codice(ospite.getTipoAlloggiato()),
                prenotazione.getDataCheckIn(),
                notti(prenotazione),
                ospite.getCognome(),
                ospite.getNome(),
                CodiciAlloggiati.codice(ospite.getSesso()),
                ospite.getDataNascita(),
                comune == null ? null : comune.getCodice(),
                comune == null ? null : comune.getProvincia(),
                ospite.getStatoNascita(),
                ospite.getCittadinanza(),
                // I tre campi del documento restano vuoti per chi e' accompagnato, anche se
                // sull'ospite ci fossero: e' chi lo accompagna ad averlo esibito, e il
                // tracciato quelle caselle le vuole in bianco.
                conDocumento ? CodiciAlloggiati.codice(ospite.getTipoDocumento()) : null,
                conDocumento ? ospite.getNumeroDocumento() : null,
                conDocumento ? ospite.getLuogoRilascioDocumento() : null);
    }

    /**
     * Le notti prenotate. Non passa da {@code DurataSoggiorno} perche' quello serve al
     * calcolo del prezzo e porta con se' i suoi limiti; qui serve un numero, ed e' la
     * stessa sottrazione che il tracciato chiama "giorni di permanenza".
     */
    private int notti(Prenotazione prenotazione) {
        return (int) ChronoUnit.DAYS.between(prenotazione.getDataCheckIn(), prenotazione.getDataCheckOut());
    }

    /**
     * Una persona da mettere sul file, con la prenotazione da cui prende le date.
     * Esiste per non dover ricercare la prenotazione di ogni ospite negli stadi
     * successivi: e' gia' in mano quando si costruisce la fila.
     */
    private record Schedina(Prenotazione prenotazione, Ospite ospite) {
    }

    /**
     * Le codifiche che servono a questo export, gia' caricate.
     *
     * <p>Dei comuni si tiene la riga intera perche' ne serve la provincia; delle altre
     * tre famiglie basta sapere <b>se il codice esiste</b>, quindi bastano i codici. La
     * differenza e' nei tipi apposta: un {@code Set} dice a chi legge che di quella
     * famiglia non si guarda nient'altro.
     */
    private record Codifiche(Map<String, VoceCodifica> comuni,
                             Set<String> stati,
                             Set<String> tipiDocumento,
                             Set<String> tipiAlloggiato) {
    }
}
