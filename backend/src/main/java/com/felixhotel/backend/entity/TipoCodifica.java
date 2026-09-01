package com.felixhotel.backend.entity;

/**
 * Quale delle quattro codifiche ministeriali.
 *
 * <p><b>Questo elenco lo scrive l'applicazione, non il Ministero</b>, ed e' la
 * ragione per cui — al contrario di {@link TipoDocumento} — nel V12 c'e' un
 * {@code CHECK} che lo ricalca. Sono i nomi delle quattro <i>famiglie</i>, non i
 * dati che stanno dentro: un valore fuori elenco sarebbe un difetto nostro e non
 * una novita' del mondo, quindi vietarlo in database e' giusto. I documenti
 * validi, invece, li cambia la Questura, e li' il CHECK trasformerebbe una riga di
 * enum in una migration.
 *
 * <p><b>Quattro famiglie in una tabella sola</b>: non sono cose diverse, sono la
 * stessa cosa — un codice con la sua descrizione — pubblicata dalla stessa
 * autorita' nello stesso formato. Il perche' esteso, e la condizione che le
 * farebbe spacchettare, stanno nel commento del
 * V12__codifiche_ministeriali.sql.
 */
public enum TipoCodifica {

    /**
     * I comuni italiani. E' la famiglia grande — circa settemilanovecento voci — ed
     * e' l'unica che usa la colonna della provincia, perche' in Italia gli omonimi
     * sono tanti: di "San Giovanni" ce n'e' uno per regione.
     */
    COMUNE,

    /** Gli stati esteri, per chi non e' nato in Italia. */
    STATO,

    /**
     * I tipi di documento nella codifica del Ministero.
     *
     * <p><b>Non e' il nostro {@link TipoDocumento}</b>, ed e' la distinzione che
     * qui e' piu' facile perdere: il nostro enum ha quattro valori e serve a
     * registrare che documento e' stato esibito al banco; questa tabella ne ha una
     * trentina ed e' cio' che la Questura si aspetta di ricevere. La corrispondenza
     * fra i due e' <b>logica nostra</b> e non un dato importabile: nessun file del
     * Ministero dice come i nostri quattro valori si mappano sui suoi trenta.
     */
    TIPO_DOCUMENTO,

    /**
     * Che ruolo ha l'ospite nel gruppo che soggiorna: ospite singolo, capofamiglia,
     * familiare, capogruppo, membro del gruppo.
     *
     * <p>E' la famiglia piu' piccola — cinque voci — ed e' quella che il 22b usera'
     * per il campo nuovo sull'{@link Ospite}: la schedina non chiede solo chi sei,
     * chiede anche con chi sei.
     */
    TIPO_ALLOGGIATO
}
