package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.CanalePrenotazione;
import com.felixhotel.backend.entity.enums.StatoCamera;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Il soggiorno che un cliente ha prenotato: chi, che tipo di camera, da quando
 * a quando.
 *
 * <p><b>Si prenota una tipologia, non una stanza.</b> Il cliente sceglie
 * "doppia superior dal 10 al 13", non la 101: quale camera fisica gli tocchera'
 * si decide al check-in, ed e' anche il motivo per cui la disponibilita' e' un
 * conteggio (quante stanze di quel tipo restano) e non la ricerca di una riga
 * libera.
 *
 * <p><b>La camera fisica arriva al check-in</b> e prima e' null, il che rende
 * questa entita' l'unica del progetto in cui un campo nullo racconta a che punto
 * del ciclo di vita si e'. Non e' "non si sa quale stanza": e' "quella decisione
 * non e' ancora stata presa".
 */
@Entity
@Table(name = "prenotazione")
@Getter
@Setter
@NoArgsConstructor
public class Prenotazione extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Cliente intestatario. E' lui che vedra' la prenotazione nel proprio
     * elenco, e l'unico USER a cui e' permesso vederla.
     *
     * <p>Non e' per forza chi ha fatto la richiesta: una prenotazione presa al
     * telefono e' intestata al cliente ma registrata da un membro del personale,
     * che compare in {@link #gestitaDaStaff}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    /**
     * Tipologia prenotata, cioe' cosa il cliente ha comprato.
     *
     * <p>{@code LAZY} come tutte le relazioni del progetto (regola 15): la
     * caricano con {@code @EntityGraph} le query che la mettono in risposta.
     * Come per la camera, l'{@code @EntityGraph} qui e' innocuo anche
     * sull'elenco paginato — e' un {@code ManyToOne}, un join che non moltiplica
     * le righe.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tipologia_camera_id", nullable = false)
    private TipologiaCamera tipologiaCamera;

    /**
     * Camera fisica assegnata, <b>valorizzata al check-in</b> e null fino ad
     * allora.
     *
     * <p><b>Non sostituisce {@link #tipologiaCamera}, le si affianca</b>, e i due
     * campi possono anche non combaciare: il cliente ha comprato una tipologia —
     * ed e' su quella che l'importo e' stato calcolato — mentre chi sta al banco
     * puo' assegnargli a mano una stanza di categoria diversa. Sovrascrivere la
     * tipologia con quella della camera assegnata farebbe sparire cos'era stato
     * venduto, e l'importo resterebbe accanto a un prodotto che non lo spiega
     * piu'.
     *
     * <p><b>Resta scritta anche dopo il check-out</b>: in quella stanza ci ha
     * dormito qualcuno, ed e' un fatto accaduto. Azzerarla renderebbe impossibile
     * rispondere a "chi c'era nella 101 a settembre", che e' una domanda che un
     * albergo si fa davvero.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id")
    private Camera camera;

    /** Giorno di arrivo. */
    @Column(name = "data_check_in", nullable = false)
    private LocalDate dataCheckIn;

    /**
     * Giorno di partenza, sempre successivo all'arrivo (CHECK
     * {@code chk_prenotazione_date}).
     *
     * <p><b>Il giorno di partenza non e' occupato</b>: chi parte il 13 libera la
     * camera per chi arriva il 13. E' la ragione per cui la sovrapposizione fra
     * due periodi si calcola con disuguaglianze strette — vedi la query di
     * disponibilita' nel repository.
     */
    @Column(name = "data_check_out", nullable = false)
    private LocalDate dataCheckOut;

    /** Quante persone alloggeranno. Non puo' superare la capienza della tipologia: lo verifica il service. */
    @Column(name = "numero_ospiti", nullable = false)
    private Integer numeroOspiti;

    /**
     * Punto del ciclo di vita. Vedi {@link StatoPrenotazione}, e in particolare
     * {@code occupaCamera()} per la differenza che conta davvero.
     *
     * <p>{@code EnumType.STRING} e non ORDINAL, come per {@link StatoCamera}: la
     * colonna e' un VARCHAR con un CHECK sui nomi, e salvare l'ordinale vorrebbe
     * dire che riordinare le costanti riscrive in silenzio lo stato di tutte le
     * prenotazioni gia' a database.
     *
     * <p>L'inizializzatore fa nascere valida anche una {@code new Prenotazione()}
     * costruita da qualcuno che si dimentichi di impostarlo: il DEFAULT della
     * colonna non scatta mai, perche' vale per gli INSERT che non nominano la
     * colonna e Hibernate la nomina sempre.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatoPrenotazione stato = StatoPrenotazione.IN_ATTESA;

    /** Da dove e' arrivata. Vedi {@link CanalePrenotazione}: lo decide il ruolo di chi prenota, non il cliente. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CanalePrenotazione canale;

    /**
     * Membro del personale che ha registrato la prenotazione, se non l'ha fatta
     * il cliente da solo.
     *
     * <p>Null per tutto cio' che arriva dal sito, ed e' la lettura giusta del
     * campo: non "non si sa chi", ma "non l'ha gestita nessuno".
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gestita_da_staff_id")
    private Staff gestitaDaStaff;

    /**
     * Totale del soggiorno: la somma delle notti, una per una.
     *
     * <p><b>Non e' piu' una moltiplicazione dal 2026-09-01</b>, ed e' la sola
     * cosa che le tariffe per periodo hanno cambiato qui: ogni notte vale il
     * prezzo del suo giorno della settimana se il periodo che la copre ne
     * dichiara uno, altrimenti il prezzo base di quel periodo, altrimenti il
     * listino della tipologia. Due notti dello stesso soggiorno possono percio'
     * costare diversamente. La somma la fa la query di
     * {@code PeriodoTariffarioRepository.preventivi}, che e' l'unico posto del
     * progetto in cui il prezzo si calcola.
     *
     * <p><b>E' una fotografia presa alla creazione, non un calcolo rifatto ad
     * ogni lettura.</b> Se domani il listino cambia, questa prenotazione
     * continua a valere quel che valeva quando e' stata fatta — che e' anche
     * l'unico comportamento difendibile davanti a un cliente che ha gia'
     * ricevuto una conferma. E' anche il motivo per cui il campo sta qui e non
     * si ricava dalla tipologia al volo.
     */
    @Column(name = "importo_totale", nullable = false, precision = 10, scale = 2)
    private BigDecimal importoTotale;

    /**
     * Perche' e' stata annullata, se chi l'ha annullata l'ha detto.
     *
     * <p>Facoltativo per scelta: obbligare a scrivere una ragione produrrebbe
     * soltanto stringhe come "annullata".
     */
    @Column(name = "motivo_cancellazione", length = 255)
    private String motivoCancellazione;

    /**
     * Quando e' stata annullata. Valorizzato insieme allo stato ANNULLATA e solo
     * allora: e' il campo che distingue "annullata" da "annullata quando".
     */
    @Column(name = "data_cancellazione")
    private LocalDateTime dataCancellazione;

    /**
     * Richieste o annotazioni libere del cliente. Non e' un campo che
     * l'applicazione interpreta: e' un promemoria per chi accoglie.
     */
    @Column(length = 1000)
    private String note;
}
