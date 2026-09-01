package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.exception.BadRequestException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Quanto puo' durare al massimo un soggiorno, e il controllo che lo applica.
 *
 * <p><b>Perche' esiste, dal 2026-09-01.</b> Fino a quel giorno si poteva
 * prenotare per diecimila notti: l'unico limite era che il totale entrasse in
 * {@code NUMERIC(10,2)}, cioe' un vincolo di colonna e non una regola d'albergo.
 * Era un gap aperto dal 2026-08-06 e lasciato li' di proposito — "quanto puo'
 * durare un soggiorno" e' una decisione di prodotto, e inventarla a tavolino
 * sarebbe stato darla per decisa. L'ha presa Giulio quando e' diventata
 * bloccante: la query dei prezzi scorre le notti una per una, quindi senza un
 * tetto il suo costo lo avrebbe deciso chi chiama.
 *
 * <p><b>Novanta notti</b>: copre il soggiorno lungo vero — chi e' in trasferta,
 * chi lavora una stagione — e taglia l'assurdo. Non e' un numero sacro, e' un
 * numero <i>scelto</i>, che e' la differenza che conta rispetto a prima.
 *
 * <p><b>Una classe apposta per una regola sola</b>, e il motivo e' che i due
 * posti che la applicano non possono condividere altro. La ricerca di
 * disponibilita' e la creazione di una prenotazione hanno controlli sulle date
 * diversi — chi cerca puo' guardare il passato, chi prenota no — e il
 * 2026-08-27 si era gia' deciso di <b>non</b> unificarli, per non ritrovarsi un
 * metodo con un parametro booleano che nessuno sa piu' leggere. Questa regola
 * pero' e' la stessa per tutti e due, e lasciarla scritta due volte
 * significherebbe che il giorno in cui il tetto cambia i due endpoint
 * comincerebbero a dire cose diverse sulla stessa richiesta — che e' precisamente
 * il difetto che questo tetto e' stato deciso per chiudere.
 */
public final class DurataSoggiorno {

    /**
     * Notti massime di un soggiorno.
     *
     * <p>E' anche il tetto al numero di righe che {@code generate_series}
     * produce per tipologia dentro
     * {@code PeriodoTariffarioRepository.preventivi}: alzarlo di molto vuol dire
     * guardare anche quella query, non solo questa costante.
     */
    public static final int MASSIMO_NOTTI = 90;

    private DurataSoggiorno() {
        // Solo il controllo statico: non c'e' nessuno stato da tenere.
    }

    /**
     * Rifiuta un periodo piu' lungo del massimo.
     *
     * <p><b>400 e non 409</b>, come per la capienza della tipologia e per la
     * stessa ragione: non c'e' niente che sia andato storto nel frattempo e non
     * c'e' nessuno stato con cui la richiesta confligga — la richiesta e'
     * semplicemente fuori da cio' che si puo' chiedere, e lo sarebbe stata anche
     * ieri.
     *
     * <p>Non verifica che la partenza sia dopo l'arrivo: quello lo fa gia' chi
     * chiama, ed e' il controllo che deve venire prima — un intervallo alla
     * rovescia darebbe qui un numero negativo, cioe' passerebbe.
     */
    public static void verifica(LocalDate dataCheckIn, LocalDate dataCheckOut) {
        if (ChronoUnit.DAYS.between(dataCheckIn, dataCheckOut) > MASSIMO_NOTTI) {
            throw new BadRequestException(
                    "Un soggiorno non puo' superare le " + MASSIMO_NOTTI + " notti");
        }
    }
}
