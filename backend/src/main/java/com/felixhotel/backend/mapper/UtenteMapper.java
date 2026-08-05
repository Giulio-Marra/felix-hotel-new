package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.AccountSummary;
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
}
