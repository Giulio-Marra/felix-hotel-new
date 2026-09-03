package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.EsitoSincronizzazione;
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

import java.time.LocalDateTime;

/**
 * Un calendario iCal altrui che leggiamo per sapere quando un canale ha venduto una
 * nostra camera.
 *
 * <p><b>E' la meta' che mancava al punto 25.</b> Il feed del V16 dice a Booking quando la
 * camera 101 e' occupata da noi; questa dice a noi quando la camera 101 e' occupata da
 * Booking. Senza il ritorno la sincronia funziona in una direzione sola, e l'albergo
 * rivende quel che ha gia' venduto.
 *
 * <p><b>Sta su una camera e non su una tipologia</b>, esattamente come il feed in uscita
 * e per la stessa ragione: iCal non sa esprimere le quantita', quindi ogni calendario —
 * il nostro come quello del canale — corrisponde a <b>una unita' vendibile</b>. Su
 * Booking e su Airbnb l'indirizzo si copia dalla scheda di una camera, e quella scheda e'
 * la camera che gli abbiamo pubblicato noi.
 *
 * <p><b>L'esito e' uno stato, non un registro.</b> Le tre colonne in fondo raccontano
 * l'ultimo giro e vengono sovrascritte dal successivo, e non e' una perdita: un conflitto
 * vive finche' vive la sua causa, quindi se c'e' ancora lo riscrive il giro dopo, e
 * quando qualcuno lo risolve sparisce da solo. Una tabella di storico direbbe le stesse
 * cose in piu' righe e chiederebbe a qualcuno di ripulirla.
 */
@Entity
@Table(name = "sorgente_calendario")
@Getter
@Setter
@NoArgsConstructor
public class SorgenteCalendario extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La camera di cui questo calendario racconta l'occupazione.
     *
     * <p>{@code LAZY} come tutte le relazioni (regola 15). Qui pero' si tocca quasi
     * sempre: la sincronizzazione ha bisogno della camera per nominare i blocchi, e della
     * sua tipologia per contare l'occupazione.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "camera_id", nullable = false)
    private Camera camera;

    /**
     * Come si chiama il canale, per riconoscerlo in un elenco: "Booking", "Airbnb".
     *
     * <p><b>Testo libero e non un elenco chiuso</b>, ed e' la regola 24: quali canali
     * esistano cambia da albergo ad albergo, e nessun codice qui dentro li guarda.
     */
    @Column(nullable = false, length = 100)
    private String nome;

    /**
     * L'indirizzo da cui si scarica il calendario.
     *
     * <p><b>Non e' un segreto nostro ma lo e' di chi ce lo ha dato</b>: chi ha questo URL
     * legge l'occupazione di quella camera su quel canale. Esce comunque per esteso dalle
     * rotte — al contrario del token del V16, che non si rimostra mai — perche' e' un
     * valore che l'ADMIN ha incollato e che deve poter rileggere per correggerlo.
     */
    @Column(nullable = false, length = 500)
    private String url;

    /**
     * Quando e' stato fatto l'ultimo giro. <b>Null vuol dire mai</b>, ed e' l'unico modo
     * di distinguere una sorgente appena registrata da una che funziona.
     */
    @Column(name = "ultima_sincronizzazione")
    private LocalDateTime ultimaSincronizzazione;

    /** Com'e' andata. Null finche' {@link #ultimaSincronizzazione} lo e'. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ultimo_esito", length = 20)
    private EsitoSincronizzazione ultimoEsito;

    /**
     * Cosa e' andato storto, in parole: l'errore di rete, oppure i conflitti trovati.
     * Null quando il giro non ha niente da dire.
     */
    @Column(name = "ultimo_messaggio", length = 1000)
    private String ultimoMessaggio;
}
