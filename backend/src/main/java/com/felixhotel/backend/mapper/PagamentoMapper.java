package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.MetodoPagamento;
import com.felixhotel.backend.dto.PagamentoResponse;
import com.felixhotel.backend.dto.RiepilogoPagamentiResponse;
import com.felixhotel.backend.entity.Pagamento;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Da {@link Pagamento} ai DTO del registro, e ritorno per i due campi che entrano.
 *
 * <p><b>Il riepilogo si costruisce qui e non nel Service</b>, come ogni altro DTO del
 * progetto (regola 11): il Service decide <i>quanto</i> resta da incassare, questo mapper
 * decide <i>come</i> quel numero viene detto.
 */
@Component
@RequiredArgsConstructor
public class PagamentoMapper {

    private final StaffMapper staffMapper;

    /** Un singolo versamento. */
    public PagamentoResponse toResponse(Pagamento pagamento) {
        return new PagamentoResponse()
                .id(pagamento.getId())
                .importo(pagamento.getImporto())
                .metodo(toMetodoDto(pagamento.getMetodo()))
                .incassatoIl(toOffset(pagamento.getIncassatoIl()))
                .riferimento(pagamento.getRiferimento())
                .registratoDa(staffMapper.toSintesi(pagamento.getRegistratoDa()));
    }

    /**
     * Il riepilogo: i quattro numeri che servono a decidere, piu' l'elenco.
     *
     * <p><b>Nessuno di questi campi corrisponde a una colonna</b>, ed e' il punto: sono
     * tutti ricavati al momento della lettura, quindi non possono andare fuori sincrono
     * con i versamenti che li producono.
     *
     * <p>{@code saldata} e' un comodo per chi legge — dice la stessa cosa di un residuo a
     * zero — e si calcola con {@code compareTo} e non con {@code equals}: per
     * {@code BigDecimal} <i>0.00</i> e <i>0</i> sono numeri uguali ma oggetti diversi, e
     * quale dei due esca dalla sottrazione dipende dalla scala degli addendi.
     */
    public RiepilogoPagamentiResponse toRiepilogo(BigDecimal importoTotale,
                                                  BigDecimal caparraDovuta,
                                                  BigDecimal incassato,
                                                  BigDecimal residuo,
                                                  List<Pagamento> pagamenti) {
        return new RiepilogoPagamentiResponse()
                .importoTotale(importoTotale)
                .caparraDovuta(caparraDovuta)
                .incassato(incassato)
                .residuo(residuo)
                .saldata(residuo.compareTo(BigDecimal.ZERO) == 0)
                .pagamenti(pagamenti.stream().map(this::toResponse).toList());
    }

    /** Il metodo come lo dichiara lo spec, a partire da quello dell'entita'. */
    public MetodoPagamento toMetodoDto(com.felixhotel.backend.entity.enums.MetodoPagamento metodo) {
        return MetodoPagamento.fromValue(metodo.name());
    }

    /** Il verso opposto, per il metodo che arriva nella richiesta. */
    public com.felixhotel.backend.entity.enums.MetodoPagamento toMetodoEntity(MetodoPagamento metodo) {
        return com.felixhotel.backend.entity.enums.MetodoPagamento.valueOf(metodo.getValue());
    }

    /**
     * Da {@code LocalDateTime} a {@code OffsetDateTime}, che e' il tipo che il generatore
     * produce per un {@code format: date-time}.
     *
     * <p>Copia della stessa conversione di {@code PrenotazioneMapper} e per la stessa
     * ragione: le colonne sono {@code TIMESTAMP} senza fuso — l'ora locale della macchina
     * che le ha scritte — e dichiararle UTC le sposterebbe di qualche ora senza cambiarne
     * il valore.
     */
    private OffsetDateTime toOffset(LocalDateTime istante) {
        return istante.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /**
     * Il verso opposto, ed e' <b>il primo istante che entra</b> in questo progetto: fin
     * qui le date in ingresso erano tutte {@code LocalDate}.
     *
     * <p>{@code atZoneSameInstant} e non {@code toLocalDateTime} diretto: chi manda
     * {@code 2026-09-02T09:15:00+02:00} e chi manda lo stesso istante scritto
     * {@code 07:15:00Z} intendono lo stesso momento, e in colonna deve finirci la
     * <b>stessa</b> ora locale. Prendere l'ora cosi' com'e' scritta farebbe dipendere il
     * dato registrato dal fuso di chi lo scrive.
     */
    public LocalDateTime toLocale(OffsetDateTime istante) {
        return istante.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
