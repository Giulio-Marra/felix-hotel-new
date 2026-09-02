package com.felixhotel.backend.entity;

/**
 * Che ruolo ha un {@link Ospite} nel gruppo con cui soggiorna.
 *
 * <p><b>La schedina non chiede solo chi sei, chiede con chi sei</b>, ed e' il
 * motivo per cui questo campo esiste: il tracciato di Alloggiati Web comincia
 * proprio da qui, e il valore decide quanto del resto della riga va compilato.
 * Chi risponde di se' — un ospite singolo, il capo di una famiglia, il capo di un
 * gruppo — porta anche il proprio documento; chi e' accompagnato no, perche' il
 * documento l'ha gia' esibito chi lo accompagna.
 *
 * <p><b>Perche' e' un enum nostro e non il codice del Ministero.</b> I codici veri
 * stanno in {@link VoceCodifica}, famiglia {@link TipoCodifica#TIPO_ALLOGGIATO},
 * e li importa l'ADMIN dal portale: qui dentro non ce n'e' nessuno, e la
 * corrispondenza fra i due elenchi sta in
 * {@code com.felixhotel.backend.service.impl.CodiciAlloggiati}. La ragione e' la
 * stessa gia' scritta su {@link TipoDocumento} ed e' l'unica che conta: la regola
 * <i>"al familiare il documento non si chiede"</i> non e' un dato pubblicato da
 * nessuno, e' logica di questa applicazione. Un file del Ministero elenca codici e
 * descrizioni, non dice come ci si comporta davanti a ognuno.
 *
 * <p><b>Cosa non fa questo enum</b>, e va detto perche' verrebbe da aspettarselo:
 * non impone che su una prenotazione da quattro persone ci sia esattamente un capo.
 * Il tracciato lo pretende, ma pretenderlo qui vorrebbe dire rifiutare la seconda
 * riga di un modulo che si sta compilando — cioe' obbligare chi sta al banco a
 * registrare le persone in un ordine preciso. Il controllo lo fa l'export, dove il
 * gruppo si guarda tutto insieme; il perche' esteso sta li'.
 */
public enum TipoAlloggiato {

    /**
     * Dorme qui da solo. E' il caso della grande maggioranza delle prenotazioni da
     * una persona, e sulla schedina porta tutti i dati del documento.
     */
    OSPITE_SINGOLO,

    /** Il capo di una famiglia che soggiorna insieme: risponde anche per gli altri. */
    CAPOFAMIGLIA,

    /** Il capo di un gruppo che non e' una famiglia — una comitiva, una squadra. */
    CAPOGRUPPO,

    /**
     * Chi viaggia sotto un {@link #CAPOFAMIGLIA}. Sulla schedina i campi del
     * documento restano <b>vuoti</b>: e' l'unica differenza di forma fra i due
     * gruppi di valori, ed e' cio' che questo enum serve a sapere.
     */
    FAMILIARE,

    /** Chi viaggia sotto un {@link #CAPOGRUPPO}, con la stessa regola del familiare. */
    MEMBRO_GRUPPO;

    /**
     * Se questa persona porta il proprio documento sulla schedina.
     *
     * <p>Il metodo sta sull'enum e non nel formattatore per la stessa ragione per
     * cui {@code StatoPrenotazione.occupaCamera()} sta sullo stato: e' una proprieta'
     * del valore, non una decisione di chi lo usa. Aggiungere domani un sesto valore
     * senza rispondere a questa domanda diventa un errore di compilazione invece di
     * un ramo dimenticato.
     */
    public boolean portaDocumento() {
        return this == OSPITE_SINGOLO || this == CAPOFAMIGLIA || this == CAPOGRUPPO;
    }
}
