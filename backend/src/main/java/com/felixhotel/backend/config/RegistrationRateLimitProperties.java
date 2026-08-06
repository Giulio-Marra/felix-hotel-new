package com.felixhotel.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Parametri del limite di frequenza sulla registrazione (prefisso
 * {@code felix.security.registration} in {@code application.properties}).
 *
 * <p><b>Cosa protegge, e perche' non e' lo stesso problema del login.</b> Sul
 * login si contano i tentativi <i>falliti</i>, perche' il danno e' indovinare
 * una password. Qui il danno e' la registrazione che <i>riesce</i>: chiunque
 * puo' chiamare l'endpoint senza autenticarsi, e ogni chiamata ci costa un hash
 * BCrypt (lento di proposito) piu' una scrittura sul database. Senza un limite,
 * un solo client puo' riempire la tabella degli utenti di account inventati e
 * tenere occupata l'applicazione a fabbricarli. Per questo qui si conta
 * <b>ogni</b> tentativo, riuscito o no.
 *
 * <p><b>Una sola chiave, l'indirizzo IP.</b> Sul login la seconda chiave e'
 * l'email, cioe' l'account preso di mira; in registrazione un account da
 * proteggere non c'e' ancora, e contare per email indirizzo non servirebbe a
 * niente — chi crea account a macchina ne usa una diversa ogni volta. Resta la
 * provenienza della richiesta. Vale anche qui l'avvertenza del login: l'IP si
 * legge dalla connessione e non da {@code X-Forwarded-For}, quindi dietro un
 * reverse proxy tutte le richieste arriverebbero con lo stesso indirizzo finche'
 * non si configura {@code ForwardedHeaderFilter} (vedi {@code AuthController}).
 *
 * <p>Come sul login il meccanismo e' un <b>ritardo progressivo, non un blocco</b>
 * (vedi {@link LoginRateLimitProperties} per il ragionamento completo): chi si
 * registra davvero non se ne accorge, chi insiste rallenta sempre di piu'.
 *
 * <p>I valori di default sono molto piu' stretti di quelli del login perche' la
 * frequenza legittima e' incomparabile: una persona si registra una volta sola.
 * I pochi tentativi liberi servono a chi sbaglia un campo e riprova, e a chi
 * condivide l'indirizzo con altri (rete di casa o aziendale, NAT).
 *
 * @param tentativiLiberiIp registrazioni tollerate dallo stesso indirizzo prima
 *                          che il ritardo cominci ad applicarsi. Attenzione a
 *                          come si traduce in pratica: il tentativo viene prima
 *                          lasciato passare e solo dopo contato, quindi con 5 ne
 *                          passano <b>sei</b> senza attesa — la sesta e' quella
 *                          che fa scattare il ritardo, e a pagarlo e' la settima.
 *                          Non e' una svista ma la condizione per cui chi sta
 *                          gia' aspettando non si allunga da solo l'attesa
 *                          insistendo, che renderebbe il ritardo un blocco
 * @param ritardoIniziale   attesa imposta al primo tentativo oltre la soglia.
 *                          Raddoppia ad ogni tentativo successivo
 * @param ritardoMassimo    tetto del raddoppio, piu' alto di quello del login:
 *                          li' dietro un'attesa lunga c'e' un utente vero che
 *                          vuole entrare nel proprio account, qui c'e' quasi
 *                          sempre qualcosa di automatico
 * @param finestra          inattivita' dopo la quale il conteggio si azzera da
 *                          solo
 */
@ConfigurationProperties(prefix = "felix.security.registration")
public record RegistrationRateLimitProperties(

        @DefaultValue("5") int tentativiLiberiIp,
        @DefaultValue("5s") Duration ritardoIniziale,
        @DefaultValue("5m") Duration ritardoMassimo,
        @DefaultValue("1h") Duration finestra) {
}
