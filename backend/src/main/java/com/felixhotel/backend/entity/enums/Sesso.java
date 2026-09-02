package com.felixhotel.backend.entity.enums;

/**
 * Il sesso di un {@link Ospite} come lo chiede la schedina alloggiati.
 *
 * <p><b>Due valori e non tre</b>, e non e' una posizione di questo progetto su cosa
 * sia il sesso di una persona: e' la casella di un modulo di legge che ne accetta
 * due e rifiuta ogni altra cosa. Inventare qui un terzo valore non renderebbe la
 * schedina piu' giusta, la renderebbe scartata al caricamento — e la persona
 * resterebbe non registrata, che e' il danno peggiore fra i due. Se il Ministero un
 * giorno ne accettera' altri, si aggiungono qui e nel {@code CHECK} del V13.
 *
 * <p><b>Si scrive la lettera e non il numero.</b> Il tracciato vuole {@code 1} per
 * il maschile e {@code 2} per il femminile; quella traduzione sta in
 * {@code CodiciAlloggiati}, insieme a tutte le altre. In tabella e nel contratto
 * resta {@code M}/{@code F}, perche' e' quel che si legge senza avere sotto mano il
 * tracciato — un {@code 2} in una colonna {@code sesso} non si interpreta da solo.
 */
public enum Sesso {

    /** Maschile. Sul tracciato diventa {@code 1}. */
    M,

    /** Femminile. Sul tracciato diventa {@code 2}. */
    F
}
