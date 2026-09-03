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
import lombok.AccessLevel;
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
 *
 * <p>L'email e' unica a meno delle maiuscole: il vincolo lo garantisce un
 * indice su {@code lower(email)} (vedi
 * V6__unicita_email_case_insensitive.sql), non questa classe.
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
    @Column(nullable = false, length = 255)
    private String email;

    /**
     * <b>Non ha un setter</b>, ed e' il punto di tutta la revoca: si scrive solo da
     * {@link #impostaPassword}, che aggiorna anche {@link #tokenNonValidiPrimaDi}.
     * Lasciare il setter vorrebbe dire che dimenticare la revoca resta possibile — e le
     * cose possibili, in cinque posti diversi, prima o poi succedono.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * I token emessi prima di questo istante non valgono piu'. {@code null} finche' non e'
     * stato revocato niente, che e' il caso di quasi tutti gli account.
     *
     * <p>Lo controlla {@code JwtAuthenticationFilter} ad ogni richiesta, senza nessuna
     * query in piu': l'account e' gia' caricato li' per verificare che sia attivo.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "token_non_validi_prima_di")
    private LocalDateTime tokenNonValidiPrimaDi;

    /**
     * Scrive la password e, insieme, invalida i token gia' emessi.
     *
     * <p><b>Sono una cosa sola e non due</b>: ogni volta che la password cambia, chi
     * teneva un token in mano non deve poterlo piu' usare. Tenerle separate ha gia'
     * prodotto il debito che questo metodo chiude — cinque posti scrivono una password, e
     * bastava dimenticarsene in uno.
     *
     * @param da l'istante da cui i vecchi token cadono, vedi {@code IstanteRevoca}
     */
    public void impostaPassword(String hash, LocalDateTime da) {
        // **Si revoca solo se una password c'era gia'.** Un account che la riceve per la
        // prima volta — una registrazione, un invito accettato — non ha nessun token in
        // giro da invalidare, e annotare la revoca lo danneggerebbe: chi accede subito
        // dopo si vedrebbe rifiutare il proprio primo token, perche' emesso nello stesso
        // secondo della soglia. Revocare quel che non esiste non e' prudenza in piu', e'
        // un modo di rompere il caso normale.
        if (this.passwordHash != null) {
            this.tokenNonValidiPrimaDi = da;
        }
        this.passwordHash = hash;
    }

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
