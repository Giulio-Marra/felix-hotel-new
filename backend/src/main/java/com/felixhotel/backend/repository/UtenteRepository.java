package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Utente;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UtenteRepository extends JpaRepository<Utente, Long> {

    /**
     * Login e caricamento UserDetails avvengono per email, a meno delle
     * maiuscole: chi si e' registrato come {@code Mario@example.com} deve poter
     * entrare scrivendo {@code mario@example.com}, perche' e' lo stesso
     * indirizzo e nessuno ricorda con che maiuscole l'ha scritto la prima volta.
     *
     * <p>Il {@code ruolo} va caricato nella stessa query: chi chiama
     * (CustomUserDetailsService) legge {@code getRuolo().getNome()} fuori da
     * qualsiasi transazione, e con {@code open-in-view=false} l'entity torna
     * gia' detached — un ManyToOne LAZY non inizializzato darebbe
     * LazyInitializationException.
     */
    @EntityGraph(attributePaths = "ruolo")
    Optional<Utente> findByEmailIgnoreCase(String email);

    /**
     * Usata in registrazione. {@code IgnoreCase} perche' il vincolo in database
     * e' su {@code lower(email)} (vedi
     * V6__unicita_email_case_insensitive.sql): un controllo case-sensitive
     * lascerebbe passare di qui un duplicato che si schianta la', cioe' un 500
     * al posto di un 409.
     */
    boolean existsByEmailIgnoreCase(String email);
}
