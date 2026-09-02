package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.entity.TipoTokenEmail;
import com.felixhotel.backend.entity.TokenEmail;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.repository.TokenEmailRepository;
import com.felixhotel.backend.security.TipoAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Emette e consuma i token che viaggiano per email.
 *
 * <p><b>E' l'unico posto che vede un token in chiaro.</b> {@link #emetti} lo genera, lo
 * restituisce a chi deve metterlo nel link, e in tabella ne scrive solo l'impronta;
 * {@link #consuma} rifa' l'impronta di quel che arriva e cerca quella. Nessun altro
 * punto del progetto tocca ne' l'una ne' l'altra.
 *
 * <p><b>Perche' non e' un'interfaccia con la sua implementazione</b>, al contrario di
 * tutti gli altri Service (regola 3): quelli sono servizi di dominio che un Controller
 * chiama e che un test puo' voler sostituire. Questo non lo chiama nessun Controller —
 * lo usano altri Service — e sostituirlo con un finto vorrebbe dire non provare piu'
 * l'unica cosa che fa, cioe' la generazione e il confronto. E' la stessa forma di
 * {@code ContatoreTentativi}.
 */
@Service
@Slf4j
public class ServizioTokenEmail {

    /**
     * Quanti byte di casualita' ha un token.
     *
     * <p>Trentadue byte sono 256 bit, cioe' un segreto che non si indovina: e' il
     * numero che rende inutile difendersi dai tentativi a forza bruta, ed e' anche il
     * motivo per cui l'impronta e' SHA-256 e non BCrypt (vedi il V14).
     */
    private static final int BYTE_CASUALI = 32;

    /**
     * {@link SecureRandom} e non {@code Random}, ed e' la differenza che conta: il
     * secondo produce numeri prevedibili da chi ne ha visti abbastanza, e qui il numero
     * <b>e'</b> la credenziale.
     */
    private final SecureRandom casuale = new SecureRandom();

    private final TokenEmailRepository tokenRepository;

    /**
     * Da cosa dipende "adesso": la scadenza che si scrive emettendo, e il confronto che
     * si fa consumando. Iniettabile perche' un test che non possa spostare l'orologio
     * non ha nessun modo di provare la scadenza se non aspettando un'ora.
     */
    private final Clock clock;

    /**
     * Costruttore usato da Spring. L'annotazione serve perche' i costruttori pubblici
     * sono due, stessa forma gia' usata da {@code OspiteServiceImpl}: il progetto non ha
     * un bean {@code Clock}, e introdurlo adesso cambierebbe anche gli altri.
     */
    @Autowired
    public ServizioTokenEmail(TokenEmailRepository tokenRepository) {
        // Fuso di sistema e non UTC, come ovunque nel progetto: le date si leggono sul
        // calendario della reception.
        this(tokenRepository, Clock.systemDefaultZone());
    }

    /**
     * Costruttore per i test, che passano un {@code OrologioPilotato}.
     *
     * <p><b>Visibile al solo package e non pubblico</b>, ed e' l'unica cosa di questa
     * classe decisa da un attrezzo invece che da un ragionamento: SpotBugs segnala
     * {@code EI_EXPOSE_REP2} su un costruttore <i>pubblico</i> che memorizza un oggetto
     * ricevuto da fuori. Qui l'oggetto e' un bean singleton di Spring e nessuno lo muta,
     * quindi il difetto non e' reale — ma la regola del progetto e' che dal filtro di
     * SpotBugs passa solo cio' che <b>non si puo' correggere</b>, e questo si corregge.
     * Il prezzo e' che il suo test unitario vive in questo package invece che in
     * {@code ..service}, dove stanno gli altri.
     */
    ServizioTokenEmail(TokenEmailRepository tokenRepository, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    /**
     * Crea un token per un destinatario e restituisce la parte in chiaro.
     *
     * <p><b>I token pendenti dello stesso tipo per la stessa persona vengono tolti</b>:
     * chi chiede due volte il reset si aspetta che valga l'ultimo link ricevuto, e
     * lasciarne validi due vorrebbe dire una porta aperta in piu' e nessun modo, per chi
     * legge la posta, di sapere quale sia quello buono.
     *
     * @return il token in chiaro, da mettere nel link. Non viene salvato da nessuna
     *         parte: se chi chiama lo perde, non lo recupera piu' nessuno
     */
    @Transactional
    public String emetti(TipoTokenEmail tipo, TipoAccount tipoAccount, Long soggettoId) {
        tokenRepository.deleteByTipoAndTipoAccountAndSoggettoIdAndUsatoIlIsNull(
                tipo, tipoAccount, soggettoId);

        byte[] byteCasuali = new byte[BYTE_CASUALI];
        casuale.nextBytes(byteCasuali);
        // URL-safe e senza riempimento: questo valore finisce in un link, e un '+' o un
        // '=' dentro una query string sono due modi diversi di rovinarlo a seconda di
        // chi lo ricopia.
        String inChiaro = Base64.getUrlEncoder().withoutPadding().encodeToString(byteCasuali);

        TokenEmail token = new TokenEmail();
        token.setTipo(tipo);
        token.setTipoAccount(tipoAccount);
        token.setSoggettoId(soggettoId);
        token.setTokenHash(impronta(inChiaro));
        token.setScadenza(LocalDateTime.now(clock).plus(tipo.durata()));
        tokenRepository.save(token);

        return inChiaro;
    }

    /**
     * Verifica un token e lo segna come usato.
     *
     * <p><b>Un messaggio solo per tre casi diversi</b> — non esiste, e' scaduto, e' gia'
     * stato usato — ed e' una scelta: sono tutti e tre "questo link non vale", chi legge
     * deve fare la stessa cosa (farsene mandare un altro), e distinguerli direbbe a chi
     * prova token a caso quali esistono. E' lo stesso criterio gia' applicato al login,
     * dove password sbagliata e account inesistente danno la stessa risposta.
     *
     * <p><b>Il tipo si controlla e non si desume</b>: un token di verifica non deve
     * poter resettare una password. Senza questo confronto, chi ha in mano un link di
     * conferma — che e' il piu' facile da ottenere, basta registrarsi — potrebbe
     * presentarlo all'endpoint del reset.
     *
     * @return il token consumato, da cui chi chiama legge destinatario e tipo di account
     * @throws BadRequestException se non e' valido, per qualunque delle tre ragioni
     */
    @Transactional
    public TokenEmail consuma(TipoTokenEmail tipo, String inChiaro) {
        LocalDateTime adesso = LocalDateTime.now(clock);

        TokenEmail token = tokenRepository.findByTokenHash(impronta(inChiaro))
                .filter(t -> t.getTipo() == tipo)
                .filter(t -> t.utilizzabile(adesso))
                .orElseThrow(() -> new BadRequestException(
                        "Il link non e' valido, e' scaduto oppure e' gia' stato usato:"
                                + " chiedine un altro"));

        token.setUsatoIl(adesso);
        return tokenRepository.save(token);
    }

    /**
     * L'impronta SHA-256 in esadecimale minuscolo.
     *
     * <p>{@code NoSuchAlgorithmException} diventa {@link IllegalStateException}: SHA-256
     * e' obbligatorio in ogni JVM, quindi se manca non e' un caso da gestire ma una
     * macchina rotta, e proseguire vorrebbe dire scrivere in tabella qualcosa che non e'
     * un'impronta.
     */
    private String impronta(String inChiaro) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(inChiaro.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 non disponibile in questa JVM", ex);
        }
    }

    /**
     * Toglie i token scaduti, una volta all'ora.
     *
     * <p>Stessa forma dei due contatori dei tentativi, che si ripuliscono ogni dieci
     * minuti, e stessa ragione: senza, la tabella cresce per sempre di una riga per ogni
     * registrazione e ogni reset, e nessuna di quelle righe serve piu' a niente.
     *
     * <p>Un'ora e non dieci minuti perche' qui non c'e' niente da liberare in fretta —
     * sono righe di database, non memoria del processo — e perche' la piu' corta delle
     * tre scadenze e' un'ora.
     */
    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void ripulisciScaduti() {
        int tolti = tokenRepository.deleteByScadenzaBefore(LocalDateTime.now(clock));
        if (tolti > 0) {
            log.debug("Token email scaduti rimossi: {}", tolti);
        }
    }
}
