package com.felixhotel.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Origini ammesse dal CORS (prefisso {@code felix.security.cors} in
 * {@code application.properties}).
 *
 * <p><b>Il default e' la configurazione chiusa</b>, e non e' un dettaglio: la
 * lista vuota significa "nessuna origine diversa dalla nostra puo' chiamare
 * l'API dal browser". Prima qui c'era {@code http://localhost:*} scritto nel
 * codice, quindi la configurazione permissiva era quella che partiva ovunque —
 * anche in produzione, dove nessuno l'avrebbe scelta. Ora il permesso e'
 * qualcosa che un ambiente si prende esplicitamente: lo fa il profilo
 * {@code dev} (vedi {@code application-dev.properties}), che apre a localhost
 * perche' li' il frontend React gira su una porta diversa dal backend.
 * Dimenticarsi di configurarlo lascia l'API chiusa, non aperta.
 *
 * <p>Le voci sono <i>pattern</i> ({@code allowedOriginPatterns}), non origini
 * esatte, cosi' si puo' scrivere {@code http://localhost:*} senza elencare una
 * per una le porte che Vite sceglie al volo. In produzione vanno messi i domini
 * veri, senza jolly.
 *
 * @param allowedOrigins pattern di origine ammessi. Vuoto = nessuno.
 */
@ConfigurationProperties(prefix = "felix.security.cors")
public record CorsProperties(

        @DefaultValue({}) List<String> allowedOrigins) {

    /**
     * Congela la lista alla costruzione.
     *
     * <p>Un record custodisce il riferimento che gli viene passato, non una
     * copia: senza questo, chi ha costruito l'oggetto continuerebbe a poter
     * aggiungere origini alla lista, e {@link #allowedOrigins()} le
     * restituirebbe a chi le legge. Su una lista qualsiasi sarebbe una sottigliezza;
     * su <b>quale sito puo' chiamare l'API dal browser</b> vuol dire che il
     * permesso non e' piu' deciso solo dalla configurazione, ed e' esattamente il
     * contrario di quel che dice la regola 20.
     *
     * <p>Segnalato da SpotBugs (EI_EXPOSE_REP / EI_EXPOSE_REP2) il 2026-08-25:
     * nessuno oggi tiene un riferimento alla lista dopo averla passata, quindi il
     * difetto non era sfruttabile — ma costa tre righe renderlo impossibile
     * invece che improbabile.
     */
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
