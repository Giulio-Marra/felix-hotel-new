package com.felixhotel.backend.security;

/**
 * A quale delle due popolazioni di account appartiene chi si e' autenticato,
 * cioe' <b>su quale tabella vale il suo id</b>.
 *
 * <p>Serve perche' i clienti stanno in {@code utente} e il personale in
 * {@code staff}: sono due tabelle separate con due sequenze indipendenti,
 * quindi l'id 3 esiste quasi certamente in tutte e due e da solo non
 * identifica nessuno. Senza questo enum, chi legge
 * {@link AppUserPrincipal#getUserId()} ha in mano un numero di cui non
 * conosce il significato, e l'unica chiave utilizzabile resta l'email —
 * univoca nell'insieme delle due popolazioni, ma che costa una lettura in
 * piu' ogni volta che si vuole sapere chi sta chiamando.
 *
 * <p><b>Non e' il ruolo, e i due non vanno confusi.</b> Il ruolo dice cosa
 * l'account puo' fare (ADMIN, STAFF, USER) ed e' una colonna che si puo'
 * cambiare; questo dice dove l'account <i>vive</i>, ed e' deciso da quale
 * repository lo ha caricato. Che le due cose combacino — personale con ruolo
 * ADMIN o STAFF, clienti con ruolo USER — e' una convenzione che nessun
 * vincolo del database applica, quindi restano due domande diverse.
 */
public enum TipoAccount {

    /** Cliente del frontoffice: l'id vale sulla tabella {@code utente}. */
    CLIENTE,

    /** Personale del backoffice: l'id vale sulla tabella {@code staff}. */
    PERSONALE
}
