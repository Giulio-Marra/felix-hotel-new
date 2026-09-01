package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.dto.PeriodoTariffarioResponse;
import com.felixhotel.backend.dto.PrezzoGiorno;
import com.felixhotel.backend.entity.PeriodoTariffario;
import com.felixhotel.backend.entity.PrezzoGiornoSettimana;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;

/**
 * Conversione fra {@link PeriodoTariffario} e i DTO generati dallo spec.
 * Scritta a mano per scelta di progetto (niente MapStruct).
 *
 * <p><b>Fa anche il verso opposto</b>, al contrario della maggior parte dei
 * mapper del progetto: {@link #toPrezziGiorno} costruisce le entita' figlie a
 * partire dalla richiesta. Sta qui e non nel Service per la regola 11 — nei
 * Service non si assembla niente — e perche' e' esattamente la stessa
 * traduzione dell'altro verso, letta da destra a sinistra: tenerle nella stessa
 * classe e' cio' che rende visibile a colpo d'occhio che siano coerenti.
 */
@Component
public class PeriodoTariffarioMapper {

    /**
     * <b>Va chiamato dentro la transazione</b> che ha caricato il periodo: i
     * prezzi per giorno sono LAZY e il progetto ha {@code open-in-view=false}.
     * Nell'elenco paginato li carica il {@code @BatchSize} della collezione, sul
     * dettaglio l'{@code @EntityGraph} della query — e' la regola 15 applicata
     * alle due query invece che all'entita'.
     *
     * <p>La tipologia non compare nella risposta: sta gia' nell'URL con cui il
     * periodo e' stato chiesto, e ripeterla vorrebbe dire spedire ad ogni riga
     * dell'elenco un oggetto che chi chiama ha gia' in mano. Stessa scelta gia'
     * fatta per le foto e per gli ospiti.
     */
    public PeriodoTariffarioResponse toResponse(PeriodoTariffario periodo) {
        return new PeriodoTariffarioResponse()
                .id(periodo.getId())
                .nome(periodo.getNome())
                .dataInizio(periodo.getDataInizio())
                .dataFine(periodo.getDataFine())
                .prezzoNotte(periodo.getPrezzoNotte())
                .soggiornoMinimo(periodo.getSoggiornoMinimo())
                .prezziGiorno(toPrezziGiornoResponse(periodo.getPrezziGiorno()));
    }

    public List<PeriodoTariffarioResponse> toResponseList(List<PeriodoTariffario> periodi) {
        return periodi.stream().map(this::toResponse).toList();
    }

    /**
     * I prezzi per giorno di un periodo, <b>ordinati da lunedi' a domenica</b>.
     *
     * <p><b>L'ordine lo mette qui e non il database</b>, ed e' l'unico posto in
     * cui puo' stare. L'enum e' persistito come stringa, quindi un
     * {@code order by} in SQL ordinerebbe i nomi in alfabetico — FRIDAY, MONDAY,
     * SATURDAY, SUNDAY, THURSDAY... — che non e' una settimana. Solo qui c'e'
     * l'enum vero, con il suo ordinale, che di settimana ne conosce una.
     */
    private List<PrezzoGiorno> toPrezziGiornoResponse(List<PrezzoGiornoSettimana> prezzi) {
        return prezzi.stream()
                .sorted(Comparator.comparing(PrezzoGiornoSettimana::getGiorno))
                .map(prezzo -> new PrezzoGiorno()
                        .giorno(PrezzoGiorno.GiornoEnum.fromValue(prezzo.getGiorno().name()))
                        .prezzo(prezzo.getPrezzo()))
                .toList();
    }

    /**
     * Le entita' figlie a partire dalla richiesta.
     *
     * <p><b>Non valorizza il periodo di appartenenza</b>: quello lo fa
     * {@code PeriodoTariffario.sostituisciPrezziGiorno}, che e' anche l'unico
     * modo corretto di attaccarle — la lista dell'entita' va svuotata e
     * riempita, non riassegnata, altrimenti {@code orphanRemoval} perde le righe
     * da cancellare. Lasciare qui meta' del lavoro sarebbe stato peggio che
     * lasciarne fuori tutto: due posti che devono ricordarsi l'uno dell'altro.
     *
     * <p>L'array assente e quello vuoto sono la stessa cosa — nessun giorno
     * scavalcato — e il DTO generato li rende gia' identici, perche' inizializza
     * la lista a vuota.
     */
    public List<PrezzoGiornoSettimana> toPrezziGiorno(PeriodoTariffarioRequest request) {
        return request.getPrezziGiorno().stream()
                .map(dto -> {
                    PrezzoGiornoSettimana prezzo = new PrezzoGiornoSettimana();
                    prezzo.setGiorno(DayOfWeek.valueOf(dto.getGiorno().getValue()));
                    prezzo.setPrezzo(dto.getPrezzo());
                    return prezzo;
                })
                .toList();
    }
}
