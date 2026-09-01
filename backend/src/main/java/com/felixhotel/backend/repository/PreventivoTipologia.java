package com.felixhotel.backend.repository;

import java.math.BigDecimal;

/**
 * Quanto costa un soggiorno di una tipologia in un periodo preciso, e quante
 * notti bisogna fermarsi come minimo per poterlo prenotare.
 *
 * <p>E' una proiezione e non un'entity, per la stessa ragione di
 * {@link OccupazioneTipologia}: nasce da un calcolo su un intervallo di date, e
 * non esiste nessuna riga a database che le corrisponda — cambiare il prezzo di
 * un periodo cambierebbe questa risposta senza toccare niente che si possa
 * salvare.
 *
 * <p><b>Non e' un impegno.</b> Il totale che vale davvero e' quello fotografato
 * su {@code Prenotazione#importoTotale} alla creazione: questo e' il preventivo
 * di adesso, e se il listino cambia in mezzo i due numeri differiscono. E'
 * voluto — l'importo di una prenotazione e' un contratto, non una query.
 */
public interface PreventivoTipologia {

    /** La tipologia a cui si riferisce il preventivo. */
    Long getTipologiaCameraId();

    /**
     * Somma dei prezzi di tutte le notti del soggiorno, in euro.
     *
     * <p>Ogni notte vale il prezzo del giorno della settimana se il periodo che
     * la copre ne ha uno, altrimenti il prezzo base di quel periodo, altrimenti
     * il prezzo di listino della tipologia. I tre livelli sono nell'ordine in
     * cui il {@code coalesce} della query li prova.
     */
    BigDecimal getImportoTotale();

    /**
     * Notti minime per prenotare, decise dal periodo che copre la <b>notte di
     * arrivo</b>. Vale 1 — cioe' nessun vincolo — quando quella notte non e'
     * coperta da nessun periodo.
     */
    int getSoggiornoMinimo();
}
