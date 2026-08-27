package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.DisponibilitaTipologia;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DisponibilitaMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.ConteggioCamere;
import com.felixhotel.backend.repository.OccupazioneTipologia;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.DisponibilitaService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementazione della ricerca di disponibilita'.
 *
 * <p><b>Tre query e non una per riga.</b> Il lavoro e' diviso in tre passi
 * perche' le tre domande hanno tre nature diverse: quali tipologie interessano
 * (una riga di tabella, impaginabile in database), quante camere hanno
 * (un conteggio raggruppato), quante ne sono occupate nel periodo (un calcolo su
 * un intervallo di date). Nessuno dei tre dipende dalla <i>riga</i>: dipendono
 * dalla pagina. E' la differenza fra tre query e una N+1.
 *
 * <p><b>L'ordine dei passi non e' invertibile.</b> Le tipologie si impaginano
 * per prime, e solo dopo si calcola la disponibilita' di quelle che sono
 * finite nella pagina. Il contrario — calcolare tutto e poi impaginare —
 * vorrebbe dire impaginare in memoria, che e' cio' che il progetto ha gia'
 * rifiutato quando ha tolto l'{@code @EntityGraph} dall'elenco del catalogo.
 */
@Service
@RequiredArgsConstructor
public class DisponibilitaServiceImpl implements DisponibilitaService {

    private final TipologiaCameraRepository tipologiaCameraRepository;
    private final CameraRepository cameraRepository;
    private final PrenotazioneRepository prenotazioneRepository;
    private final DisponibilitaMapper disponibilitaMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated cerca(LocalDate dataCheckIn, LocalDate dataCheckOut,
                                          Integer numeroOspiti, BigDecimal prezzoMinimo,
                                          BigDecimal prezzoMassimo, int page, int size) {
        verificaPeriodo(dataCheckIn, dataCheckOut);

        // Ordine alfabetico come il catalogo, e per lo stesso motivo: il nome e'
        // l'unica cosa con cui chi guarda si orienta. E' anche unico — c'e' un indice
        // su lower(nome) dal V2 — quindi qui basta un criterio solo, al contrario
        // dell'elenco delle prenotazioni, dove la data di arrivo non lo e'.
        Page<TipologiaCamera> pagina = tipologiaCameraRepository.cercaPerCapienzaEPrezzo(
                numeroOspiti, prezzoMinimo, prezzoMassimo,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "nome")));

        long notti = ChronoUnit.DAYS.between(dataCheckIn, dataCheckOut);

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Disponibilita' calcolata",
                disponibilita(pagina.getContent(), dataCheckIn, dataCheckOut, notti), pagina);
    }

    /**
     * Le righe di risultato per le tipologie di una pagina.
     *
     * <p>La pagina vuota <b>non arriva alle query</b>, e non e' un'ottimizzazione:
     * la query dell'occupazione filtra con un {@code in (:ids)}, e un
     * {@code in ()} non e' SQL valido. Il caso e' normale — l'ultima pagina di un
     * elenco, o filtri che non trovano niente — quindi va gestito, non evitato.
     */
    private List<DisponibilitaTipologia> disponibilita(List<TipologiaCamera> tipologie,
                                                       LocalDate dataCheckIn, LocalDate dataCheckOut,
                                                       long notti) {
        if (tipologie.isEmpty()) {
            return List.of();
        }

        List<Long> ids = tipologie.stream().map(TipologiaCamera::getId).toList();

        Map<Long, Long> camerePerTipologia = cameraRepository.contaPerTipologia(ids).stream()
                .collect(Collectors.toMap(ConteggioCamere::getTipologiaCameraId, ConteggioCamere::getTotale));

        Map<Long, Long> occupatePerTipologia = prenotazioneRepository.occupazioneMassima(
                        ids, dataCheckIn, dataCheckOut, StatoPrenotazione.nomiCheOccupano(), null).stream()
                .collect(Collectors.toMap(OccupazioneTipologia::getTipologiaCameraId,
                        OccupazioneTipologia::getOccupate));

        return tipologie.stream()
                .map(tipologia -> disponibilitaMapper.toDisponibilita(
                        tipologia, libere(tipologia, camerePerTipologia, occupatePerTipologia), notti))
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
    private long libere(TipologiaCamera tipologia, Map<Long, Long> camere, Map<Long, Long> occupate) {
        long esistenti = camere.getOrDefault(tipologia.getId(), 0L);
        long impegnate = occupate.getOrDefault(tipologia.getId(), 0L);

        return Math.max(0, esistenti - impegnate);
    }

    /**
     * L'unica regola sulle date che vale anche qui.
     *
     * <p><b>Non riusa {@code PrenotazioneServiceImpl.verificaDate} di proposito</b>:
     * quella ne fa due, e la seconda — non si comincia nel passato — qui sarebbe
     * sbagliata. Chi cerca sta guardando, non prenotando, e rifiutare una ricerca
     * su date passate vorrebbe dire impedire a chi lavora al banco di controllare
     * com'era andata la settimana scorsa. E' la stessa scelta gia' fatta il
     * 2026-08-06 fra {@code LoginAttemptService} e {@code ContatoreTentativi}:
     * due casi che condividono la forma ma non le regole restano due, e a
     * unificarli si ottiene un metodo con un parametro booleano che nessuno sa
     * piu' leggere.
     */
    private void verificaPeriodo(LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (!dataCheckOut.isAfter(dataCheckIn)) {
            throw new BadRequestException("La data di partenza deve essere successiva a quella di arrivo");
        }
    }
}
