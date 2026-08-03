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
import java.time.LocalDateTime;

/**
 * Cliente che prenota (lato frontoffice). Contiene solo dati account
 * (credenziali, contatti, consenso privacy): i dati anagrafici/documento di
 * chi soggiorna effettivamente vivono in {@code Ospite}, per non duplicare
 * informazioni che potrebbero divergere (es. prenotazioni per altre persone).
 */
@Entity
@Table(name = "utente")
@Getter
@Setter
@NoArgsConstructor
public class Utente extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(nullable = false, length = 100)
    private String cognome;

    /** Usata anche come credenziale di login. */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(length = 30)
    private String telefono;

    @Column(name = "data_nascita")
    private LocalDate dataNascita;

    @Column(name = "data_registrazione", nullable = false)
    private LocalDateTime dataRegistrazione;

    @Column(nullable = false)
    private boolean attivo;

    @Column(name = "email_verificata", nullable = false)
    private boolean emailVerificata;

    /** GDPR: consenso al trattamento dati, obbligatorio per registrarsi. */
    @Column(name = "consenso_privacy", nullable = false)
    private boolean consensoPrivacy;

    @Column(name = "data_consenso")
    private LocalDateTime dataConsenso;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ruolo_id", nullable = false)
    private Ruolo ruolo;
}
