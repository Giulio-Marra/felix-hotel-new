package com.felixhotel.backend.entity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Punto del ciclo di vita in cui si trova una prenotazione.
 *
 * <p><b>La distinzione che conta e' fra chi occupa una camera e chi no</b>, ed
 * e' quella che il calcolo della disponibilita' interroga: {@link #CONFERMATA},
 * {@link #CHECK_IN} e {@link #CHECK_OUT} tengono impegnata una stanza per il
 * loro periodo, {@link #IN_ATTESA} e {@link #ANNULLATA} no. Vedi
 * {@link #occupaCamera()}, che e' l'unico posto in cui quell'elenco e' scritto.
 *
 * <p><b>Perche' CHECK_IN e CHECK_OUT esistono gia' senza avere un endpoint che
 * ci porti.</b> Nessuna transizione di oggi raggiunge quei due stati — arrivano
 * col check-in, che e' un branch a parte — ma i due valori non sono una
 * promessa vuota: il CHECK del database li ammette da sempre e la query di
 * disponibilita' li conta. Toglierli vorrebbe dire scrivere un calcolo che
 * dimentica di considerare occupate le camere in cui c'e' qualcuno dentro, e
 * poi ricordarsi di correggerlo. Un test li esercita passando dal repository,
 * proprio perche' l'API non ci arriva.
 *
 * <p>Vive come enum Java e non come tabella, per la stessa ragione di
 * {@link StatoCamera}: l'elenco e' chiuso e lo impone un CHECK nel DDL
 * (V1__init_schema.sql). I nomi coincidono con i valori del CHECK e con l'enum
 * generato dallo spec OpenAPI: tre elenchi che devono restare allineati.
 */
public enum StatoPrenotazione {

    /**
     * Il carrello: un appunto personale che <b>non riserva niente</b>.
     *
     * <p>E' lo stato con cui ogni prenotazione nasce, e non blocca la
     * disponibilita' per gli altri: due clienti possono avere nel carrello
     * l'ultima camera libera, e a prenderla e' quello che conferma per primo.
     * Non ha scadenza — la scelta di non metterne una e' deliberata, ed e' il
     * motivo per cui la conferma ricontrolla la disponibilita'.
     */
    IN_ATTESA,

    /** Prenotazione vera: la camera e' riservata per quel periodo. */
    CONFERMATA,

    /** L'ospite e' arrivato e la camera fisica gli e' stata assegnata. */
    CHECK_IN,

    /** L'ospite e' partito. Resta occupata per il periodo trascorso: e' storia, non disponibilita'. */
    CHECK_OUT,

    /** Non vale piu' e ha restituito il posto. E' il modo in cui una prenotazione smette di contare senza sparire. */
    ANNULLATA;

    /**
     * Se una prenotazione in questo stato tenga impegnata una camera.
     *
     * <p>Sta qui e non dentro la query per un motivo pratico: e' la stessa
     * domanda che si fanno il calcolo della disponibilita' e chi decide se una
     * transizione sia lecita, e due elenchi scritti in due posti sono due
     * elenchi che prima o poi divergono. La query non puo' chiamare questo
     * metodo — gira in database — ma il test che tiene allineati i due lo fa,
     * ed e' li' che la divergenza si vedrebbe.
     */
    public boolean occupaCamera() {
        return this == CONFERMATA || this == CHECK_IN || this == CHECK_OUT;
    }

    /**
     * Gli stati che tengono impegnata una camera, nella forma che serve alla
     * query di disponibilita'.
     *
     * <p><b>E' derivato da {@link #occupaCamera()}, non riscritto</b>, ed e' il
     * punto di tutto: la query gira in database e non puo' chiamare un metodo
     * Java, quindi l'elenco deve comunque arrivare li' come parametro — ma
     * calcolarlo dall'unica definizione che esiste vuol dire che aggiungere un
     * sesto stato costringe a decidere una volta sola se occupi o no. Scriverlo
     * a mano come {@code Set.of(CONFERMATA, CHECK_IN, CHECK_OUT)} avrebbe
     * ottenuto lo stesso risultato oggi e un elenco dimenticato domani.
     */
    public static Set<StatoPrenotazione> statiCheOccupano() {
        return EnumSet.copyOf(Arrays.stream(values()).filter(StatoPrenotazione::occupaCamera).toList());
    }
}
