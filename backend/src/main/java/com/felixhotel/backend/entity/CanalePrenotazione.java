package com.felixhotel.backend.entity;

/**
 * Da dove e' arrivata una prenotazione.
 *
 * <p>Non e' un dato che il cliente sceglie: chi prenota dal sito produce
 * {@link #ONLINE} per costruzione, e gli altri tre valori li puo' indicare solo
 * il personale che registra la prenotazione di qualcun altro. Serve al
 * backoffice per sapere di cosa e' fatto il proprio riempimento — quanto arriva
 * dal sito e quanto dal telefono e' la differenza fra due alberghi molto
 * diversi.
 *
 * <p>Come {@link StatoPrenotazione}, e' un enum e non una tabella: l'elenco e'
 * chiuso, lo impone un CHECK nel DDL (V1__init_schema.sql) e non e' qualcosa che
 * un amministratore debba poter estendere da un'interfaccia.
 */
public enum CanalePrenotazione {

    /** Prenotata dal cliente sul sito. E' l'unico valore che una prenotazione fatta da un USER puo' avere. */
    ONLINE,

    /** Presa al telefono da un membro del personale. */
    TELEFONO,

    /** Cliente presentatosi al banco senza aver prenotato. */
    WALK_IN,

    /** Arrivata tramite un intermediario. */
    AGENZIA
}
