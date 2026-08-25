package com.felixhotel.backend.entity;

/**
 * Stato operativo di una camera fisica, cioe' com'e' messa <b>adesso</b>.
 *
 * <p><b>Non dice se la camera sia prenotabile</b>, ed e' la distinzione da
 * tenere ferma: la disponibilita' e' un calcolo su date — camere della tipologia
 * meno prenotazioni attive che si sovrappongono al periodo richiesto — mentre
 * questo e' un campo che descrive il presente. {@code OCCUPATA} vuol dire "c'e'
 * qualcuno dentro stanotte", non "e' impegnata a settembre". Confondere le due
 * cose porterebbe a rifiutare una prenotazione per novembre perche' la stanza e'
 * occupata oggi.
 *
 * <p>Vive come enum Java e non come tabella, a differenza di {@link Ruolo}:
 * l'elenco e' chiuso, lo impone un CHECK nel DDL (V1__init_schema.sql) e non e'
 * qualcosa che un amministratore debba poter estendere da un'interfaccia. Se un
 * giorno lo diventasse, sarebbe una tabella — ma allora sarebbe anche un'altra
 * cosa.
 *
 * <p>I nomi coincidono con i valori accettati dal CHECK e con l'enum
 * {@code StatoCamera} generato dallo spec OpenAPI: sono tre elenchi che devono
 * restare allineati, e un IT li esercita tutti e quattro proprio per questo.
 */
public enum StatoCamera {

    /** Pronta a essere assegnata. E' lo stato con cui una camera nasce. */
    LIBERA,

    /** C'e' un ospite dentro in questo momento. */
    OCCUPATA,

    /** Fuori servizio per un guasto o un lavoro. Una stanza fuori uso si segna cosi', non si cancella. */
    MANUTENZIONE,

    /** Liberata ma non ancora rifatta: non assegnabile finche' non torna LIBERA. */
    PULIZIA
}
