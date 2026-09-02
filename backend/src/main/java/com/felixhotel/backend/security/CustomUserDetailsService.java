package com.felixhotel.backend.security;

import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Carica l'account autenticabile a partire dall'email, cercando prima tra i
 * clienti ({@code Utente}) e poi tra il personale ({@code Staff}): sono
 * tabelle separate ma condividono lo stesso meccanismo di login, quindi
 * l'email deve essere univoca nell'insieme delle due popolazioni per
 * evitare ambiguita' in fase di autenticazione.
 *
 * <p><b>La ricerca ignora le maiuscole</b>, come gli indici che garantiscono
 * quell'unicita' (vedi V6__unicita_email_case_insensitive.sql). Cercare per
 * valore esatto vorrebbe dire che chi si e' registrato come
 * {@code Mario@example.com} non entra scrivendo {@code mario@example.com} — lo
 * stesso indirizzo per chi lo scrive, due valori diversi per un confronto
 * letterale.
 *
 * <p>E' anche <b>l'unico punto del progetto che sa da quale delle due tabelle
 * l'account e' stato letto</b>, ed e' il motivo per cui il {@link TipoAccount}
 * si valorizza qui: e' un'informazione che esiste solo dentro questo metodo, e
 * se non la si mette nel principal e' persa per sempre.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return utenteRepository.findByEmailIgnoreCase(email)
                .map(this::toPrincipal)
                .or(() -> staffRepository.findByEmailIgnoreCase(email).map(this::toPrincipal))
                // Il messaggio non riporta l'email: e' un dato personale, e da qui finirebbe
                // nei log ad ogni tentativo di accesso con un indirizzo inesistente — cioe'
                // proprio durante un attacco, che riempirebbe i log di indirizzi altrui.
                // Chi indaga sul serio ha comunque l'email nella richiesta.
                .orElseThrow(() -> new UsernameNotFoundException("Nessun account trovato per l'email indicata"));
    }

    /**
     * <b>Un cliente non verificato non e' abilitato</b>, dal 2026-09-02. La condizione
     * si somma a {@code attivo} invece di sostituirlo: sono due cose diverse — uno e'
     * un account chiuso da chi amministra, l'altro un account che non ha ancora provato
     * di appartenere a chi dice.
     *
     * <p><b>Passa da {@code isEnabled()} e non da un controllo dentro il login</b>, ed
     * e' la ragione per cui costa cosi' poco: questo metodo lo rilegge il filtro JWT ad
     * <i>ogni</i> richiesta, quindi la condizione non vale solo al momento di entrare —
     * e' la stessa proprieta' che dal 2026-08-27 fa cadere il token di uno staff
     * disattivato mentre lo sta usando.
     */
    private AppUserPrincipal toPrincipal(Utente utente) {
        return new AppUserPrincipal(TipoAccount.CLIENTE, utente.getId(), utente.getEmail(),
                utente.getPasswordHash(), utente.getNome(), utente.getCognome(),
                utente.getRuolo().getNome(), utente.isAttivo() && utente.isEmailVerificata());
    }

    /**
     * <b>Un invito non ancora accettato non e' abilitato</b>, dal 2026-09-02: dal V14 un
     * account del personale puo' esistere senza password, ed e' il caso normale fra la
     * creazione e il momento in cui la persona sceglie la sua.
     *
     * <p>Il controllo e' esplicito e non lasciato al confronto delle password, benche'
     * BCrypt su un hash nullo non possa combaciare con niente: senza, il valore nullo
     * arriverebbe dentro l'encoder, che e' un posto in cui nessuno vuole scoprire come
     * si comporta. Meglio dire qui che quell'account non e' utilizzabile.
     */
    private AppUserPrincipal toPrincipal(Staff staff) {
        return new AppUserPrincipal(TipoAccount.PERSONALE, staff.getId(), staff.getEmail(),
                // La password nulla diventa una stringa vuota per non far uscire un null
                // dal principal: nessun hash BCrypt e' la stringa vuota, quindi non
                // combacia con niente, e l'account e' comunque gia' non abilitato.
                staff.getPasswordHash() == null ? "" : staff.getPasswordHash(),
                staff.getNome(), staff.getCognome(),
                staff.getRuolo().getNome(), staff.isAttivo() && staff.getPasswordHash() != null);
    }
}
