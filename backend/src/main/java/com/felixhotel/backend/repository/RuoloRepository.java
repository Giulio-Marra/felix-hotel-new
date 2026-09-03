package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Ruolo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RuoloRepository extends JpaRepository<Ruolo, Long> {

    /** Usata in fase di registrazione per assegnare il ruolo USER di default. */
    Optional<Ruolo> findByNome(String nome);

    /**
     * Prende il lock sulla riga di un ruolo, e non restituisce niente di utile.
     *
     * <p><b>Serializza chi conta gli amministratori attivi.</b> "Non si tocca l'ultimo
     * ADMIN" e' una regola che si verifica contando gli altri, e fra il conteggio e la
     * scrittura che ne consegue non c'e' niente: due disattivazioni simultanee su due
     * ADMIN vedono ognuna <i>un altro</i> amministratore e passano tutte e due, lasciando
     * il backoffice senza nessuno che possa entrarci — cioe' esattamente il danno che
     * quella regola esiste per impedire.
     *
     * <p><b>Sulla riga del ruolo, che e' l'unica cosa condivisa.</b> Gli account coinvolti
     * sono due diversi, quindi bloccare l'account non farebbe incontrare i due
     * concorrenti; il ruolo ADMIN invece e' uno solo, ed e' il punto in cui si passa
     * comunque per fare quella domanda.
     *
     * <p>Restituisce l'id per la stessa ragione dei suoi gemelli sulle camere: serve il
     * {@code SELECT ... FOR UPDATE}, non l'entita'.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r.id from Ruolo r where r.nome = :nome")
    Optional<Long> bloccaPerConteggio(@Param("nome") String nome);
}
