package com.felixhotel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * Anagrafica dell'unica struttura gestita da questa installazione: come si
 * chiama, dove sta, come la si contatta, e con che codici si presenta alle
 * amministrazioni.
 *
 * <p><b>E' una riga sola, e non e' una convenzione</b>: il vincolo
 * {@code CHECK (id = 1)} sta in database (vedi
 * V8__identita_struttura.sql) insieme alla riga stessa, seminata dalla
 * migration. Il progetto e' single-hotel, quindi questa tabella non e' una
 * collezione ma l'anagrafica di quel singolo albergo — e infatti nessuna rotta
 * la indirizza per id: {@code /api/impostazioni} identifica gia' l'unica che
 * esiste.
 *
 * <p><b>Nessun {@code @GeneratedValue}</b>, al contrario di ogni altra entita'.
 * La colonna e' una {@code BIGSERIAL} e la sequenza esiste ancora, ma qui non
 * serve a niente: la riga non nasce da questo codice, che sa solo leggerla e
 * riscriverla. Dichiarare una strategia di generazione direbbe che
 * l'applicazione puo' crearne di nuove, che e' precisamente cio' che il CHECK
 * vieta.
 *
 * <p><b>I campi si dividono in due gruppi, e la divisione conta</b>: i primi sei
 * sono quelli che il sito pubblica di sua volonta' — nome, indirizzo, recapiti e
 * orari — e li legge chiunque; gli altri sette sono l'identita' fiscale e i
 * codici degli adempimenti, che escono solo dall'endpoint riservato agli ADMIN.
 * A tenerli separati non e' un filtro ma due DTO diversi (vedi
 * {@code ImpostazioniHotelMapper}): un campo aggiunto qui non diventa pubblico
 * finche' qualcuno non lo scrive anche nel DTO pubblico.
 *
 * <p><b>Gli orari sono informazione, non regola.</b> Nessun endpoint li applica:
 * il check-in registra l'arrivo a qualunque ora del giorno. Sono il dato che si
 * scrive sul sito e che sta al banco della reception. Scritto qui perche' un
 * campo chiamato {@code orarioCheckInDefault} suggerisce un vincolo che non
 * c'e', e una promessa senza il codice che la mantiene e' esattamente cio' che
 * la regola 17 vieta.
 */
@Entity
@Table(name = "impostazioni_hotel")
@Getter
@Setter
@NoArgsConstructor
public class ImpostazioniHotel extends BaseAuditableEntity {

    /**
     * L'id dell'unica riga. E' una costante e non un parametro: chi legge le
     * impostazioni non sceglie quali, ce n'e' una sola.
     */
    public static final long ID_RIGA_UNICA = 1L;

    @Id
    private Long id;

    // --- Quello che la struttura pubblica di sua volonta' ---

    /** Nome commerciale, quello che il sito mostra. */
    @Column(nullable = false, length = 200)
    private String nome;

    @Column(length = 300)
    private String indirizzo;

    @Column(length = 30)
    private String telefono;

    /**
     * Indirizzo di contatto pubblico. <b>Non e' una credenziale</b>: non ha
     * niente a che vedere con le email degli account, non e' unica e nessun
     * login la cerca.
     */
    @Column(length = 255)
    private String email;

    /** Ora da cui si consegnano le camere. Informativa, vedi la nota in testa. */
    @Column(name = "orario_check_in_default", nullable = false)
    private LocalTime orarioCheckInDefault;

    /** Ora entro cui la camera va liberata. Informativa come la precedente. */
    @Column(name = "orario_check_out_default", nullable = false)
    private LocalTime orarioCheckOutDefault;

    // --- Identita' fiscale e codici degli adempimenti (solo ADMIN) ---

    /**
     * Denominazione legale di chi gestisce la struttura, che spesso non coincide
     * col nome commerciale ("Felix Hotel" contro "Felix Hotel S.r.l.").
     */
    @Column(name = "ragione_sociale", length = 200)
    private String ragioneSociale;

    @Column(name = "partita_iva", length = 20)
    private String partitaIva;

    /**
     * Codice fiscale del titolare o della societa'. Per una societa' coincide di
     * norma con la partita IVA, ma non sempre: sono due campi perche' sono due
     * dati.
     */
    @Column(name = "codice_fiscale", length = 16)
    private String codiceFiscale;

    /** Codice Identificativo Nazionale della struttura ricettiva. */
    @Column(length = 30)
    private String cin;

    @Column(length = 100)
    private String comune;

    /**
     * Codice ISTAT del comune. E' la chiave con cui il comune viene identificato
     * nelle rilevazioni statistiche e nel calcolo della tassa di soggiorno, che
     * cambia da comune a comune.
     */
    @Column(name = "codice_istat_comune", length = 6)
    private String codiceIstatComune;

    /**
     * Identificativo assegnato dalla Questura per l'invio delle schedine ad
     * Alloggiati Web.
     */
    @Column(name = "codice_struttura_alloggiati", length = 20)
    private String codiceStrutturaAlloggiati;
}
