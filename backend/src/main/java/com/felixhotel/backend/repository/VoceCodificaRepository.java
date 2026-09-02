package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.entity.enums.TipoCodifica;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Accesso alle tabelle di codifica ministeriali.
 *
 * <p>Tre operazioni, perche' tre sono i modi in cui questi dati si toccano: si
 * <b>cercano</b> per riempire una tendina, si <b>rileggono per codice</b> quando
 * una schedina va compilata, e si <b>sostituiscono in blocco</b> quando il
 * Ministero pubblica una versione nuova.
 *
 * <p>Le prime due sono tutte e due letture e restano separate per una ragione che
 * si vede dalle firme: una impagina e ordina per nome, perche' la guarda una
 * persona; l'altra prende un pugno di codici e non ordina niente, perche' la guarda
 * l'export. Fonderle vorrebbe dire una query che fa male tutte e due le cose.
 *
 * <p>Quel che <b>non</b> c'e', ed e' voluto, e' un metodo per scrivere una riga
 * sola: la quarta riga della regola 24 dice che questi codici si aggiornano in
 * blocco e non si digitano.
 */
public interface VoceCodificaRepository extends JpaRepository<VoceCodifica, Long> {

    /**
     * Le voci di una famiglia, filtrate per descrizione, in ordine alfabetico.
     *
     * <p><b>Il filtro e' un {@code contains} e non un {@code startsWith}</b>: chi
     * cerca il comune di nascita di un ospite si ricorda un pezzo del nome, non
     * per forza l'inizio — "reggio" deve trovare sia Reggio Emilia sia Reggio
     * Calabria. Il confronto ignora le maiuscole perche' qui a digitare e' una
     * persona, al contrario del codice, che invece le distingue.
     *
     * <p><b>Paginato, e qui il tetto su {@code size} conta piu' che altrove</b>:
     * la famiglia dei comuni ha circa ottomila righe, quindi un endpoint senza
     * limite sarebbe il modo di farsi mandare l'intera tabella con una richiesta
     * sola — il caso che la regola 21 nomina per esteso.
     *
     * <p>L'ordine e' <b>per descrizione</b> e non per codice: e' una tendina, e chi
     * la guarda cerca un nome. L'indice {@code idx_voce_codifica_tipo_descrizione}
     * del V12 serve esattamente questa query, filtro compreso.
     *
     * <p><b>Il {@code cast(:filtro as string)} non e' ornamento: senza, la query
     * fallisce quando il filtro e' null.</b> Il parametro compare due volte — una in
     * {@code is null}, che non dice niente sul tipo, e una dentro {@code concat} —
     * quindi Postgres non ha nessun elemento per inferirlo e lo tratta come
     * {@code bytea}, con un {@code ERROR: function lower(bytea) does not exist} che
     * arriva a chi chiama come un 500.
     *
     * <p><b>Il difetto e' rimasto nascosto ai test</b>, e vale la pena sapere
     * perche': Postgres tiene in cache il piano di un prepared statement sulla
     * connessione, quindi se la prima esecuzione ha un filtro valorizzato il tipo
     * resta fissato e tutte le chiamate successive senza filtro passano. Nei test i
     * casi col filtro girano per primi, a runtime la prima chiamata e' senza — ed e'
     * cosi' che l'ha trovato il punto 2 della checklist della regola 17 e non la
     * suite.
     */
    @Query("""
            select v from VoceCodifica v
            where v.tipo = :tipo
              and (:filtro is null
                   or lower(v.descrizione) like lower(concat('%', cast(:filtro as string), '%')))
            order by v.descrizione
            """)
    Page<VoceCodifica> cerca(@Param("tipo") TipoCodifica tipo,
                             @Param("filtro") String filtro,
                             Pageable pageable);

    /**
     * Le voci di una famiglia fra quelle con questi codici.
     *
     * <p>Serve all'export delle schedine, che di questa tabella fa due domande
     * diverse con una risposta sola: <b>questo codice esiste davvero?</b> — perche'
     * spedire alla Questura un codice che il Ministero non ha pubblicato e' il
     * difetto che la quarta riga della regola 24 esiste per evitare — e <b>qual e'
     * la provincia di questo comune?</b>, che il tracciato chiede in una casella sua
     * e che sta gia' scritta qui.
     *
     * <p><b>Una query per famiglia e non una per codice</b>: un giorno di arrivi
     * nomina lo stesso pugno di comuni decine di volte, e chiederli uno per uno
     * sarebbe una N+1 su un'operazione quotidiana. Chi chiama raccoglie prima tutti i
     * codici che gli servono, poi fa una domanda sola.
     *
     * <p>I codici <b>distinguono le maiuscole</b>, come l'indice unico del V12: sono
     * stringhe emesse da un'autorita', non nomi che una persona scrive a modo suo.
     */
    List<VoceCodifica> findByTipoAndCodiceIn(TipoCodifica tipo, Collection<String> codici);

    /**
     * Cancella tutte le voci di una famiglia.
     *
     * <p>Serve all'import, che <b>sostituisce l'elenco intero</b> invece di
     * fonderlo con quello che c'era: un aggiornamento del Ministero puo' togliere
     * un comune (le fusioni), e una fusione lascerebbe in tabella un codice
     * soppresso che nessuno si accorge di avere. Sostituire e' anche l'unica
     * operazione che rende il risultato indipendente da quante volte la si esegue.
     *
     * <p><b>{@code @Modifying} con una query e non {@code deleteAll}</b>: quello
     * caricherebbe ottomila entita' in memoria per cancellarle una per una. Qui
     * e' una DELETE sola, e le entita' non servono a nessuno.
     *
     * @return quante righe sono state cancellate, che il Service usa per dire nel
     *         messaggio cosa e' stato sostituito
     */
    @Modifying
    @Query("delete from VoceCodifica v where v.tipo = :tipo")
    int cancellaPerTipo(@Param("tipo") TipoCodifica tipo);
}
