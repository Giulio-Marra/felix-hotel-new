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
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return utenteRepository.findByEmail(email)
                .map(this::toPrincipal)
                .or(() -> staffRepository.findByEmail(email).map(this::toPrincipal))
                .orElseThrow(() -> new UsernameNotFoundException("Nessun account trovato per l'email: " + email));
    }

    private AppUserPrincipal toPrincipal(Utente utente) {
        return new AppUserPrincipal(utente.getId(), utente.getEmail(), utente.getPasswordHash(),
                utente.getNome(), utente.getCognome(), utente.getRuolo().getNome(), utente.isAttivo());
    }

    private AppUserPrincipal toPrincipal(Staff staff) {
        return new AppUserPrincipal(staff.getId(), staff.getEmail(), staff.getPasswordHash(),
                staff.getNome(), staff.getCognome(), staff.getRuolo().getNome(), staff.isAttivo());
    }
}
