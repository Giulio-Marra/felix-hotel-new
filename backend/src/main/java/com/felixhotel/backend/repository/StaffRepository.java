package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    /** Login e caricamento UserDetails avvengono per email. */
    Optional<Staff> findByEmail(String email);

    boolean existsByEmail(String email);
}
