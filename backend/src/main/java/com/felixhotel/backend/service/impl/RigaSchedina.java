package com.felixhotel.backend.service.impl;

import java.time.LocalDate;

/**
 * I dati di una schedina, gia' risolti, pronti da scrivere sul tracciato.
 *
 * <p><b>Sta fra il dominio e il formato, e serve a tenerli separati.</b> Da una
 * parte c'e' l'{@code Ospite} con la sua prenotazione, dall'altra
 * {@link TracciatoAlloggiati}, che sa solo contare caratteri e riempire caselle.
 * Senza questo passaggio il formattatore dovrebbe conoscere le entita', le
 * codifiche e le regole su chi porta il documento — e non si potrebbe piu' provare
 * senza un database.
 *
 * <p><b>Qui dentro non c'e' piu' niente da decidere</b>, ed e' il punto: i codici
 * ministeriali sono gia' stati tradotti, la provincia e' gia' stata cercata, i campi
 * che un familiare non deve compilare sono gia' {@code null}. Chi legge una riga di
 * questo record legge quel che finira' sul file.
 *
 * <p>Un {@code null} vuol dire <b>casella vuota</b> e non "dato mancante": a
 * quest'altezza i dati mancanti hanno gia' fatto fallire l'export con un 409 che
 * nomina la persona. Le caselle legittimamente vuote sono quelle del documento per
 * chi e' accompagnato, e il comune o lo stato di nascita — che sono alternativi.
 *
 * @param codiceTipoAlloggiato    codice ministeriale del ruolo nel gruppo
 * @param dataArrivo              giorno di arrivo, dalla prenotazione
 * @param giorniPermanenza        notti prenotate, che il tracciato scrive in due cifre
 * @param cognome                 come sta sul documento
 * @param nome                    come sta sul documento
 * @param codiceSesso             {@code 1} o {@code 2}
 * @param dataNascita             sempre presente, obbligatoria dal V10
 * @param comuneNascita           codice del comune, oppure {@code null} per chi e' nato fuori
 * @param provinciaNascita        sigla letta dalla codifica del comune, {@code null} con lui
 * @param statoNascita            codice dello stato estero, {@code null} per chi e' nato in Italia
 * @param cittadinanza            codice dello stato di cittadinanza
 * @param codiceTipoDocumento     {@code null} per chi non porta il documento sulla schedina
 * @param numeroDocumento         {@code null} insieme al tipo
 * @param luogoRilascioDocumento  {@code null} insieme al tipo
 */
public record RigaSchedina(
        String codiceTipoAlloggiato,
        LocalDate dataArrivo,
        int giorniPermanenza,
        String cognome,
        String nome,
        String codiceSesso,
        LocalDate dataNascita,
        String comuneNascita,
        String provinciaNascita,
        String statoNascita,
        String cittadinanza,
        String codiceTipoDocumento,
        String numeroDocumento,
        String luogoRilascioDocumento) {
}
