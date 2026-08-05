package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Utente;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UtenteRepository extends JpaRepository<Utente, Long> {

    /**
     * Login e caricamento UserDetails avvengono per email.
     *
     * <p>Il {@code ruolo} va caricato nella stessa query: chi chiama
     * (CustomUserDetailsService) legge {@code getRuolo().getNome()} fuori da
     * qualsiasi transazione, e con {@code open-in-view=false} l'entity torna
     * gia' detached — un ManyToOne LAZY non inizializzato darebbe
     * LazyInitializationException.
     */
    @EntityGraph(attributePaths = "ruolo")
    Optional<Utente> findByEmail(String email);

    boolean existsByEmail(String email);
}
