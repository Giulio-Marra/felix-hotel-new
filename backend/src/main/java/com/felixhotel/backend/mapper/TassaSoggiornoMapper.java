package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.AliquotaTassaSoggiornoResponse;
import com.felixhotel.backend.dto.TassaSoggiornoOspite;
import com.felixhotel.backend.dto.TassaSoggiornoResponse;
import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import com.felixhotel.backend.entity.MotivoEsenzione;
import com.felixhotel.backend.entity.Ospite;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Conversione verso i DTO della tassa di soggiorno. Scritta a mano per scelta di
 * progetto (niente MapStruct); i DTO di destinazione sono generati dallo spec.
 *
 * <p><b>Copre due cose che sembrano una sola</b>: l'aliquota, che e' un'entita' e
 * si converte come tutte le altre, e il <i>conto</i>, che entita' non e' — non
 * esiste nessuna riga "tassa dovuta", perche' il numero si ricalcola ad ogni
 * richiesta. Il conto lo compone il Service riga per riga chiamando
 * {@link #toOspite}, e questa classe non sa niente di come sia stato ottenuto: e'
 * la stessa divisione gia' in uso fra {@code DisponibilitaServiceImpl} e il suo
 * mapper.
 *
 * <p><b>Il dettaglio non porta il numero di documento, ed e' l'unica decisione di
 * questa classe.</b> {@link #toOspite} prende un {@link Ospite} intero e ne fa
 * uscire nome, cognome e il conto: e' cio' che permette al cliente di vedere la
 * tassa della propria prenotazione, mentre il registro degli ospiti resta chiuso
 * al personale. Se un domani qualcuno aggiungesse qui un campo, e' questo javadoc
 * a dover essere riletto prima.
 */
@Component
public class TassaSoggiornoMapper {

    /**
     * I decimali con cui esce ogni importo di questa risposta.
     *
     * <p><b>Esiste per un difetto visto a runtime</b>, non in teoria: gli importi che
     * vengono dal database hanno gia' due decimali (la colonna e' {@code NUMERIC(10,2)}),
     * ma quelli calcolati in Java partono da {@link BigDecimal#ZERO}, che di decimali non
     * ne ha nessuno. La stessa risposta usciva con {@code "importo": 6.00} accanto a
     * {@code "importo": 0} — due formati per la stessa colonna, che su un conto e' il
     * genere di incoerenza che fa dubitare del resto.
     *
     * <p>La normalizzazione sta <b>qui e non nel Service</b> di proposito: e' una
     * decisione sul formato della risposta, quindi appartiene a chi la compone, e messa
     * in un punto solo nessun chiamante futuro puo' dimenticarsene.
     */
    private static final int DECIMALI = 2;

    public AliquotaTassaSoggiornoResponse toResponse(AliquotaTassaSoggiorno aliquota) {
        return new AliquotaTassaSoggiornoResponse()
                .id(aliquota.getId())
                .dataInizio(aliquota.getDataInizio())
                .dataFine(aliquota.getDataFine())
                .importoPerPersonaNotte(aliquota.getImportoPerPersonaNotte())
                .nottiMassimeTassate(aliquota.getNottiMassimeTassate())
                .etaEsenzione(aliquota.getEtaEsenzione());
    }

    /**
     * Versione per l'elenco. <b>L'ordine della lista in ingresso viene
     * conservato</b>: lo ha gia' deciso la query, per data di inizio crescente.
     */
    public List<AliquotaTassaSoggiornoResponse> toResponseList(List<AliquotaTassaSoggiorno> aliquote) {
        return aliquote.stream().map(this::toResponse).toList();
    }

    /**
     * La riga di conto di una persona.
     *
     * <p>I due motivi di esenzione escono <b>separati e non fusi in un campo
     * solo</b>: {@code esenzioneEta} la calcola il sistema, {@code motivoEsenzione}
     * l'ha dichiarata qualcuno al banco. Un enum unico con dentro tutti e due
     * avrebbe dovuto scegliere quale mostrare quando valgono entrambi — un
     * residente di dieci anni — e qualunque scelta avrebbe nascosto meta' della
     * verita' a chi legge.
     *
     * <p>Il terzo caso, il tetto di notti, <b>non ha un campo</b> e non e' una
     * dimenticanza: e' l'unica esenzione che non esenta la persona ma alcune delle
     * sue notti, quindi si legge confrontando {@code nottiTassate} con le notti del
     * soggiorno. Dargli un booleano avrebbe messo sullo stesso piano cose diverse.
     */
    public TassaSoggiornoOspite toOspite(Ospite ospite,
                                         int nottiTassate,
                                         BigDecimal importo,
                                         boolean esenzioneEta) {
        return new TassaSoggiornoOspite()
                .ospiteId(ospite.getId())
                .nome(ospite.getNome())
                .cognome(ospite.getCognome())
                .nottiTassate(nottiTassate)
                .importo(conDueDecimali(importo))
                .esenzioneEta(esenzioneEta)
                .motivoEsenzione(motivoEsenzione(ospite));
    }

    /**
     * Il conto intero.
     *
     * <p>Il totale <b>si somma dalle righe</b> invece di essere calcolato a parte:
     * cosi' non puo' esistere una risposta in cui il totale e il dettaglio dicono
     * due cose diverse, che e' il difetto peggiore che un conto possa avere.
     */
    public TassaSoggiornoResponse toResponse(List<TassaSoggiornoOspite> ospiti,
                                             int nottiSoggiorno,
                                             int nottiNonCoperte) {
        BigDecimal totale = ospiti.stream()
                .map(TassaSoggiornoOspite::getImporto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TassaSoggiornoResponse()
                .totale(conDueDecimali(totale))
                .nottiSoggiorno(nottiSoggiorno)
                .nottiNonCoperte(nottiNonCoperte)
                .ospiti(ospiti);
    }

    /**
     * Lo stesso importo con due decimali, sempre.
     *
     * <p>{@code HALF_UP} non arrotonda mai niente nei fatti — i valori arrivano da una
     * colonna che di decimali ne ha gia' due, e le somme di numeri a due decimali
     * restano a due decimali — ma va indicato comunque, e fra i modi possibili e'
     * quello che nessuno legge come una scelta. {@code UNNECESSARY} sarebbe piu'
     * onesto e trasformerebbe l'imprevisto in un 500: su un formato di risposta non
     * vale la pena.
     */
    private BigDecimal conDueDecimali(BigDecimal importo) {
        return importo.setScale(DECIMALI, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Il motivo dichiarato nel tipo del contratto, oppure {@code null}.
     *
     * <p>La conversione fra i due {@link MotivoEsenzione} — quello dell'entita' e
     * quello generato dallo spec — e' la stessa logica che {@code OspiteMapper} fa
     * per il tipo di documento, e per la stessa ragione: sono due tipi diversi che
     * si somigliano, e a tenere allineati i loro elenchi c'e' solo questa riga.
     */
    private com.felixhotel.backend.dto.MotivoEsenzione motivoEsenzione(Ospite ospite) {
        return ospite.getMotivoEsenzione() == null
                ? null
                : com.felixhotel.backend.dto.MotivoEsenzione.fromValue(ospite.getMotivoEsenzione().name());
    }
}
