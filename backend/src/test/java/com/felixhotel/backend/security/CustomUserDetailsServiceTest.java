package com.felixhotel.backend.security;

import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link CustomUserDetailsService}.
 *
 * <p>La classe ha una decisione sola ma e' quella che rende sensato tutto il
 * resto: <b>da quale delle due tabelle arriva l'account</b>. E' l'unico punto
 * del progetto che lo sa — piu' a valle resta solo il principal — quindi se il
 * {@link TipoAccount} venisse messo storto qui, l'id di uno staff verrebbe
 * usato come se fosse quello di un cliente. Il danno si vedrebbe lontano da
 * qui, in un elenco di prenotazioni che mostra quelle di un altro.
 *
 * <p>Gli IT che fanno login la esercitano gia' di rimbalzo, e continuano a
 * servire: la differenza e' che quelli fallirebbero dicendo "400 invece di
 * 201", cioe' indicando il punto sbagliato. Questi dicono quale delle due
 * assegnazioni e' storta.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomUserDetailsService")
class CustomUserDetailsServiceTest {

    private static final String EMAIL = "mario.rossi@example.com";

    /**
     * Lo stesso id nelle due tabelle, che e' il fatto da cui nasce tutto: sono
     * due sequenze indipendenti, quindi la collisione non e' un caso costruito
     * ad arte ma la normalita'.
     */
    private static final Long ID_CONDIVISO = 3L;

    @Mock
    private UtenteRepository utenteRepository;
    @Mock
    private StaffRepository staffRepository;

    @InjectMocks
    private CustomUserDetailsService userDetailsService;

    @Nested
    @DisplayName("loadUserByUsername")
    class LoadUserByUsername {

        @Test
        @DisplayName("per un cliente marca il principal come CLIENTE")
        void loadUserByUsername_conUtente_marcaComeCliente() {
            // given
            when(utenteRepository.findByEmail(EMAIL)).thenReturn(Optional.of(cliente()));

            // when
            AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername(EMAIL);

            // then: id e tipo si leggono insieme — il numero da solo non direbbe niente
            assertThat(principal.getTipo()).isEqualTo(TipoAccount.CLIENTE);
            assertThat(principal.getUserId()).isEqualTo(ID_CONDIVISO);
            assertThat(principal.getRuoloNome()).isEqualTo("USER");
        }

        @Test
        @DisplayName("per il personale marca il principal come PERSONALE")
        void loadUserByUsername_conStaff_marcaComePersonale() {
            // given: nessun cliente con quell'email, uno staff si'
            when(utenteRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(staffRepository.findByEmail(EMAIL)).thenReturn(Optional.of(personale()));

            // when
            AppUserPrincipal principal = (AppUserPrincipal) userDetailsService.loadUserByUsername(EMAIL);

            // then: stesso id del test accanto, tipo diverso. E' esattamente la coppia
            // che l'id da solo non sapeva distinguere
            assertThat(principal.getTipo()).isEqualTo(TipoAccount.PERSONALE);
            assertThat(principal.getUserId()).isEqualTo(ID_CONDIVISO);
            assertThat(principal.getRuoloNome()).isEqualTo("STAFF");
        }

        @Test
        @DisplayName("con un'email che non esiste in nessuna delle due tabelle solleva UsernameNotFoundException")
        void loadUserByUsername_conEmailSconosciuta_sollevaUsernameNotFound() {
            // given
            when(utenteRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(staffRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            // when/then: il messaggio non riporta l'email, che e' un dato personale e da
            // qui finirebbe nei log ad ogni tentativo con un indirizzo inventato
            assertThatThrownBy(() -> userDetailsService.loadUserByUsername(EMAIL))
                    .isInstanceOf(UsernameNotFoundException.class)
                    .hasMessageNotContaining(EMAIL);
        }
    }

    private Utente cliente() {
        Utente utente = new Utente();
        utente.setId(ID_CONDIVISO);
        utente.setEmail(EMAIL);
        utente.setPasswordHash("hash");
        utente.setNome("Mario");
        utente.setCognome("Rossi");
        utente.setAttivo(true);
        utente.setRuolo(ruolo("USER"));
        return utente;
    }

    private Staff personale() {
        Staff staff = new Staff();
        staff.setId(ID_CONDIVISO);
        staff.setEmail(EMAIL);
        staff.setPasswordHash("hash");
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        staff.setAttivo(true);
        staff.setRuolo(ruolo("STAFF"));
        return staff;
    }

    private Ruolo ruolo(String nome) {
        Ruolo ruolo = new Ruolo();
        ruolo.setNome(nome);
        return ruolo;
    }
}
