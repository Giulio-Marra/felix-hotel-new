package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.enums.StatoCamera;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Accesso ai dati delle camere fisiche.
 *
 * <p>Ogni lettura che finisce in una risposta carica anche la tipologia con
 * {@code @EntityGraph}: la relazione e' LAZY e il progetto ha
 * {@code open-in-view=false} (regola 15). Qui il fetch e' innocuo anche
 * sull'elenco paginato — e' un {@code ManyToOne}, quindi un join che non
 * moltiplica le righe, al contrario di quel che succederebbe con una collezione.
 */
public interface CameraRepository extends JpaRepository<Camera, Long> {

    /**
     * Elenco paginato con i due filtri facoltativi, combinabili.
     *
     * <p><b>Perche' una query sola con i null e non quattro metodi derivati.</b>
     * Due filtri opzionali fanno quattro combinazioni, e
     * {@code findAll}/{@code findByStato}/{@code findByTipologiaCameraId}/
     * {@code findByTipologiaCameraIdAndStato} vorrebbero dire quattro firme piu'
     * un {@code if} nel Service che sceglie quale chiamare — cioe' il posto dove
     * un domani si dimentica di aggiungere il terzo filtro. Il
     * {@code :parametro is null or ...} tiene la decisione dentro la query, dove
     * la si legge tutta insieme.
     *
     * @param tipologiaCameraId se null, non filtra per tipologia
     * @param stato             se null, non filtra per stato
     */
    @EntityGraph(attributePaths = "tipologiaCamera")
    @Query("""
            select c from Camera c
            where (:tipologiaCameraId is null or c.tipologiaCamera.id = :tipologiaCameraId)
              and (:stato is null or c.stato = :stato)
            """)
    Page<Camera> cerca(@Param("tipologiaCameraId") Long tipologiaCameraId,
                       @Param("stato") StatoCamera stato,
                       Pageable pageable);

    /** Lettura per id con la tipologia gia' caricata: serve a tutti i metodi che passano di qui. */
    @Override
    @EntityGraph(attributePaths = "tipologiaCamera")
    Optional<Camera> findById(Long id);

    /**
     * Usato in creazione: il numero non deve appartenere a nessun'altra camera.
     * {@code IgnoreCase} perche' il vincolo in database e' su {@code lower(numero)},
     * e due regole diverse lascerebbero passare di qui un duplicato che si
     * schianterebbe la'.
     */
    boolean existsByNumeroIgnoreCase(String numero);

    /**
     * Usato in aggiornamento: come sopra, ma escludendo la camera che si sta
     * modificando — altrimenti riconfermarle il proprio numero darebbe 409.
     */
    boolean existsByNumeroIgnoreCaseAndIdNot(String numero, Long id);

    /**
     * Le camere di una tipologia che si possono <b>assegnare adesso</b> per un
     * periodo, in ordine di numero.
     *
     * <p><b>Non e' il calcolo della disponibilita' fatto un'altra volta</b>, ed
     * e' la distinzione da tenere ferma. La disponibilita' risponde a "quante
     * stanze di questo tipo restano": conta tutte le camere qualunque sia il
     * loro stato operativo, e guarda le prenotazioni <b>della tipologia</b>.
     * Questa risponde a "quale chiave dare a chi e' davanti al banco", quindi
     * pretende una stanza materialmente utilizzabile e guarda le prenotazioni
     * <b>di quella stanza</b>. Le due possono dare risposte diverse sullo stesso
     * giorno, e va bene: una prenotazione confermata che arriva con tutte le
     * stanze in manutenzione e' un problema d'albergo, non un errore di calcolo.
     *
     * <p><b>Solo LIBERA</b>, e non e' un elenco di esclusioni scritto qui: e'
     * cio' che {@code StatoCamera} dichiara da sempre — MANUTENZIONE e' fuori
     * servizio, PULIZIA e' "non assegnabile finche' non torna LIBERA", OCCUPATA
     * ha gia' qualcuno dentro. Lo stato arriva come parametro perche' la
     * costante viva in un posto solo, cioe' dove il service la nomina.
     *
     * <p><b>Perche' non basta lo stato operativo, che pure sarebbe esatto.</b> Se
     * OCCUPATA fosse sempre fedele, quel campo da solo risponderebbe: viene messo
     * al check-in e tolto al check-out, e l'assegnazione avviene sempre
     * "adesso". Ma quel campo lo scrive anche una persona, con
     * {@code PUT /api/camere/{id}/stato}, che di proposito non ha nessuna
     * macchina a stati — un tocco sbagliato riporta a LIBERA una stanza con
     * dentro un ospite, e la prossima chiave finirebbe a un secondo cliente.
     * Le prenotazioni in CHECK_IN, invece, le scrive solo l'applicazione: sono la
     * fonte che non si puo' sbagliare a mano.
     *
     * <p><b>Solo CHECK_IN, e non tutti gli stati che occupano.</b> Sono due
     * domande diverse: {@code occupaCamera()} dice se una prenotazione consuma
     * disponibilita' — e li' dentro c'e' anche CHECK_OUT, che e' storia —
     * mentre qui la domanda e' "c'e' qualcuno dentro <i>adesso</i>". Usare
     * l'elenco largo rifiuterebbe una stanza libera davvero: chi parte in
     * anticipo lascia una prenotazione CHECK_OUT che copre ancora le notti
     * successive, e quella stanza va potuta ridare subito.
     *
     * <p><b>La prenotazione che si sta registrando non ha bisogno di essere
     * esclusa</b>, al contrario di quel che serve al calcolo della
     * disponibilita': arriva qui da CONFERMATA, cioe' senza nessuna camera
     * assegnata, quindi non puo' comparire in una condizione che confronta
     * {@code p.camera}.
     *
     * <p>La sovrapposizione usa le solite disuguaglianze strette: chi parte il
     * 13 libera la stanza per chi arriva il 13.
     */
    @EntityGraph(attributePaths = "tipologiaCamera")
    @Query("""
            select c from Camera c
            where c.tipologiaCamera.id = :tipologiaCameraId
              and c.stato = :statoAssegnabile
              and not exists (
                  select p.id from Prenotazione p
                  where p.camera = c
                    and p.stato = :statoOccupante
                    and p.dataCheckIn  <  :dataCheckOut
                    and p.dataCheckOut >  :dataCheckIn
              )
            order by c.numero
            """)
    List<Camera> trovaAssegnabili(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                  @Param("statoAssegnabile") StatoCamera statoAssegnabile,
                                  @Param("statoOccupante") StatoPrenotazione statoOccupante,
                                  @Param("dataCheckIn") LocalDate dataCheckIn,
                                  @Param("dataCheckOut") LocalDate dataCheckOut,
                                  Limit limit);

    /**
     * Quante camere fisiche esistono di una tipologia.
     *
     * <p>E' meta' del calcolo della disponibilita': l'altra e' quante di quelle
     * risultano gia' impegnate nel periodo richiesto, che la sa
     * {@code PrenotazioneRepository.contaSovrapposte}.
     *
     * <p><b>Conta tutte le camere, stato operativo compreso.</b> Una stanza in
     * MANUTENZIONE oggi non dice niente su come sara' fra due mesi, e sottrarla
     * qui vorrebbe dire rifiutare una prenotazione di novembre per un
     * condizionatore rotto ad agosto — vedi {@code StatoCamera}, dove la
     * distinzione fra stato presente e disponibilita' calcolata e' scritta per
     * esteso. Il giorno che servisse tenere fuori una camera da <i>tutte</i> le
     * prenotazioni future servirebbe un'altra cosa: un periodo di
     * indisponibilita', con le sue date.
     */
    long countByTipologiaCameraId(Long tipologiaCameraId);

    /**
     * Lo stesso conteggio per piu' tipologie in un colpo solo.
     *
     * <p>Serve alla ricerca di disponibilita', che ne vuole uno per ogni
     * tipologia della pagina: chiamare il metodo qui sopra in un ciclo sarebbe
     * una query per riga mostrata. Il metodo singolo resta perche' creazione e
     * conferma guardano una tipologia sola, dove non c'e' niente da raggruppare.
     *
     * <p><b>Una tipologia senza camere non compare fra i risultati</b>, perche'
     * il {@code group by} raggruppa righe che non esistono. Chi chiama deve
     * leggerla come zero — che e' anche la verita': una tipologia a catalogo di
     * cui non e' stata ancora creata nessuna stanza non ha niente da vendere.
     */
    @Query("""
            select c.tipologiaCamera.id as tipologiaCameraId, count(c) as totale
            from Camera c
            where c.tipologiaCamera.id in :tipologiaCameraIds
            group by c.tipologiaCamera.id
            """)
    List<ConteggioCamere> contaPerTipologia(
            @Param("tipologiaCameraIds") Collection<Long> tipologiaCameraIds);
}
