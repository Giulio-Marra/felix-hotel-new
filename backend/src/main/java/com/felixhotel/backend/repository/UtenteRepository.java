package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Utente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UtenteRepository extends JpaRepository<Utente, Long> {

    /** Login e caricamento UserDetails avvengono per email. */
    Optional<Utente> findByEmail(String email);

    boolean existsByEmail(String email);
}
