package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.TipologiaCamera;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Accesso ai dati delle tipologie di camera.
 *
 * <p>I due {@code existsBy} servono a rispondere 409 con un messaggio
 * comprensibile prima di arrivare all'indice unico del database: sono una
 * cortesia verso chi chiama, non la garanzia — quella e' l'indice su
 * {@code lower(nome)}, l'unico che regge anche quando due richieste identiche
 * arrivano nello stesso istante. {@code IgnoreCase} non e' un dettaglio: senza,
 * il controllo qui e il vincolo in database userebbero due regole diverse e
 * un "doppia" scritto minuscolo passerebbe di qui per poi schiantarsi la'.
 */
public interface TipologiaCameraRepository extends JpaRepository<TipologiaCamera, Long> {

    /**
     * Lettura per id con le dotazioni gia' caricate.
     *
     * <p>Il fetch e' dichiarato sulla query e non sull'entita', come vuole la
     * regola 15: qui serve, sull'elenco paginato no — anzi li' romperebbe la
     * paginazione, che e' il motivo per cui la collezione ha {@code @BatchSize}
     * invece di essere EAGER (vedi {@code TipologiaCamera#dotazioni}).
     *
     * <p>Vale per tutti e tre i metodi che passano di qui — dettaglio,
     * aggiornamento, eliminazione — e in nessuno dei tre e' uno spreco: il
     * dettaglio le mostra, l'aggiornamento le restituisce nella risposta, e
     * l'eliminazione deve comunque staccarle.
     */
    @Override
    @EntityGraph(attributePaths = "dotazioni")
    Optional<TipologiaCamera> findById(Long id);

    /**
     * Lettura per id <b>senza</b> le dotazioni, per chi della tipologia ha
     * bisogno solo come riferimento.
     *
     * <p>Serve a chi deve legare qualcosa a una tipologia — una camera, una foto
     * — e poi non ne mostra ne' il prezzo ne' le dotazioni: quelle scritture
     * passando da {@link #findById(Long)} si tirerebbero dietro un join e una
     * collezione che nessuno legge. E' una query esplicita e non un
     * {@code @EntityGraph} vuoto perche' il fetch dichiarato sul metodo
     * ereditato vale per quel metodo soltanto: una {@code @Query} scritta a mano
     * non lo eredita, ed e' esattamente cio' che qui si vuole.
     *
     * <p><b>Perche' esiste solo adesso.</b> Lo spreco era stato notato il
     * 2026-08-25 su {@code CameraServiceImpl} e lasciato li' di proposito: con
     * un chiamante solo, il rimedio costava piu' codice di quanto risparmiasse.
     * Con la galleria delle foto i chiamanti sono due, ed e' la condizione che
     * era stata scritta per riaprire il punto.
     *
     * <p>Non restituisce un proxy ({@code getReferenceById}): quello eviterebbe
     * anche questa query, ma non direbbe se l'id esiste — e tutti e due i
     * chiamanti devono rispondere qualcosa di preciso quando non esiste.
     */
    @Query("select t from TipologiaCamera t where t.id = :id")
    Optional<TipologiaCamera> trovaSenzaCollezioni(@Param("id") Long id);

    /** Usato in creazione: il nome non deve appartenere a nessun'altra tipologia. */
    boolean existsByNomeIgnoreCase(String nome);

    /**
     * Usato in aggiornamento: come sopra, ma escludendo la tipologia che si sta
     * modificando — altrimenti riconfermarle il proprio nome darebbe 409.
     */
    boolean existsByNomeIgnoreCaseAndIdNot(String nome, Long id);

    /**
     * Prende il lock sulla riga di una tipologia, e non restituisce niente di utile.
     *
     * <p><b>Serializza chi vende la stessa tipologia.</b> Contare le camere e contare le
     * occupate sono due letture, e fra la seconda e la scrittura che ne consegue non c'e'
     * niente che impedisca a un'altra transazione di fare lo stesso conto: due conferme
     * simultanee sull'ultima camera passano tutte e due, e l'albergo ha venduto una stanza
     * che non ha. Prendendo prima questo lock, la seconda transazione aspetta la prima e
     * poi rifa' il conto — trovandolo cambiato.
     *
     * <p><b>Sulla tipologia e non sulle prenotazioni</b>, ed e' la scelta che rende la cosa
     * semplice: la riga da bloccare dev'essere una sola e sempre la stessa per tutti i
     * concorrenti, e la tipologia e' esattamente l'oggetto di cui si contano le unita'.
     * Bloccare le prenotazioni non servirebbe: il problema e' quella che <b>non c'e'
     * ancora</b>.
     *
     * <p><b>Restituisce l'id e non l'entita'</b>, perche' l'entita' non serve a nessuno:
     * chi chiama ce l'ha gia'. Serve solo il {@code SELECT ... FOR UPDATE} sulla riga, e
     * caricare la tipologia intera si tirerebbe dietro le sue collezioni.
     *
     * <p><b>Il lock dura fino alla fine della transazione</b>, come tutti i lock di
     * database: chi chiama non ha niente da rilasciare, ma deve sapere che da qui in poi
     * gli altri aspettano — quindi va preso il piu' tardi possibile, e infatti si prende
     * dentro il controllo di disponibilita' e non all'inizio del metodo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t.id from TipologiaCamera t where t.id = :id")
    Optional<Long> bloccaPerVendita(@Param("id") Long id);
}
