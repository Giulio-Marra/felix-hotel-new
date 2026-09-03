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
 * che si sappia <i>quale</i> camera ({@link #camera} valorizzata) o solo che <i>una</i>
 * di quella tipologia non e' vendibile ({@code null}), alla disponibilita' toglie uno.
 * Senza questa regola servirebbero due tabelle, oppure una colonna con la quantita' che
 * nessuno saprebbe compilare per una manutenzione.
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
     * il che e' quel che serve quando si sa che una doppia non e' vendibile ma non quale.
     *
     * <p><b>I blocchi importati da un canale la nominano</b>, al contrario di quel che il
     * V15 prevedeva: una sorgente di calendario e' agganciata a una camera precisa — la
     * stessa a cui abbiamo pubblicato il feed in uscita — quindi quale sia lo sappiamo.
     * Il caso anonimo resta possibile e oggi lo produce solo chi scrive a mano.
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
     * L'identificativo che il calendario esterno da' a quell'occupazione (l'UID di una
     * riga iCal). Null per i blocchi manuali.
     *
     * <p><b>Serve a leggere il passato, non a decidere il presente.</b> La
     * sincronizzazione non lo confronta con niente: ogni giro cancella i blocchi della
     * propria sorgente e riscrive quel che il calendario dice adesso, che e' piu' semplice
     * e non puo' sbagliarsi. Questo valore resta perche' e' l'unica cosa che permetta di
     * ritrovare, davanti a un blocco che non si spiega, <i>quale</i> riga del calendario
     * del canale lo abbia prodotto.
     */
    @Column(name = "riferimento_esterno", length = 255)
    private String riferimentoEsterno;

    /**
     * Da quale sorgente arriva questo blocco. Null per i manuali.
     *
     * <p><b>E' cio' che permette a un canale di rifare i propri blocchi senza toccare
     * quelli degli altri.</b> Senza, "i propri" si potrebbe dedurre solo dall'origine, e
     * allora il giro di Booking porterebbe via i blocchi di Airbnb sulla stessa camera. Il
     * {@link #riferimentoEsterno} non basta: un UID e' unico dentro un calendario, non fra
     * calendari diversi.
     *
     * <p><b>Le due colonne non possono contraddirsi</b>: il V17 pretende che un blocco
     * abbia una sorgente <i>se e solo se</i> la sua origine e' {@code CANALE_ESTERNO}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sorgente_calendario_id")
    private SorgenteCalendario sorgenteCalendario;

    /** Perche' la camera non e' vendibile, in parole. Facoltativo. */
    @Column(length = 500)
    private String note;
}
