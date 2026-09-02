package com.felixhotel.backend.service;

/**
 * Il modo in cui questa applicazione manda un'email.
 *
 * <p><b>E' una porta, e serve a due cose diverse.</b> La prima e' quella ovvia: nei
 * test non si spedisce niente, e un'implementazione finta raccoglie i messaggi in
 * memoria — scelta del 2026-08-28, perche' un container SMTP proverebbe che
 * {@code JavaMailSender} sa parlare SMTP, che e' codice di Spring e non nostro. La
 * seconda conta di piu': tiene i Service di dominio all'oscuro di <i>come</i> si
 * spedisce, cosi' il giorno in cui il trasporto cambia non si tocca nessuno di loro.
 *
 * <p><b>Un metodo che non fallisce mai</b>, ed e' la decisione portante di questa
 * interfaccia: {@link #invia} non dichiara eccezioni e non ne lascia passare. Non e'
 * pigrizia — e' che <i>nessuna</i> delle operazioni che mandano email deve poter
 * fallire per colpa della posta. Una prenotazione confermata e' confermata anche se
 * l'SMTP e' irraggiungibile; una registrazione riuscita e' riuscita anche se la mail
 * di conferma non parte. Il contrario vorrebbe dire che un guasto del fornitore di
 * posta ferma le vendite dell'albergo.
 *
 * <p><b>Il prezzo di quella scelta, dichiarato</b>: un messaggio perso e' perso, e se
 * ne accorge solo chi legge i log. Non c'e' nessuna coda, nessun ritentativo e nessuna
 * tabella di messaggi in uscita. Per la verifica e per il reset non e' grave, perche'
 * l'utente puo' chiederne un altro; per la conferma di prenotazione lo e' di piu', ed
 * e' scritto fra i gap.
 *
 * <p><b>Si spedisce dopo il commit.</b> Chi chiama non deve farci caso ed e' proprio il
 * punto: se una transazione e' aperta, l'invio viene rimandato al momento in cui quella
 * transazione e' andata a buon fine. Senza, un rollback lascerebbe nella casella di
 * qualcuno il link a un token che nel database non esiste piu' — un errore che si
 * manifesta come "questo link non e' valido" e che nessuno saprebbe piu' spiegare.
 */
public interface ServizioEmail {

    /**
     * Manda un messaggio. Non fallisce e non blocca chi chiama.
     *
     * @param messaggio destinatario, oggetto e corpo, gia' pronti
     */
    void invia(MessaggioEmail messaggio);
}
