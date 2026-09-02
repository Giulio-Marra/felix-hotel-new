package com.felixhotel.backend.entity.enums;

/**
 * Che documento un {@link Ospite} ha esibito al banco.
 *
 * <p>L'elenco ricalca quello che la Questura accetta sulla schedina alloggiati
 * ed e' volutamente corto: sono i quattro documenti che una reception vede
 * davvero. Aggiungerne uno e' una modifica al solo contratto e a questo enum.
 *
 * <p><b>Al contrario di {@link StatoPrenotazione} e {@link CanalePrenotazione},
 * il database non lo verifica.</b> Quei due hanno un {@code CHECK} nel DDL che
 * ripete l'elenco; {@code ospite.tipo_documento} e' un {@code VARCHAR(30)} nudo
 * fin dal V1. La conseguenza va saputa, perche' e' una differenza vera e non un
 * dettaglio: l'unico posto che fa rispettare questo elenco e' la validazione del
 * DTO generato dallo spec, quindi una riga scritta a mano nel database puo'
 * contenere qualunque stringa, e a leggerla poi sarebbe
 * {@code IllegalArgumentException} in fase di conversione — non un dato
 * strano, un errore.
 *
 * <p>Non e' stato aggiunto un CHECK con una migration, e la ragione e' che le
 * due situazioni non sono simmetriche: lo stato di una prenotazione lo scrive
 * <i>l'applicazione</i> e un valore fuori elenco sarebbe un difetto suo, mentre
 * l'elenco dei documenti validi e' una cosa che il mondo fuori puo' cambiare —
 * la Questura ne aggiunge uno, e con il CHECK servirebbe una migration per una
 * modifica che qui e' una riga. Il prezzo di questa scelta e' scritto sopra.
 */
public enum TipoDocumento {

    /** Carta d'identita', cartacea o elettronica. */
    CARTA_IDENTITA,

    /** Passaporto. E' il documento normale per chi arriva da fuori dall'Unione. */
    PASSAPORTO,

    /** Patente di guida. */
    PATENTE,

    /** Permesso di soggiorno. */
    PERMESSO_SOGGIORNO
}
