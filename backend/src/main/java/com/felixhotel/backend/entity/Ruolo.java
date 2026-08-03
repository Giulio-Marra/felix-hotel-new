package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ruolo applicativo (ADMIN, STAFF, USER). Tabella unica in DB, referenziata
 * sia da {@link Utente} che da {@link Staff} (due FK indipendenti verso
 * questa stessa tabella). Autorizzazione basata solo sul nome del ruolo
 * (Spring Security {@code hasRole(...)}): deliberatamente niente permessi
 * granulari.
 */
@Entity
@Table(name = "ruolo")
@Getter
@Setter
@NoArgsConstructor
public class Ruolo extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String nome;

    @Column(length = 255)
    private String descrizione;
}
