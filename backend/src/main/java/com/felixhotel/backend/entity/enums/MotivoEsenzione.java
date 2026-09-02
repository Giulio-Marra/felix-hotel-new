package com.felixhotel.backend.entity.enums;

/**
 * Il motivo per cui un ospite non paga la tassa di soggiorno, fra quelli che
 * <b>qualcuno deve dichiarare</b>.
 *
 * <p><b>Qui non stanno tutte le esenzioni, e la divisione e' il punto.</b> Le
 * esenzioni dei regolamenti comunali sono di due specie:
 * <ul>
 *   <li>quelle che il sistema <b>calcola</b> — l'eta' sotto la soglia e le notti
 *       oltre il tetto — che discendono da dati gia' presenti
 *       ({@link Ospite#getDataNascita()} dal V10, le date della prenotazione dal
 *       V1). Chiederle a chi sta al banco vorrebbe dire fargli ripetere cio' che
 *       il sistema sa gia', e permettergli di sbagliarlo;</li>
 *   <li>quelle che qualcuno <b>constata</b>, e sono queste. Nessuna e'
 *       derivabile da niente: sono fatti del mondo che si verificano guardando
 *       un tesserino.</li>
 * </ul>
 * Per questo l'enum non ha un valore {@code MINORE} ne' {@code SOGGIORNO_LUNGO}:
 * sarebbero due modi di scrivere a mano una cosa che il calcolo sa fare, e il
 * giorno in cui i due non fossero d'accordo non ci sarebbe modo di sapere quale
 * dei due ha ragione.
 *
 * <p><b>L'elenco lo decide un regolamento comunale, non noi</b>, ed e' il motivo
 * per cui nel V11 non c'e' nessun {@code CHECK} con i valori — stessa scelta gia'
 * fatta per {@link TipoDocumento}, e per la stessa ragione: aggiungere un motivo
 * dev'essere una riga qui e non una migration. Sono i valori che ricorrono in
 * quasi tutti i regolamenti italiani; un comune che ne avesse uno suo obbligherebbe
 * comunque a toccare il codice, ed e' un caso che non si e' voluto anticipare.
 *
 * <p><b>Perche' non e' una tabella configurabile dall'ADMIN</b>, che per la regola
 * 24 sarebbe la domanda giusta da farsi: perche' il motivo da solo non basta —
 * ogni motivo nuovo va anche <i>applicato</i>, e applicarlo qui vuol dire una riga
 * di calcolo che non paga. Una tabella di motivi che il calcolo non conosce
 * sarebbe una configurazione senza codice dietro, cioe' esattamente la promessa
 * vuota che la regola 17 vieta.
 */
public enum MotivoEsenzione {

    /**
     * Residente nel comune. E' l'esenzione piu' comune in assoluto: la tassa
     * esiste per far contribuire chi usa la citta' senza abitarci.
     */
    RESIDENTE,

    /** Persona con disabilita', dove il regolamento la esenta. */
    DISABILE,

    /**
     * Chi accompagna una persona con disabilita'. E' una riga separata da
     * {@link #DISABILE} perche' sono due persone diverse, e una delle due potrebbe
     * essere esente e l'altra no a seconda del comune.
     */
    ACCOMPAGNATORE_DISABILE,

    /** Forze dell'ordine e vigili del fuoco in servizio. */
    FORZE_ORDINE,

    /**
     * Autisti di pullman e guide turistiche al seguito di un gruppo. Sono esenti
     * quasi ovunque perche' non sono turisti: stanno lavorando.
     */
    AUTISTA_O_GUIDA,

    /**
     * Chi soggiorna per cure mediche, e chi lo accompagna. I due casi stanno
     * insieme perche' i regolamenti li trattano insieme: e' il ricovero a
     * esentare, non il ruolo.
     */
    RICOVERO
}
