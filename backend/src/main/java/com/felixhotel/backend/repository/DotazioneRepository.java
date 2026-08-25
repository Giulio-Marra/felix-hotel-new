package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Dotazione;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Accesso ai dati delle dotazioni.
 *
 * <p>I due {@code existsBy} hanno lo stesso ruolo che hanno in
 * {@link TipologiaCameraRepository}: rispondere 409 con un messaggio
 * comprensibile prima di arrivare all'indice unico del database. Sono una
 * cortesia verso chi chiama, non la garanzia — quella e' l'indice su
 * {@code lower(nome)}, l'unico che regge anche quando due richieste identiche
 * arrivano nello stesso istante. {@code IgnoreCase} non e' un dettaglio: senza,
 * il controllo qui e il vincolo in database userebbero due regole diverse e un
 * "wi-fi" scritto minuscolo passerebbe di qui per poi schiantarsi la'.
 */
public interface DotazioneRepository extends JpaRepository<Dotazione, Long> {

    /** Usato in creazione: il nome non deve appartenere a nessun'altra dotazione. */
    boolean existsByNomeIgnoreCase(String nome);

    /**
     * Usato in aggiornamento: come sopra, ma escludendo la dotazione che si sta
     * modificando — altrimenti riconfermarle il proprio nome darebbe 409.
     */
    boolean existsByNomeIgnoreCaseAndIdNot(String nome, Long id);
}
