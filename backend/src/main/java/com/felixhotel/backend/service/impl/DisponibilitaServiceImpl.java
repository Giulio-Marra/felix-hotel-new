package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.DisponibilitaTipologia;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DisponibilitaMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.ConteggioCamere;
import com.felixhotel.backend.repository.OccupazioneTipologia;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.PreventivoTipologia;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.DisponibilitaService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementazione della ricerca di disponibilita'.
 *
 * <p><b>Quattro query e non una per riga.</b> Il lavoro e' diviso perche' le
 * domande hanno nature diverse: quanto costa il soggiorno e quali tipologie
 * passano i filtri (un calcolo su un intervallo di date, che pero' e' anche cio'
 * che decide le righe, quindi impagina), che tipologie siano davvero (le loro
 * righe di catalogo, con le dotazioni), quante camere hanno (un conteggio
 * raggruppato), quante ne sono occupate nel periodo (un altro calcolo su un
 * intervallo). Nessuna delle quattro dipende dalla <i>riga</i>: dipendono dalla
 * pagina. E' la differenza fra quattro query e una N+1.
 *
 * <p><b>A impaginare e' il preventivo, e dal 2026-09-01 non poteva piu' essere
 * altrimenti.</b> Prima le tipologie si impaginavano da sole, perche' il filtro
 * di prezzo guardava una colonna della loro riga. Con le tariffe per periodo il
 * prezzo dipende dalle date cercate — la stessa camera costa cose diverse a
 * Ferragosto e in novembre — quindi un filtro sul listino avrebbe offerto a chi
 * cerca sotto i cento euro una stanza che in quelle date ne costa duecento. E un
 * filtro che puo' escludere righe deve agire prima della paginazione: toglierle
 * dopo darebbe pagine di dimensione variabile, che e' impaginare in memoria con
 * un altro nome. Da qui l'inversione: prima il prezzo, che decide chi entra e in
 * che pagina, poi le entita' di quella pagina.
 *
 * <p><b>L'ordine dei passi resta non invertibile</b>, per la stessa ragione di
 * sempre: si impagina in database e si calcola dopo, mai il contrario.
 */
@Service
@RequiredArgsConstructor
public class DisponibilitaServiceImpl implements DisponibilitaService {

    private final TipologiaCameraRepository tipologiaCameraRepository;
    private final CameraRepository cameraRepository;
    private final PrenotazioneRepository prenotazioneRepository;

    /**
     * Quanto costa il soggiorno di ogni tipologia, e quante notti ognuna
     * pretende come minimo. E' anche cio' che decide quali tipologie entrano
     * nella pagina, perche' i filtri di prezzo si applicano al preventivo e non
     * al listino.
     */
    private final PeriodoTariffarioRepository periodoTariffarioRepository;

    private final DisponibilitaMapper disponibilitaMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated cerca(LocalDate dataCheckIn, LocalDate dataCheckOut,
                                          Integer numeroOspiti, BigDecimal prezzoMinimo,
                                          BigDecimal prezzoMassimo, int page, int size) {
        verificaPeriodo(dataCheckIn, dataCheckOut);

        // L'ordinamento sta scritto dentro la query — e' un group by, e chi chiama non
        // ha nessun criterio da scegliere. E' alfabetico come il catalogo, e come li' un
        // criterio solo basta perche' il nome e' unico (indice del V2).
        Page<PreventivoTipologia> preventivi = periodoTariffarioRepository.preventivi(
                null, numeroOspiti, prezzoMinimo, prezzoMassimo, dataCheckIn, dataCheckOut,
                PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Disponibilita' calcolata",
                disponibilita(preventivi.getContent(), dataCheckIn, dataCheckOut), preventivi);
    }

    /**
     * Le righe di risultato per le tipologie di una pagina.
     *
     * <p>La pagina vuota <b>non arriva alle query</b>, e non e' un'ottimizzazione:
     * due di loro filtrano con un {@code in (:ids)}, e un {@code in ()} non e' SQL
     * valido. Il caso e' normale — l'ultima pagina di un elenco, o filtri che non
     * trovano niente — quindi va gestito, non evitato.
     */
    private List<DisponibilitaTipologia> disponibilita(List<PreventivoTipologia> preventivi,
                                                       LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (preventivi.isEmpty()) {
            return List.of();
        }

        List<Long> ids = preventivi.stream().map(PreventivoTipologia::getTipologiaCameraId).toList();

        // Le entita' della pagina, indicizzate per id: la query dei preventivi ha gia'
        // deciso quali sono e in che ordine, quindi qui si tratta solo di ritrovarle.
        // findAllById non garantisce l'ordine, e affidarsi al suo sarebbe affidarsi a
        // qualcosa che nessuno ha promesso.
        Map<Long, TipologiaCamera> tipologie = tipologiaCameraRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TipologiaCamera::getId, Function.identity()));

        Map<Long, Long> camerePerTipologia = cameraRepository.contaPerTipologia(ids).stream()
                .collect(Collectors.toMap(ConteggioCamere::getTipologiaCameraId, ConteggioCamere::getTotale));

        Map<Long, Long> occupatePerTipologia = prenotazioneRepository.occupazioneMassima(
                        ids, dataCheckIn, dataCheckOut, StatoPrenotazione.nomiCheOccupano(), null).stream()
                .collect(Collectors.toMap(OccupazioneTipologia::getTipologiaCameraId,
                        OccupazioneTipologia::getOccupate));

        // Il filtro sul null non e' difensivo per abitudine: fra la query dei preventivi
        // e questa lettura c'e' una finestra, e in READ COMMITTED una DELETE altrui in
        // mezzo lascerebbe un id senza la sua riga. Sarebbe un NullPointerException nel
        // mapper, cioe' un 500 su una GET pubblica per colpa di qualcosa che chi cerca
        // non ha fatto. Saltare la riga da' una pagina piu' corta di 'totalElements' —
        // che pero' e' esattamente cio' che e' successo: quella tipologia non c'e' piu'.
        return preventivi.stream()
                .filter(preventivo -> tipologie.containsKey(preventivo.getTipologiaCameraId()))
                .map(preventivo -> disponibilitaMapper.toDisponibilita(
                        tipologie.get(preventivo.getTipologiaCameraId()),
                        libere(preventivo.getTipologiaCameraId(), camerePerTipologia, occupatePerTipologia),
                        preventivo))
                .toList();
    }

    /**
     * Camere libere per tutto il periodo: quante ne esistono meno quante ne sono
     * impegnate nella notte peggiore.
     *
     * <p><b>Le due mappe possono non avere la chiave</b>, e i due casi vogliono
     * dire cose diverse ma si trattano uguale. Manca dai conteggi la tipologia
     * senza nessuna camera (il {@code group by} non raggruppa righe che non
     * esistono); manca dalle occupazioni non succede mai — la query ci mette una
     * riga a zero per ogni tipologia chiesta — ma il default resta, perche' un
     * codice che si regge su quella garanzia si romperebbe in silenzio se la
     * query cambiasse.
     *
     * <p>Il {@code Math.max} non e' difensivo: senza, la sottrazione resterebbe
     * comunque non negativa, ma solo finche' i due numeri arrivano dalla stessa
     * transazione. Costa niente e toglie di mezzo la domanda.
     */
    private long libere(Long tipologiaCameraId, Map<Long, Long> camere, Map<Long, Long> occupate) {
        long esistenti = camere.getOrDefault(tipologiaCameraId, 0L);
        long impegnate = occupate.getOrDefault(tipologiaCameraId, 0L);

        return Math.max(0, esistenti - impegnate);
    }

    /**
     * Le regole sulle date che valgono anche qui.
     *
     * <p><b>Non riusa {@code PrenotazioneServiceImpl.verificaDate} di proposito</b>:
     * quella ne fa tre, e la seconda — non si comincia nel passato — qui sarebbe
     * sbagliata. Chi cerca sta guardando, non prenotando, e rifiutare una ricerca
     * su date passate vorrebbe dire impedire a chi lavora al banco di controllare
     * com'era andata la settimana scorsa. E' la stessa scelta gia' fatta il
     * 2026-08-06 fra {@code LoginAttemptService} e {@code ContatoreTentativi}:
     * due casi che condividono la forma ma non le regole restano due, e a
     * unificarli si ottiene un metodo con un parametro booleano che nessuno sa
     * piu' leggere.
     *
     * <p><b>Il tetto sulla durata invece si condivide</b>, ed e' l'unica delle
     * tre che lo faccia: sta in {@link DurataSoggiorno}, scritto una volta per
     * tutti e due. Prima del 2026-09-01 questo controllo qui non c'era affatto, e
     * la conseguenza era che la ricerca mostrava il preventivo di un soggiorno
     * che la creazione avrebbe poi rifiutato — due endpoint che dicevano cose
     * diverse sulla stessa richiesta.
     */
    private void verificaPeriodo(LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (!dataCheckOut.isAfter(dataCheckIn)) {
            throw new BadRequestException("La data di partenza deve essere successiva a quella di arrivo");
        }

        DurataSoggiorno.verifica(dataCheckIn, dataCheckOut);
    }
}
