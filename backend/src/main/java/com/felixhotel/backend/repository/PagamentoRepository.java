package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Pagamento;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * Accesso ai pagamenti di una prenotazione.
 *
 * <p><b>Due domande in tutto, e sono davvero due.</b> L'elenco serve a chi guarda la
 * scheda della prenotazione; la somma serve a chi deve decidere qualcosa — quanto manca,
 * e se un nuovo incasso stia superando il dovuto. Sarebbe stato possibile tenere solo
 * l'elenco e sommare in Java, ed e' proprio quel che non si fa: la somma la chiede anche
 * il controllo che gira <b>prima</b> di scrivere una riga, dove caricare tutti i
 * pagamenti per addizionarli vorrebbe dire portarsi in memoria delle righe per leggerne
 * un numero solo.
 */
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {

    /**
     * I pagamenti di una prenotazione, dal piu' vecchio al piu' recente.
     *
     * <p><b>L'ordine e' quello dell'incasso e non quello dell'id</b>: e' la sequenza in
     * cui il denaro e' arrivato, che e' come chi riconcilia si aspetta di leggerla. L'id
     * resta come secondo criterio perche' due versamenti possono avere lo stesso istante
     * — due contanti registrati insieme — e senza un secondo criterio l'ordine fra loro
     * cambierebbe da una chiamata all'altra.
     *
     * <p><b>Lo staff si carica nella stessa query</b> (regola 15): il mapper ne legge il
     * nome per ogni riga, e senza il grafo sarebbe una query per pagamento.
     */
    @EntityGraph(attributePaths = "registratoDa")
    List<Pagamento> findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(Long prenotazioneId);

    /**
     * Quanto e' stato incassato in tutto su una prenotazione.
     *
     * <p><b>Il {@code coalesce} non e' una precauzione ma la risposta giusta</b>: su una
     * prenotazione senza pagamenti la somma di zero righe e' {@code null} per SQL, mentre
     * la frase che serve a chi chiama e' "e' stato incassato zero". Lasciare uscire il
     * null vorrebbe dire ripetere lo stesso controllo in ogni chiamante, e dimenticarselo
     * una volta sola.
     */
    @Query("""
            select coalesce(sum(p.importo), 0)
            from Pagamento p
            where p.prenotazione.id = :prenotazioneId
            """)
    BigDecimal sommaIncassata(@Param("prenotazioneId") Long prenotazioneId);
}
