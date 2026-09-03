package com.felixhotel.backend.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Decide se il backend possa andare a bussare a un indirizzo.
 *
 * <p><b>Serve perche' esiste una rotta che fa partire una richiesta scelta da qualcun
 * altro</b>: le sorgenti di calendario. E' l'unica del progetto, e trasforma il backend in
 * un messaggero — chi configura l'indirizzo non raggiunge quella macchina, ma <i>noi</i>
 * si', e noi siamo dentro la rete. Senza un controllo, {@code http://localhost:5432} o
 * {@code http://169.254.169.254/latest/meta-data/} diventano modi di farsi dire da fuori
 * cosa c'e' dentro: non il contenuto, che non torna mai a chi chiama, ma <b>se quel punto
 * risponde</b>, che e' gia' una mappa della rete.
 *
 * <p><b>Il controllo sta qui e non nel Service</b> per una ragione precisa: va fatto
 * <i>anche</i> al momento dello scarico, e non solo quando si salva la configurazione. Un
 * nome si puo' far risolvere a un indirizzo pubblico oggi e a uno interno domani, e un
 * canale puo' rispondere con un redirect verso l'interno. Chi salva vede un 400 subito
 * perche' e' comodo, ma la difesa vera e' quella che scatta ogni volta.
 *
 * <p><b>In sviluppo e nei test si apre il solo loopback</b>, con la stessa forma del CORS
 * (regola 20): chiuso di default — cioe' in produzione, dove nessuno lo configura — e
 * aperto solo dal profilo che ne ha bisogno. La ragione e' concreta: un canale finto vive
 * su {@code 127.0.0.1}, quindi senza questa apertura non si potrebbe provare niente di
 * quel che questa classe protegge.
 *
 * <p><b>Il loopback e non "gli indirizzi interni"</b>, ed e' una restrizione fatta
 * rileggendo: la prima stesura apriva tutto, e cosi' in sviluppo sarebbe passato anche
 * {@code 169.254.169.254}. Un canale finto sta sulla macchina di chi sviluppa, mai
 * sull'endpoint dei metadati del cloud: aprire piu' del necessario avrebbe reso il
 * profilo {@code dev} un ambiente in cui la difesa non si puo' provare, e proprio nel caso
 * che conta di piu'.
 *
 * <p>Il default sta scritto <b>qui</b> e non nei file di configurazione, per la stessa
 * ragione del CORS: un default scritto due volte il giorno che cambia resta vero solo in
 * uno dei due.
 *
 * <p><b>Quel che resta scoperto, e va detto</b>: fra il momento in cui si risolve il nome
 * e quello in cui ci si connette, la risposta del DNS puo' cambiare — e' il
 * <i>DNS rebinding</i>, e chiuderlo davvero vorrebbe dire connettersi all'indirizzo gia'
 * risolto invece che al nome, cioe' una fabbrica di socket nostra. Sta nei gap con il suo
 * innesco. Quel che questa classe chiude e' l'attacco che si fa incollando un indirizzo,
 * che e' l'unico alla portata di chi ha accesso a quella rotta.
 */
@Component
public class IndirizzoConsentito {

    /** Gli unici schemi che ha senso scaricare. Un {@code file:} non e' un calendario. */
    private static final List<String> SCHEMI = List.of("http", "https");

    /**
     * Se lasciar passare il loopback. <b>Falso ovunque tranne dove e' scritto il
     * contrario</b>: il default chiuso vive qui, i profili {@code dev} e {@code test} lo
     * aprono. Tutto il resto della rete interna resta chiuso anche li'.
     */
    private final boolean consentiLoopback;

    public IndirizzoConsentito(
            @Value("${felix.canale.consenti-loopback:false}") boolean consentiLoopback) {
        this.consentiLoopback = consentiLoopback;
    }

    /**
     * Controlla schema e destinazione, sollevando se l'indirizzo non va bene.
     *
     * @throws IndirizzoNonConsentitoException se lo schema non e' ammesso, se il nome non
     *                                         si risolve, o se <b>anche uno solo</b> degli
     *                                         indirizzi a cui si risolve e' interno
     */
    public void verifica(String url) {
        URI uri = analizza(url);

        String schema = uri.getScheme();
        if (schema == null || !SCHEMI.contains(schema.toLowerCase(Locale.ROOT))) {
            throw new IndirizzoNonConsentitoException(
                    "L'indirizzo del calendario deve essere http o https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IndirizzoNonConsentitoException("L'indirizzo non ha un nome di host");
        }
        verificaDestinazione(host);
    }

    private static URI analizza(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException ex) {
            throw new IndirizzoNonConsentitoException("L'indirizzo non e' scritto in modo valido", ex);
        }
    }

    /**
     * Che il nome non porti dentro la nostra rete.
     *
     * <p><b>Si guardano tutti gli indirizzi a cui il nome si risolve, non il primo.</b> Un
     * nome puo' averne piu' di uno, e bastandone uno interno per fare il danno, basta uno
     * interno per rifiutare. Guardare solo il primo vorrebbe dire lasciar passare chi ne
     * mette due.
     */
    private void verificaDestinazione(String host) {
        InetAddress[] indirizzi;
        try {
            indirizzi = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new IndirizzoNonConsentitoException("Il nome '" + host + "' non si risolve", ex);
        }

        for (InetAddress indirizzo : indirizzi) {
            if (interno(indirizzo) && !(consentiLoopback && indirizzo.isLoopbackAddress())) {
                // L'indirizzo risolto NON entra nel messaggio: sarebbe proprio la risposta
                // che l'attacco cerca — "questo nome punta dentro" — servita in chiaro.
                throw new IndirizzoNonConsentitoException(
                        "L'indirizzo '" + host + "' punta dentro la rete e non si puo' interrogare");
            }
        }
    }

    /**
     * Se un indirizzo appartiene alla rete e non al mondo.
     *
     * <p>I primi cinque controlli li ha gia' Java. Il sesto no: gli indirizzi IPv6
     * <b>unique-local</b> ({@code fc00::/7}) sono l'equivalente moderno del 10.x, e
     * {@code isSiteLocalAddress()} non li vede perche' guarda ancora i {@code fec0::/10},
     * deprecati dal 2004. Senza questa riga, la difesa varrebbe su IPv4 e non su IPv6 —
     * cioe' non varrebbe.
     */
    private static boolean interno(InetAddress indirizzo) {
        return indirizzo.isAnyLocalAddress()
                || indirizzo.isLoopbackAddress()
                || indirizzo.isLinkLocalAddress()
                || indirizzo.isSiteLocalAddress()
                || indirizzo.isMulticastAddress()
                || uniqueLocalIpv6(indirizzo);
    }

    private static boolean uniqueLocalIpv6(InetAddress indirizzo) {
        byte[] byteDellIndirizzo = indirizzo.getAddress();
        return byteDellIndirizzo.length == 16 && (byteDellIndirizzo[0] & 0xFE) == 0xFC;
    }

    /**
     * L'indirizzo non si puo' interrogare.
     *
     * <p>Non estende {@code AppException} perche' le due rotte che la incontrano ne fanno
     * due cose diverse: chi <b>salva</b> una sorgente la traduce in un 400, chi la
     * <b>scarica</b> in un esito {@code ERRORE} sulla riga. Legarla a uno status HTTP
     * qui vorrebbe dire dare ragione a uno dei due.
     */
    public static class IndirizzoNonConsentitoException extends RuntimeException {

        public IndirizzoNonConsentitoException(String messaggio) {
            super(messaggio);
        }

        public IndirizzoNonConsentitoException(String messaggio, Throwable causa) {
            super(messaggio, causa);
        }
    }
}
