package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.TipoDocumento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Accesso ai dati degli ospiti registrati su una prenotazione.
 *
 * <p><b>Nessun {@code @EntityGraph}</b>, come per le foto delle tipologie e per
 * la stessa ragione: la risposta non contiene la prenotazione — chi chiama l'ha
 * appena scritta nell'URL — quindi la relazione LAZY di
 * {@link Ospite#getPrenotazione()} non viene mai letta e non c'e' niente da
 * precaricare. La regola 15 dice di caricare cio' che la risposta usera': qui la
 * risposta non usa niente.
 *
 * <p>Ogni metodo prende l'id della prenotazione, e non e' una comodita' del
 * chiamante: un ospite si indirizza sempre dentro il suo soggiorno, quindi
 * <b>non esiste</b> una lettura per solo id. Averla vorrebbe dire poter
 * cancellare l'ospite di una prenotazione sbagliando l'URL di un'altra — e qui
 * la riga cancellata sarebbe un documento d'identita' in un registro di legge.
 */
public interface OspiteRepository extends JpaRepository<Ospite, Long> {

    /**
     * Gli ospiti di una prenotazione, nell'ordine in cui sono stati registrati.
     *
     * <p>L'ordine e' l'id crescente, cioe' quello di inserimento, e non un
     * criterio ricavato dai dati. L'alfabetico per cognome sembrerebbe piu'
     * ordinato e sarebbe peggiore: farebbe saltare le righe di posto ogni volta
     * che se ne aggiunge una, mentre chi sta registrando una famiglia al banco
     * si aspetta di ritrovare la lista com'e' cresciuta sotto le sue mani.
     *
     * <p>Un criterio esplicito ci vuole comunque: senza {@code order by} il
     * database e' libero di restituire le righe in ordine diverso a ogni query,
     * e una lista che si rimescola da sola a ogni ricarica e' proprio quel che
     * fa dubitare di aver registrato tutti.
     */
    List<Ospite> findByPrenotazioneIdOrderByIdAsc(Long prenotazioneId);

    /**
     * Un singolo ospite, ma solo se appartiene alla prenotazione indicata.
     *
     * <p>Il secondo parametro e' <b>la</b> regola di questa sottorisorsa
     * espressa dove non si puo' dimenticare di applicarla — stessa scelta gia'
     * fatta su {@code MediaCameraRepository}. Con un {@code findById} normale il
     * controllo "e' davvero di questa prenotazione?" resterebbe un {@code if}
     * nel Service, cioe' una riga che qualcuno un domani puo' non scrivere.
     */
    Optional<Ospite> findByIdAndPrenotazioneId(Long id, Long prenotazioneId);

    /**
     * Gli ospiti di piu' prenotazioni insieme, per l'export delle schedine.
     *
     * <p><b>Esiste per non fare N+1</b>, ed e' l'unica ragione per cui non si riusa
     * {@code findByPrenotazioneIdOrderByIdAsc} in un ciclo: gli arrivi di una
     * giornata piena sono decine di prenotazioni, e una query per ognuna
     * moltiplicherebbe per venti il costo di un'operazione che si fa ogni mattina.
     *
     * <p>L'ordine e' <b>prima per prenotazione e poi per registrazione</b>, e non e'
     * indifferente: sul file le persone di uno stesso gruppo devono stare vicine, e
     * dentro il gruppo l'ordine e' quello in cui sono state registrate — cioe' lo
     * stesso che {@code GET .../ospiti} mostra a chi sta al banco. Un file che
     * mescolasse i gruppi sarebbe accettato lo stesso dal portale, ma nessuno
     * potrebbe piu' rileggerlo accanto al registro.
     *
     * <p>Con una collezione vuota restituisce una lista vuota senza toccare il
     * database: e' Spring Data a saperlo, e vale la pena dirlo perche' e'
     * esattamente il caso di un giorno senza arrivi.
     */
    List<Ospite> findByPrenotazioneIdInOrderByPrenotazioneIdAscIdAsc(Collection<Long> prenotazioneIds);

    /**
     * Quanti ospiti sono gia' registrati: serve a far rispettare il tetto di
     * {@code numeroOspiti} in aggiunta, e la condizione del check-in.
     *
     * <p><b>E' anche la meta' che rende il check-in una regola e non una buona
     * intenzione</b>: la' non si conta per limitare ma per pretendere, e le due
     * domande — "sono troppi?" e "sono tutti?" — si fanno con lo stesso numero
     * perche' il tetto e il pavimento sono lo stesso valore.
     */
    long countByPrenotazioneId(Long prenotazioneId);

    /**
     * Se questo documento e' gia' registrato su questa prenotazione.
     *
     * <p>Il confronto e' esatto — niente {@code IgnoreCase} — perche' l'indice
     * in database e' su {@code (prenotazione_id, tipo_documento,
     * numero_documento)} senza {@code lower()}, e due regole diverse
     * lascerebbero passare di qui un duplicato che si schianterebbe la' (vedi
     * V7).
     *
     * <p>Sul tipo <b>oltre</b> che sul numero, come l'indice: due documenti di
     * natura diversa possono portare lo stesso numero, e trattarli come
     * duplicati rifiuterebbe una registrazione legittima.
     */
    boolean existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumento(
            Long prenotazioneId, TipoDocumento tipoDocumento, String numeroDocumento);

    /**
     * Come sopra, ma ignorando un ospite: serve alla correzione, dove il
     * documento gia' presente <b>su se' stessi</b> non e' un conflitto.
     *
     * <p>Senza questo, correggere il solo nome di un ospite sarebbe impossibile:
     * la richiesta rimanda lo stesso numero di documento, che il controllo
     * troverebbe gia' presente — su quella stessa riga — e rifiuterebbe con un
     * 409 che non ha nessun senso per chi l'ha ricevuto.
     */
    boolean existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumentoAndIdNot(
            Long prenotazioneId, TipoDocumento tipoDocumento, String numeroDocumento, Long id);
}
