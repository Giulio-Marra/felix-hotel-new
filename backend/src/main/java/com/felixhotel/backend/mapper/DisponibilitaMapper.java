package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.DisponibilitaTipologia;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.repository.PreventivoTipologia;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Conversione Entity -> DTO per una riga di risultato della ricerca. Scritta a
 * mano per scelta di progetto (niente MapStruct); il DTO di destinazione e'
 * generato dallo spec.
 *
 * <p><b>Prende tre cose e non una</b>, al contrario di tutti gli altri mapper
 * del progetto: la tipologia, quante camere ne restano e il preventivo del
 * soggiorno. Non e' un'eccezione alla regola dei mapper senza logica — e' che
 * qui il DTO non e' la fotografia di una riga, ma di una riga <b>vista
 * attraverso un periodo</b>, e le altre due sono il periodo.
 */
@Component
@RequiredArgsConstructor
public class DisponibilitaMapper {

    private final TipologiaCameraMapper tipologiaCameraMapper;

    /**
     * <b>Va chiamato dentro la transazione</b> che ha caricato la tipologia: le
     * dotazioni sono LAZY e il progetto ha {@code open-in-view=false}. Le carica
     * il {@code @BatchSize} dichiarato sulla collezione, che e' anche il motivo
     * per cui l'elenco non le fetcha con un {@code @EntityGraph}.
     *
     * <p><b>Il prezzo non si calcola qui</b>, ed e' cambiato il 2026-09-01: fino
     * a quel giorno questo metodo moltiplicava il listino per le notti. Adesso
     * l'importo arriva gia' fatto dalla query delle tariffe, che e' l'unico posto
     * del progetto in cui il prezzo si calcola — e ci passa anche la creazione
     * della prenotazione. La ragione e' che due formule dei prezzi che divergono
     * vogliono dire una pagina di ricerca che dice un numero e una fattura che ne
     * dice un altro.
     *
     * <p><b>L'importo e' il preventivo di oggi, non un impegno.</b> Il totale che
     * vale e' quello fotografato alla creazione della prenotazione: se il listino
     * cambia in mezzo, i due numeri possono differire, ed e' voluto — vedi
     * {@code Prenotazione#importoTotale}.
     *
     * @param camereDisponibili quante ne restano nella notte peggiore del
     *                          periodo. Zero e' legittimo e vuol dire esaurito
     * @param preventivo        quanto costa il soggiorno cercato e quante notti
     *                          questa tipologia pretende come minimo con
     *                          quell'arrivo
     */
    public DisponibilitaTipologia toDisponibilita(TipologiaCamera tipologia, long camereDisponibili,
                                                  PreventivoTipologia preventivo) {
        return new DisponibilitaTipologia()
                .tipologia(tipologiaCameraMapper.toResponse(tipologia))
                .camereDisponibili((int) camereDisponibili)
                .importoTotale(preventivo.getImportoTotale())
                .soggiornoMinimo(preventivo.getSoggiornoMinimo());
    }
}
