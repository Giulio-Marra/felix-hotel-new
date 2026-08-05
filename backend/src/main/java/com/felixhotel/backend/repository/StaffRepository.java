package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Staff;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    /**
     * Login e caricamento UserDetails avvengono per email.
     *
     * <p>Il {@code ruolo} va caricato nella stessa query: vedi la nota su
     * {@link UtenteRepository#findByEmail} — senza, il ManyToOne LAZY
     * esploderebbe fuori transazione.
     */
    @EntityGraph(attributePaths = "ruolo")
    Optional<Staff> findByEmail(String email);

    boolean existsByEmail(String email);
}
