package com.felixhotel.backend.security;

import com.felixhotel.backend.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Chi sta chiamando, e se appartiene al personale.
 *
 * <p>Sono due domande che ogni Service si e' finora riposto per conto suo, e
 * la seconda e' una <b>regola di sicurezza</b>, non una comodita' di firma:
 * dal 2026-08-27 i privilegi del personale pretendono due cose insieme — il
 * ruolo <i>e</i> il tipo dell'account — e una regola del genere scritta in piu'
 * copie e' una regola che prima o poi vale in un posto e non nell'altro. Questa
 * classe esiste perche' ci sia <b>una copia sola</b>, e perche' la prossima
 * risorsa che ne ha bisogno la trovi invece di riscriverla.
 *
 * <p><b>Perche' e' nata adesso.</b> La sottorisorsa degli ospiti e' riservata a
 * STAFF e ADMIN, cioe' e' il secondo posto che deve rispondere a "sei del
 * personale davvero?", e {@code @PreAuthorize} da solo non basta a rispondere:
 * quello guarda il ruolo e non sa niente della tabella da cui l'account viene.
 * Duplicare li' la meta' mancante voleva dire la seconda copia di una regola di
 * sicurezza; scriverla dentro quel branch voleva dire due decisioni
 * indipendenti nello stesso diff. Quindi prima questo, poi gli ospiti.
 *
 * <p><b>Il principal si prende dal {@code SecurityContextHolder} e non da
 * {@code @AuthenticationPrincipal}</b>: le firme dei metodi le impongono le
 * interfacce generate dallo spec OpenAPI, che non hanno un parametro per il
 * principal (regola 12), quindi non c'e' un argomento in cui Spring possa
 * iniettarlo. Nei Service e' comunque il posto giusto in cui leggerlo: e' li'
 * che stanno le decisioni che dipendono da chi chiama.
 *
 * <p>Non ha stato ne' dipendenze, ma resta un bean come tutti gli altri
 * collaboratori del progetto invece di diventare una classe di metodi statici:
 * chi lo usa lo dichiara nel costruttore, e da quella riga si vede che quel
 * Service guarda chi sta chiamando. Con un metodo statico la stessa dipendenza
 * sarebbe visibile solo leggendo il corpo dei metodi.
 */
@Component
public class ChiamanteCorrente {

    private static final String RUOLO_ADMIN = "ADMIN";
    private static final String RUOLO_STAFF = "STAFF";

    /**
     * L'account autenticato, o {@code UnauthorizedException} se non ce n'e'
     * nessuno.
     *
     * <p>Gli endpoint che chiamano questo metodo non sono in {@code permitAll},
     * quindi ci si arriva solo autenticati; il controllo resta perche' un
     * anonimo avrebbe come principal la stringa {@code "anonymousUser"}, che
     * senza questo {@code instanceof} diventerebbe una
     * {@code ClassCastException} — cioe' un 500 al posto di un 401.
     */
    public AppUserPrincipal autenticato() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new UnauthorizedException("Nessun account autenticato");
        }

        return principal;
    }

    /**
     * Se chi chiama appartiene al personale, cioe' se puo' vedere e toccare
     * anche cio' che non e' suo.
     *
     * <p><b>Le due fonti devono dire la stessa cosa</b>: il ruolo (una colonna
     * che si puo' cambiare) e il tipo dell'account (la tabella da cui e' stato
     * caricato). Fino al 2026-08-27 le prenotazioni guardavano il solo ruolo
     * mentre il gestore da registrare si risolveva sul solo tipo, e le due
     * risposte potevano divergere: una riga di {@code utente} con ruolo ADMIN —
     * che nessun endpoint produce, ma una {@code UPDATE} a mano si' — leggeva
     * <b>tutte</b> le prenotazioni e non poteva intestarne nessuna. Uno stato
     * che nessuno aveva disegnato.
     *
     * <p><b>Perche' comandano tutte e due e non una sola.</b> La divergenza si
     * poteva chiudere anche eleggendo una fonte sola, e non e' stato fatto
     * perche' le due domande restano diverse — il ruolo dice cosa un account
     * puo' fare, il tipo dice dove vive — e qui servono entrambe le risposte:
     * per toccare la roba altrui bisogna avere il privilegio <i>e</i> essere una
     * persona che lavora qui. Richiederle insieme fa fallire in sicurezza: un
     * account ibrido non guadagna i privilegi del personale, resta il cliente
     * che la sua tabella dice che e'.
     *
     * <p>Prende il principal invece di rileggerlo da se': chi lo chiama l'ha
     * gia' in mano da {@link #autenticato()}, e leggere due volte il contesto
     * vorrebbe dire fidarsi che dia la stessa risposta.
     */
    public boolean personale(AppUserPrincipal chiamante) {
        if (chiamante.getTipo() != TipoAccount.PERSONALE) {
            return false;
        }

        return RUOLO_ADMIN.equals(chiamante.getRuoloNome()) || RUOLO_STAFF.equals(chiamante.getRuoloNome());
    }

    /**
     * L'id del cliente che sta chiamando, con 401 se l'account non e' di un cliente.
     *
     * <p><b>401 e non 403</b>: non e' una questione di permessi, e' un token che vale per
     * un account che non e' quello che dice di essere — il ruolo dice una cosa e la
     * tabella in cui vive ne dice un'altra. E' la stessa asimmetria di
     * {@link #personale(AppUserPrincipal)}, guardata dall'altro capo.
     *
     * <p><b>E' salita qui il 2026-09-04</b>, da tre copie identiche in altrettanti punti
     * che chiedevano "questa roba e' tua?". Non ha bisogno di nessun repository — guarda
     * solo il principal — quindi sta bene in questa classe, che di repository non ne ha e
     * non deve averne: chi deve anche <i>trovare</i> la prenotazione passa da
     * {@link AccessoPrenotazioni}.
     */
    public Long idCliente(AppUserPrincipal chiamante) {
        if (chiamante.getTipo() != TipoAccount.CLIENTE) {
            throw new UnauthorizedException("L'account autenticato non e' quello di un cliente");
        }

        return chiamante.getUserId();
    }
}
