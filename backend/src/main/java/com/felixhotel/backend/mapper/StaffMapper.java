package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.StaffSintesi;
import com.felixhotel.backend.entity.Staff;
import org.springframework.stereotype.Component;

/**
 * Conversione Entity -> DTO per {@link Staff}. Scritta a mano per scelta di
 * progetto (niente MapStruct); il DTO di destinazione e' generato dallo spec.
 *
 * <p>Ha un metodo solo perche' il personale, per ora, compare solo dentro le
 * prenotazioni che ha gestito: non esiste nessun endpoint che restituisca uno
 * {@code Staff} come risorsa a se'. Se un giorno nascera' la gestione degli
 * account del personale — oggi deliberatamente rimandata — sara' questa la
 * classe che cresce.
 */
@Component
public class StaffMapper {

    /**
     * Membro del personale ridotto a quel che serve per sapere chi ha fatto
     * cosa: id, nome e cognome.
     *
     * <p><b>Senza email</b>, al contrario di {@code UtenteMapper.toSintesi}. Non
     * e' una svista ne' una simmetria mancata: l'indirizzo di un cliente serve a
     * chi lo deve contattare, quello di un dipendente non serve a nessuno scopo
     * del client — e un dato personale che non serve e' un dato che non si
     * manda.
     *
     * <p>Accetta null e risponde null, perche' la relazione che lo porta qui e'
     * facoltativa: una prenotazione fatta dal sito non e' stata gestita da
     * nessuno, e quello e' il suo significato — non un dato mancante.
     */
    public StaffSintesi toSintesi(Staff staff) {
        if (staff == null) {
            return null;
        }

        return new StaffSintesi()
                .id(staff.getId())
                .nome(staff.getNome())
                .cognome(staff.getCognome());
    }
}
