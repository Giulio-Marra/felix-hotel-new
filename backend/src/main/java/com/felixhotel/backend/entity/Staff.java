package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Personale interno (lato backoffice): gestisce camere, prenotazioni,
 * clienti. A differenza di {@link Utente} non ha campi GDPR/consenso (non
 * e' un cliente) e ha un solo {@link Ruolo} alla volta (ADMIN o STAFF, no
 * multi-ruolo). Niente registrazione pubblica: gli account si creano dal
 * backoffice, con POST /api/staff, che e' riservato agli ADMIN.
 *
 * <p>L'email e' unica a meno delle maiuscole: il vincolo lo garantisce un
 * indice su {@code lower(email)} (vedi
 * V6__unicita_email_case_insensitive.sql), non questa classe.
 */
@Entity
@Table(name = "staff")
@Getter
@Setter
@NoArgsConstructor
public class Staff extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, length = 100)
    private String cognome;

    /** Usata anche come credenziale di login. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(length = 30)
    private String telefono;

    @Column(name = "data_assunzione")
    private LocalDate dataAssunzione;

    @Column(nullable = false)
    private boolean attivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ruolo_id", nullable = false)
    private Ruolo ruolo;
}
