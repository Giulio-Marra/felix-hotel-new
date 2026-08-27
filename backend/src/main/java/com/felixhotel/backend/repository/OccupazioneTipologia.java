package com.felixhotel.backend.repository;

/**
 * Quante camere di una tipologia risultano impegnate <b>nella notte peggiore</b>
 * di un periodo.
 *
 * <p><b>La notte peggiore, non il totale delle prenotazioni che toccano il
 * periodo</b>, ed e' tutta la differenza: chi prenota vuole una stanza per
 * <i>tutte</i> le notti che ha chiesto, quindi cio' che gli toglie il posto e' il
 * momento di massimo affollamento, non la somma di quelli che passano di li'.
 * Tre soggiorni brevi messi in fila occupano una camera sola, e contarli come
 * tre significherebbe rifiutare un soggiorno lungo che invece si poteva servire.
 *
 * <p>E' una proiezione e non un'entity: nasce da un calcolo su un intervallo di
 * date, non c'e' nessuna riga a database che le corrisponda, e non ce ne
 * sarebbe una che resti giusta dopo il primo annullamento.
 */
public interface OccupazioneTipologia {

    /** La tipologia a cui si riferisce il conteggio. */
    Long getTipologiaCameraId();

    /**
     * Camere impegnate nella notte piu' affollata del periodo. Zero quando in
     * quel periodo non c'e' nessuna prenotazione attiva.
     */
    long getOccupate();
}
