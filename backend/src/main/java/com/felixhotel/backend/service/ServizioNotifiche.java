package com.felixhotel.backend.service;

import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.security.TipoAccount;

/**
 * Le quattro email che questa applicazione manda.
 *
 * <p><b>I metodi parlano di dominio e non di posta</b>, ed e' tutto il punto
 * dell'interfaccia: chi la chiama dice <i>"questo utente deve confermare il suo
 * indirizzo"</i>, non <i>"manda un messaggio con questo testo"</i>. Cosi' i Service di
 * dominio non sanno che esistono i token, i testi e l'SMTP, e il giorno in cui una di
 * queste notifiche passasse per un altro canale cambierebbe l'implementazione e nessuno
 * di loro.
 *
 * <p><b>Nessun metodo restituisce niente e nessuno solleva</b>: una notifica non
 * riuscita non deve poter far fallire l'operazione che l'ha richiesta — vedi
 * {@link ServizioEmail} per il perche' esteso e per cosa questo costa.
 */
public interface ServizioNotifiche {

    /** Il link con cui un cliente appena registrato conferma il proprio indirizzo. */
    void verificaIndirizzo(Utente utente);

    /** L'invito con cui un membro del personale sceglie la propria password la prima volta. */
    void invitoPersonale(Staff staff);

    /**
     * Il link per reimpostare la password.
     *
     * <p><b>Prende i dati sciolti e non un'entita'</b>, al contrario degli altri tre, ed
     * e' l'unica firma di questa interfaccia che meriti una spiegazione: il reset vale
     * per i clienti <i>e</i> per il personale, che sono due tabelle diverse senza un
     * antenato comune. Le alternative erano due metodi gemelli o un'interfaccia in piu'
     * implementata da {@code Utente} e {@code Staff}; la prima duplica, la seconda mette
     * un tipo tecnico dentro il dominio per un uso solo.
     *
     * @param tipoAccount in quale delle due tabelle vive chi ha chiesto il reset: senza,
     *                    l'id da solo non identifica nessuno
     */
    void resetPassword(String email, String nome, TipoAccount tipoAccount, Long soggettoId);

    /**
     * La conferma di prenotazione al cliente.
     *
     * <p>Va chiamata <b>dentro la transazione</b> di chi conferma: legge relazioni LAZY
     * della prenotazione, come tutti i mapper del progetto (regola 15).
     */
    void confermaPrenotazione(Prenotazione prenotazione);
}
