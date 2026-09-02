package com.felixhotel.backend.service;

/**
 * Un messaggio pronto da spedire: a chi, con che oggetto, con che testo.
 *
 * <p><b>Testo semplice e non HTML</b>, ed e' una scelta e non un rinvio mascherato: un
 * corpo HTML vuole un motore di template, cioe' una dipendenza e un secondo posto in
 * cui il contenuto puo' rompersi, per un guadagno che qui e' solo estetico. Le quattro
 * email di questo progetto sono una frase e un link — la piu' lunga e' la conferma di
 * prenotazione — e in testo semplice arrivano dappertutto, comprese le caselle che
 * l'HTML lo bloccano. Il giorno in cui servira' un'email che <i>sembra</i> qualcosa, e'
 * una decisione sua.
 *
 * <p><b>Un record e non tre parametri</b>: cosi' {@link ServizioEmail#invia} ha una
 * firma sola che non cambia se un giorno servisse un mittente diverso per messaggio o
 * un allegato, e chi costruisce il messaggio non puo' invertire due stringhe adiacenti
 * senza che si veda.
 *
 * @param destinatario indirizzo di chi lo riceve
 * @param oggetto      la riga dell'oggetto
 * @param corpo        il testo, gia' composto
 */
public record MessaggioEmail(String destinatario, String oggetto, String corpo) {
}
