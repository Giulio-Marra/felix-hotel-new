package com.felixhotel.backend.entity.enums;

/**
 * Chi ha scritto un blocco di disponibilita'.
 *
 * <p><b>Dice chi, non perche'.</b> Il perche' sta nelle note del blocco, che sono testo
 * libero; questo elenco esiste per una decisione sola e molto concreta: <b>la sincronia
 * con un canale esterno rifa' i propri blocchi da capo ad ogni giro, e non deve toccare
 * quelli scritti a mano</b>. Senza questa distinzione la prima sincronizzazione
 * porterebbe via la manutenzione che qualcuno ha inserito alla reception.
 *
 * <p><b>Due valori e non quattro</b>, ed e' la regola 24 applicata a un elenco: sarebbe
 * venuto spontaneo distinguere manutenzione, chiusura stagionale e camera riservata alla
 * direzione, ma quelle sono <i>ragioni</i> — cambiano da albergo ad albergo, si scrivono
 * nelle note e nessun codice le guarda. Un valore in piu' qui vuol dire un ramo in piu'
 * da provare, in cambio di niente.
 */
public enum OrigineBlocco {

    /**
     * Lo ha inserito una persona dal backoffice. <b>Nessun automatismo lo cancella</b>:
     * un blocco manuale lo toglie chi lo ha messo.
     */
    MANUALE,

    /**
     * Lo ha scritto l'import di un calendario esterno — Booking, Airbnb — dove quella
     * camera risulta gia' venduta. <b>Vive quanto la sincronizzazione</b>: se
     * all'aggiornamento successivo quell'occupazione non c'e' piu', il blocco sparisce
     * con lei.
     *
     * <p>Nessun codice lo scrive ancora: arriva col branch dell'iCal. Il valore esiste
     * gia' perche' e' quello che da' un senso a questa colonna — vedi il commento del
     * V15__blocchi_disponibilita.sql.
     */
    CANALE_ESTERNO
}
