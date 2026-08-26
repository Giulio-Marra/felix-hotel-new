package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.AccountSummary;
import com.felixhotel.backend.dto.UtenteSintesi;
import com.felixhotel.backend.entity.Utente;
import org.springframework.stereotype.Component;

/**
 * Conversione Entity -> DTO per {@link Utente}. Scritta a mano per scelta di
 * progetto (niente MapStruct): mapping esplicito, senza generazione di
 * codice a compile-time. Il DTO {@code AccountSummary} e' invece generato
 * dallo spec OpenAPI (contract-first, vedi felix-hotel-api.yaml).
 */
@Component
public class UtenteMapper {

    public AccountSummary toAccountSummary(Utente utente) {
        return new AccountSummary()
                .id(utente.getId())
                .nome(utente.getNome())
                .cognome(utente.getCognome())
                .email(utente.getEmail())
                .ruolo(utente.getRuolo().getNome());
    }

    /**
     * Versione ridotta, per quando il cliente compare <b>dentro</b> un'altra
     * risorsa (oggi: la prenotazione che ha fatto). Porta id, nome, cognome ed
     * email.
     *
     * <p>Non e' {@code AccountSummary} per due ragioni diverse. La prima e' la
     * stessa di {@code TipologiaCameraMapper.toSintesi}: chi guarda un elenco di
     * prenotazioni vuole sapere di chi sono, non rileggere un profilo. La
     * seconda pesa di piu': {@code AccountSummary} porta con se' il <b>ruolo</b>,
     * che dentro una prenotazione non serve a niente e direbbe a chiunque possa
     * vederla se quel cliente sia un amministratore.
     *
     * <p>L'email invece resta, perche' e' cio' con cui il personale riconosce e
     * contatta un cliente — ed e' visibile solo a chi ha diritto di vedere la
     * prenotazione, che per un USER vuol dire soltanto le proprie.
     */
    public UtenteSintesi toSintesi(Utente utente) {
        return new UtenteSintesi()
                .id(utente.getId())
                .nome(utente.getNome())
                .cognome(utente.getCognome())
                .email(utente.getEmail());
    }
}
