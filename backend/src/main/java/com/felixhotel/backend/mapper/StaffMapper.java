package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffResponse;
import com.felixhotel.backend.dto.StaffSintesi;
import com.felixhotel.backend.entity.Staff;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -> DTO per {@link Staff}. Scritta a mano per scelta di
 * progetto (niente MapStruct); i DTO di destinazione sono generati dallo spec.
 *
 * <p>Ha due forme di uscita, e la differenza fra loro e' il punto di questa
 * classe: {@link #toSintesi} e' il personale <i>dentro</i> un'altra risorsa
 * (chi ha gestito questa prenotazione), {@link #toResponse} e' il personale
 * <i>come</i> risorsa (l'account che un ADMIN sta gestendo). Non sono due
 * livelli di dettaglio dello stesso oggetto ma due risposte a due domande, ed
 * e' il motivo per cui l'email c'e' in una sola delle due.
 */
@Component
public class StaffMapper {

    /**
     * Account del personale come risorsa a se': tutto quello che serve a
     * gestirlo.
     *
     * <p><b>Con l'email</b>, al contrario di {@link #toSintesi}, e non e' una
     * contraddizione con quanto scritto la': quella nasconde l'indirizzo di un
     * dipendente a chi guarda una prenotazione, dove non serve a niente. Qui la
     * risorsa <i>e'</i> l'account e l'email ne e' la credenziale di accesso — un
     * ADMIN che gestisce gli account senza vederla non puo' fare il suo lavoro.
     *
     * <p><b>Senza nessuna forma della password</b>, hash compreso: quello non
     * esce da questa applicazione, e non c'e' nessuna domanda del client a cui
     * serva.
     *
     * <p><b>Va chiamato dentro la transazione</b> che ha caricato l'entity: il
     * ruolo e' una relazione LAZY e il progetto ha {@code open-in-view=false}.
     * Le query del repository lo caricano gia' con {@code @EntityGraph}.
     */
    public StaffResponse toResponse(Staff staff) {
        return new StaffResponse()
                .id(staff.getId())
                .nome(staff.getNome())
                .cognome(staff.getCognome())
                .email(staff.getEmail())
                .telefono(staff.getTelefono())
                .dataAssunzione(staff.getDataAssunzione())
                .ruolo(toRuoloDto(staff.getRuolo().getNome()))
                .attivo(staff.isAttivo());
    }

    /** Versione per l'endpoint di lista: stessa conversione, applicata a una pagina di risultati. */
    public List<StaffResponse> toResponseList(List<Staff> personale) {
        return personale.stream().map(this::toResponse).toList();
    }

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

    /**
     * Traduce il ruolo dal nome che sta in tabella al valore dell'enum generato
     * dallo spec.
     *
     * <p><b>Un ruolo fuori dai due previsti fa esplodere la conversione</b>
     * ({@code IllegalArgumentException}), ed e' voluto: una riga di {@code staff}
     * con ruolo USER non e' un caso da mostrare a meta', e' uno stato che nessun
     * endpoint puo' produrre — ci si arriva solo scrivendo a mano nel database.
     * Restituire null o inventare un valore la nasconderebbe dentro una risposta
     * apparentemente normale; un 500 la fa vedere a chi puo' sistemarla.
     */
    private RuoloStaff toRuoloDto(String nomeRuolo) {
        return RuoloStaff.fromValue(nomeRuolo);
    }
}
