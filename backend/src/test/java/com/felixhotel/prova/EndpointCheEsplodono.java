package com.felixhotel.prova;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoint che falliscono di proposito, uno per ogni rete difensiva del
 * {@code GlobalExceptionHandler} che in funzionamento normale non scatta mai.
 *
 * <p><b>Perche' servono.</b> Tre handler — {@code handleResponseStatus},
 * {@code handleAutenticazione} e il catch-all {@code handleGenerica} — non
 * erano esercitati da nessun test, e il motivo e' che per arrivarci serve
 * qualcosa che si rompa: nel codice di produzione quei percorsi si aprono solo
 * quando c'e' un guasto, ed e' esattamente per quello che nessuno li aveva mai
 * visti funzionare. Un handler mai eseguito e' una promessa senza codice che la
 * mantenga (regola 17), e la promessa piu' grossa e' quella del catch-all: che
 * chi chiama non veda mai il dettaglio di cio' che e' esploso.
 *
 * <p>Il guasto va quindi provocato, e questo e' il posto in cui farlo: qui una
 * classe che lancia eccezioni e' il comportamento voluto, mentre in produzione
 * sarebbe un difetto.
 */
@RestController
public class EndpointCheEsplodono {

    /** Solleva una ResponseStatusException che dichiara sia lo status sia il motivo. */
    public static final String STATUS_CON_MOTIVO = "/test-only/esplode/status-con-motivo";

    /** Solleva una ResponseStatusException col solo status: getReason() resta null. */
    public static final String STATUS_SENZA_MOTIVO = "/test-only/esplode/status-senza-motivo";

    /** Solleva un'AuthenticationException che nessun Service ha convertito. */
    public static final String AUTENTICAZIONE = "/test-only/esplode/autenticazione";

    /** Solleva un'eccezione qualunque, cioe' il caso del catch-all. */
    public static final String IMPREVISTO = "/test-only/esplode/imprevisto";

    /** Il motivo dichiarato dall'eccezione, che l'handler deve riportare al client. */
    public static final String MOTIVO = "motivo dichiarato dall'eccezione";

    /**
     * Il dettaglio interno del guasto simulato. Il test verifica che questa
     * stringa <b>non</b> compaia nella risposta: e' la forma verificabile della
     * promessa "stacktrace e dettagli restano nei log".
     */
    public static final String DETTAGLIO_INTERNO = "dettaglio-interno-che-non-deve-uscire";

    @GetMapping(STATUS_CON_MOTIVO)
    public void statusConMotivo() {
        throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, MOTIVO);
    }

    /**
     * Senza motivo, cosi' l'handler deve ripiegare sulla descrizione standard
     * dello status. E' un ramo vero del codice — {@code getReason()} puo' essere
     * null — e senza questo endpoint sarebbe l'unico modo di scoprirlo un
     * {@code message} null servito a un client.
     */
    @GetMapping(STATUS_SENZA_MOTIVO)
    public void statusSenzaMotivo() {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
    }

    /**
     * Un'AuthenticationException che arriva fino all'advice. In produzione non
     * succede perche' {@code AuthServiceImpl.login} cattura la propria per
     * rispondere "Credenziali non valide": questo handler e' la rete per tutte
     * le altre, e prima d'ora non l'aveva mai attraversata nessuna.
     */
    @GetMapping(AUTENTICAZIONE)
    public void autenticazione() {
        throw new BadCredentialsException(DETTAGLIO_INTERNO);
    }

    /**
     * Il caso del catch-all: un'eccezione che nessun handler piu' specifico
     * riconosce. Il messaggio porta un dettaglio riconoscibile proprio perche'
     * il test possa pretendere che non venga restituito.
     */
    @GetMapping(IMPREVISTO)
    public void imprevisto() {
        throw new IllegalStateException(DETTAGLIO_INTERNO);
    }
}
