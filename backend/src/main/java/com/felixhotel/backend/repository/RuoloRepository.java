package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Ruolo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RuoloRepository extends JpaRepository<Ruolo, Long> {

    /** Usata in fase di registrazione per assegnare il ruolo USER di default. */
    Optional<Ruolo> findByNome(String nome);
}
