package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Staff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Accesso ai dati del personale.
 *
 * <p>Ogni lettura che esce da qui porta con se' il {@code ruolo}: la relazione
 * e' LAZY e il progetto ha {@code open-in-view=false} (regola 15), quindi
 * leggerne il nome fuori dalla transazione darebbe
 * LazyInitializationException. E' un {@code ManyToOne}, quindi anche
 * sull'elenco paginato il fetch e' un join che non moltiplica le righe.
 *
 * <p><b>Tutti i confronti sull'email ignorano le maiuscole</b>, perche' il
 * vincolo in database e' su {@code lower(email)} (vedi
 * V6__unicita_email_case_insensitive.sql). Due regole diverse — il codice
 * case-sensitive e l'indice no — vorrebbero dire lasciar passare di qui un
 * duplicato che poi si schianta la', cioe' un 500 al posto di un 409.
 */
public interface StaffRepository extends JpaRepository<Staff, Long> {

    /**
     * Login e caricamento UserDetails avvengono per email, a meno delle
     * maiuscole: un account registrato come {@code Anna@felixhotel.it} deve
     * essere raggiungibile scrivendo {@code anna@felixhotel.it}, perche' e' lo
     * stesso indirizzo — e qui a sceglierlo e' stato un amministratore, quindi
     * chi lo digita al login non e' nemmeno chi l'ha scritto la prima volta.
     */
    @EntityGraph(attributePaths = "ruolo")
    Optional<Staff> findByEmailIgnoreCase(String email);

    /** Lettura per id col ruolo gia' caricato: e' il preambolo di ogni scrittura. */
    @Override
    @EntityGraph(attributePaths = "ruolo")
    Optional<Staff> findById(Long id);

    /**
     * Elenco paginato con i due filtri facoltativi, combinabili.
     *
     * <p>Stessa forma di {@code CameraRepository.cerca} e per la stessa ragione:
     * due filtri opzionali sono quattro combinazioni, e tenerle in quattro
     * metodi derivati vorrebbe dire un {@code if} nel Service — cioe' il posto
     * dove un domani si dimentica di aggiungere il terzo filtro.
     *
     * <p>Il ruolo si filtra <b>per nome</b> e non per id: l'id di una riga di
     * {@code ruolo} non lo conosce nessuno fuori dal database, mentre il nome e'
     * quello che arriva dal contratto.
     *
     * @param ruoloNome se null, non filtra per ruolo
     * @param attivo    se null, non filtra per attivazione — ed e' il caso
     *                  normale: l'elenco del personale mostra anche chi non
     *                  lavora piu' qui
     *
     * <p><b>I flag accanto ai filtri seguono la regola 25</b>, dal 2026-09-03: un
     * parametro facoltativo compare <b>solo accanto alla sua colonna</b>, che gli da' il
     * tipo, e a dire "questo filtro non si applica" ci pensa un booleano, che un tipo ce
     * l'ha sempre. In {@code :filtro is null} non c'e' niente da cui Postgres possa
     * dedurre un tipo — <i>qualunque cosa</i> puo' essere null — e quando non lo deduce
     * appoggia al testo. Qui funzionava proprio per quello, e la forma e' stata comunque
     * uniformata: la scappatoia del testo non esiste per le <b>date</b>, quindi il primo
     * filtro di quel tipo aggiunto a questa query avrebbe dato un 500 che nei test non si
     * vede (vedi il javadoc di {@code BloccoDisponibilitaRepository.cerca}, dove c'e'
     * l'intera diagnosi).
     */
    @EntityGraph(attributePaths = "ruolo")
    @Query("""
            select s from Staff s
            where (:filtraRuolo  = false or s.ruolo.nome = :ruoloNome)
              and (:filtraAttivo = false or s.attivo     = :attivo)
            """)
    Page<Staff> cerca(@Param("filtraRuolo") boolean filtraRuolo,
                      @Param("ruoloNome") String ruoloNome,
                      @Param("filtraAttivo") boolean filtraAttivo,
                      @Param("attivo") Boolean attivo,
                      Pageable pageable);

    /** La forma comoda per chi chiama, vedi il gemello in {@code PrenotazioneRepository}. */
    default Page<Staff> cerca(String ruoloNome, Boolean attivo, Pageable pageable) {
        return cerca(ruoloNome != null, ruoloNome, attivo != null, attivo, pageable);
    }

    /** Usato in creazione: l'email non deve appartenere a nessun altro membro del personale. */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Usato in aggiornamento: come sopra, ma escludendo l'account che si sta
     * modificando — altrimenti riconfermargli la propria email darebbe 409.
     */
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /**
     * Quanti amministratori attivi resterebbero se quello indicato smettesse di
     * esserlo.
     *
     * <p>E' la domanda che protegge l'unica operazione irreversibile di questa
     * risorsa: togliere il ruolo ADMIN all'ultimo che ce l'ha, o disattivarlo,
     * lascia l'albergo senza nessuno che possa gestire gli account — e da li' si
     * torna indietro solo scrivendo nel database, cioe' proprio la cosa che
     * questi endpoint esistono per non dover piu' fare.
     *
     * <p>L'esclusione per id serve perche' la domanda si fa <b>prima</b> di
     * scrivere: l'account che si sta per degradare o disattivare e' ancora un
     * ADMIN attivo in database, e contarlo vorrebbe dire non accorgersi mai che
     * era l'ultimo.
     */
    long countByRuoloNomeAndAttivoTrueAndIdNot(String ruoloNome, Long id);
}
