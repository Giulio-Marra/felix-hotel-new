package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.RegisterRequest;

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
}
