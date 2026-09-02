package com.felixhotel.backend.entity;

import com.felixhotel.backend.entity.enums.OrigineBlocco;
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

import java.time.LocalDate;

/**
 * Una camera che non si puo' vendere in certe notti, pur non essendo prenotata da
 * nessuno.
 *
 * <p><b>Non e' una prenotazione e non deve diventarlo.</b> Una prenotazione ha un
 * cliente, un importo e una macchina a stati; una camera col bagno rotto non ha niente
 * di tutto questo. Rappresentarla come una prenotazione intestata a un cliente inventato
 * vorrebbe dire righe false nella tabella su cui si calcolano fatturato, tassa di
 * soggiorno e schedine alla Questura — cioe' esattamente il difetto che il progetto
 * evita ovunque: <i>un dato inventato e' peggio di un dato mancante</i>.
 *
 * <p><b>Un blocco vale una unita', sempre.</b> E' la regola che tiene insieme l'entita':
 * che si sappia <i>quale</i> camera ({@link #camera} valorizzata, il caso della
 * manutenzione) o solo che <i>una</i> di quella tipologia non e' vendibile
 * ({@code null}, il caso di un canale esterno che ha venduto senza dirci quale), alla
 * disponibilita' toglie uno. Senza questa regola servirebbero due tabelle, oppure una
 * colonna con la quantita' che nessuno saprebbe compilare per una manutenzione.
 *
 * <p><b>Gli estremi si leggono come quelli di una prenotazione</b>: si entra il giorno
 * di inizio e si libera quello di fine. Un blocco dal 3 al 5 rende invendibili le notti
 * del 3 e del 4. La coerenza non e' estetica — e' cio' che permette alla query della
 * disponibilita' di contare blocchi e prenotazioni con le stesse disuguaglianze, senza
 * tradurre niente in mezzo.
 */
@Entity
@Table(name = "blocco_disponibilita")
@Getter
@Setter
@NoArgsConstructor
public class BloccoDisponibilita extends BaseAuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La tipologia a cui questo blocco toglie una unita'.
     *
     * <p><b>Sempre valorizzata, anche quando la camera si sa</b>: e' la tipologia che la
     * disponibilita' conta, e risalirci dalla camera vorrebbe dire una join in piu'
     * nella query piu' calda del progetto.
     *
     * <p>{@code LAZY} come tutte le relazioni (regola 15), e come per gli ospiti il
     * fetch non serve quasi mai: la risposta porta l'id e il nome, non la tipologia
     * intera.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tipologia_camera_id", nullable = false)
    private TipologiaCamera tipologiaCamera;

    /**
     * Quale camera, se lo si sa. {@code null} vuol dire "una qualunque della tipologia".
     *
     * <p>Chi blocca per manutenzione la nomina — e' quella che ha il bagno rotto — e in
     * cambio ottiene l'unica cosa che il nome serve a fare: <b>il check-in non gliela
     * assegna</b>. Un blocco senza camera toglie una unita' alla disponibilita' e basta,
     * il che e' esattamente quel che serve quando un canale esterno ha venduto una
     * doppia senza dirci quale.
     *
     * <p><b>Che sia della tipologia indicata non lo verifica il database</b>: un
     * {@code CHECK} non puo' leggere un'altra tabella. Lo pretende il Service, e la
     * conseguenza e' la stessa dell'accoppiata tipo/numero sul documento di un ospite —
     * una INSERT scritta a mano puo' mettere una camera di un'altra tipologia, e quel
     * blocco toglierebbe l'unita' a quella sbagliata.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id")
    private Camera camera;

    /** Primo giorno non vendibile. */
    @Column(name = "data_inizio", nullable = false)
    private LocalDate dataInizio;

    /**
     * Giorno in cui la camera torna vendibile. <b>Escluso</b>, come la data di partenza
     * di una prenotazione.
     */
    @Column(name = "data_fine", nullable = false)
    private LocalDate dataFine;

    /**
     * Chi ha scritto questa riga. Il perche' sta nelle {@link #note}: questa colonna
     * serve a impedire che la sincronia con un canale porti via i blocchi inseriti a
     * mano — vedi {@link OrigineBlocco}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrigineBlocco origine;

    /**
     * L'identificativo che il calendario esterno da' a quell'occupazione.
     *
     * <p><b>Nessun codice lo legge ancora</b>, e non e' una promessa senza codice nel
     * senso della regola 17: non c'e' nessun contratto e nessun commento che dica cosa
     * fa: e' una colonna che il branch dell'iCal riempira'. Sta qui adesso perche' e'
     * l'unica di questa tabella che quel branch non potrebbe aggiungere senza una
     * migration in piu'.
     */
    @Column(name = "riferimento_esterno", length = 255)
    private String riferimentoEsterno;

    /** Perche' la camera non e' vendibile, in parole. Facoltativo. */
    @Column(length = 500)
    private String note;
}
