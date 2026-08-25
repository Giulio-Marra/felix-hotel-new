package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TipologiaCameraDotazioniRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Costruisce i dati di partenza dei test.
 *
 * <p>Esiste per una ragione di forma: nessun test deve contenere il rituale di
 * riempimento di un DTO campo per campo. Un test si legge per quello che
 * verifica, non per come si prepara i dati — quindi parte da un oggetto valido
 * e cambia solo il campo che gli interessa
 * ({@code registerRequest().password("corta")}).
 *
 * <p>Se domani un DTO guadagna un campo obbligatorio, si aggiorna questa
 * classe e non trenta test.
 */
public class TestDataFactory {

    /** Password valida di default: sopra il minimo di 8 caratteri dichiarato nello spec. */
    public static final String PASSWORD_VALIDA = "PasswordSicura123";

    /**
     * Contatore per email univoche: statico perche' l'unicita' deve valere per
     * l'intera esecuzione, non per la singola classe di test. Il database e'
     * condiviso e non viene ripulito fra un test e l'altro, quindi due test che
     * usassero la stessa email si romperebbero a vicenda con un 409.
     */
    private static final AtomicInteger CONTATORE = new AtomicInteger();

    /** Email mai usata prima in questa esecuzione. */
    public String emailUnivoca() {
        return "test.utente." + System.currentTimeMillis() + "." + CONTATORE.incrementAndGet() + "@example.com";
    }

    /**
     * Richiesta di registrazione valida in ogni campo. I test la modificano
     * con i setter fluenti dei DTO generati per creare il caso che vogliono
     * verificare.
     */
    public RegisterRequest registerRequest() {
        return new RegisterRequest()
                .nome("Mario")
                .cognome("Rossi")
                .email(emailUnivoca())
                .password(PASSWORD_VALIDA)
                .consensoPrivacy(true);
    }

    /** Credenziali di login corrispondenti a una registrazione andata a buon fine. */
    public LoginRequest loginRequest(String email, String password) {
        return new LoginRequest()
                .email(email)
                .password(password);
    }

    /**
     * Nome di tipologia mai usato prima in questa esecuzione. Stessa ragione
     * delle email: il nome e' unico in database (indice su lower(nome)) e il
     * database non viene ripulito fra un test e l'altro, quindi due test che
     * usassero "Doppia" si romperebbero a vicenda con un 409.
     */
    public String nomeTipologiaUnivoco() {
        return "Doppia " + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet();
    }

    /**
     * Tipologia di camera valida in ogni campo. Come per la registrazione, i
     * test partono da questa e cambiano solo il campo che vogliono verificare
     * ({@code tipologiaCameraRequest().capienzaMax(0)}).
     */
    public TipologiaCameraRequest tipologiaCameraRequest() {
        return new TipologiaCameraRequest()
                .nome(nomeTipologiaUnivoco())
                .descrizione("Camera doppia con vista sul giardino")
                .capienzaMax(2)
                .prezzoNotte(new BigDecimal("120.00"));
    }

    /**
     * Nome di dotazione mai usato prima in questa esecuzione, per la stessa
     * ragione del nome di tipologia: e' unico in database (indice su
     * lower(nome), vedi V3) e il database non viene ripulito fra un test e
     * l'altro.
     */
    public String nomeDotazioneUnivoco() {
        return nomeDotazioneUnivoco("Wi-Fi");
    }

    /**
     * Come sopra, ma con un prefisso scelto da chi chiama. Serve ai test che
     * verificano l'<b>ordinamento</b> delle dotazioni: li' i nomi devono essere
     * univoci — il vincolo vale comunque — e insieme in un ordine alfabetico
     * noto, che con un prefisso solo non sarebbe esprimibile.
     */
    public String nomeDotazioneUnivoco(String prefisso) {
        return prefisso + " " + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet();
    }

    /**
     * Dotazione valida in ogni campo. Come per le altre, i test partono da
     * questa e cambiano solo il campo che vogliono verificare
     * ({@code dotazioneRequest().nome("")}).
     */
    public DotazioneRequest dotazioneRequest() {
        return new DotazioneRequest()
                .nome(nomeDotazioneUnivoco())
                .descrizione("Connessione senza fili gratuita in tutta la struttura");
    }

    /**
     * Richiesta che assegna a una tipologia esattamente le dotazioni indicate.
     * Senza argomenti produce l'insieme vuoto, che e' il modo legittimo di
     * toglierle tutte e non un caso limite da evitare.
     */
    public TipologiaCameraDotazioniRequest dotazioniIdsRequest(Long... ids) {
        return new TipologiaCameraDotazioniRequest()
                .dotazioniIds(new LinkedHashSet<>(Arrays.asList(ids)));
    }
}
