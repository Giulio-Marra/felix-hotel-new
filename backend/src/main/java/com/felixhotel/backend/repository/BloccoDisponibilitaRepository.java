package com.felixhotel.backend.repository;

import com.felixhotel.backend.entity.BloccoDisponibilita;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Accesso ai blocchi di disponibilita'.
 *
 * <p><b>Qui non c'e' la query che conta i blocchi</b>, ed e' la cosa da sapere per
 * prima: quella vive in {@code PrenotazioneRepository.occupazioneMassima}, insieme al
 * conteggio delle prenotazioni. Non e' una svista di collocazione — <i>quante camere
 * restano</i> e' una domanda sola, e spezzarla in due query da sommare in Java
 * riaprirebbe la porta a due risposte diverse alla stessa domanda, che e' esattamente il
 * difetto che il calcolo del prezzo ha chiuso il 2026-09-01.
 *
 * <p>Quel che resta qui sono le letture del backoffice — l'elenco di chi amministra — e
 * il controllo che chi scrive un blocco non ne stia scrivendo uno che si sovrappone.
 */
public interface BloccoDisponibilitaRepository extends JpaRepository<BloccoDisponibilita, Long> {

    /**
     * L'elenco per il backoffice, con i tre filtri che servono davvero: la tipologia, la
     * camera e il periodo.
     *
     * <p><b>Il filtro sul periodo e' una sovrapposizione e non un contenimento</b>: chi
     * chiede "cosa e' bloccato a settembre" vuole vedere anche il blocco che comincia il
     * 28 agosto e finisce il 3 settembre. Cercare i blocchi <i>contenuti</i> nel periodo
     * lo nasconderebbe proprio a chi sta cercando di capire perche' non puo' vendere.
     *
     * <p><b>Le due date hanno un flag accanto invece di un {@code is null}</b>, ed e' la
     * cosa piu' strana di questo repository: va spiegata, perche' senza spiegazione il
     * primo che passa la "semplifica" e rimette il 500.
     *
     * <p>Il 2026-09-01 le codifiche avevano avuto un 500 di questa famiglia — {@code
     * function lower(bytea) does not exist} — e il rimedio era stato un
     * {@code cast(:filtro as string)}. Scrivendo questa query l'ho ricopiato come
     * {@code cast(:da as date)}: 500 nuovo, {@code cannot cast type bytea to date}.
     * Tolto il cast: 500 ancora, {@code could not determine data type of parameter $5},
     * ma solo <b>quando un filtro c'e'</b>.
     *
     * <p>La causa, che le prime due diagnosi avevano mancato: Postgres deve dedurre il
     * tipo di ogni parametro, e in {@code :da is null} non c'e' niente da cui dedurlo —
     * <i>qualunque cosa</i> puo' essere null. Quando tutti i parametri sono nulli li
     * tratta come testo e la cosa passa; appena uno viene valorizzato prepara
     * l'istruzione sul serio e si ferma. Il cast del 2026-09-01 non tipizzava niente:
     * funzionava perche' <b>da bytea a testo la conversione esiste</b> e verso una data
     * no.
     *
     * <p><b>La regola che ne esce</b>, e che vale per ogni query futura: un parametro
     * facoltativo deve comparire <b>solo accanto alla sua colonna</b>, che gli da' il
     * tipo. A dire "questo filtro non si applica" ci pensa un booleano, che un tipo ce
     * l'ha sempre.
     *
     * <p>L'ordine e' per data di inizio: un elenco di indisponibilita' si legge come un
     * calendario, non come un archivio.
     */
    @Query("""
            select b from BloccoDisponibilita b
            where (:tipologiaCameraId is null or b.tipologiaCamera.id = :tipologiaCameraId)
              and (:cameraId is null or b.camera.id = :cameraId)
              and (:filtraDa = false or b.dataFine   > :da)
              and (:filtraA  = false or b.dataInizio < :a)
            order by b.dataInizio, b.id
            """)
    Page<BloccoDisponibilita> cerca(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                    @Param("cameraId") Long cameraId,
                                    @Param("filtraDa") boolean filtraDa,
                                    @Param("da") LocalDate da,
                                    @Param("filtraA") boolean filtraA,
                                    @Param("a") LocalDate a,
                                    Pageable pageable);

    /**
     * Se quella camera sia gia' bloccata in quelle notti.
     *
     * <p><b>E' un controllo preventivo, non la difesa</b>: la difesa e' il vincolo di
     * esclusione del V15, che regge anche contro due richieste simultanee. Questo serve a
     * rispondere 409 con un messaggio comprensibile invece di far tradurre al Service una
     * violazione di vincolo — la stessa divisione dei compiti gia' usata per i periodi
     * tariffari e per le aliquote.
     *
     * <p>Le disuguaglianze sono strette da tutte e due le parti: un blocco che finisce il
     * 5 e uno che comincia il 5 non si toccano.
     */
    @Query("""
            select count(b) > 0 from BloccoDisponibilita b
            where b.camera.id = :cameraId
              and b.dataInizio < :dataFine
              and b.dataFine   > :dataInizio
              and (:esclusa is null or b.id <> :esclusa)
            """)
    boolean esisteSovrapposizioneSuCamera(@Param("cameraId") Long cameraId,
                                          @Param("dataInizio") LocalDate dataInizio,
                                          @Param("dataFine") LocalDate dataFine,
                                          @Param("esclusa") Long esclusa);
    /**
     * I blocchi di una tipologia che toccano un orizzonte, per il calendario.
     *
     * <p>Nessun parametro facoltativo qui, quindi nessun booleano di accompagnamento
     * (regola 25): tutti e tre servono sempre.
     */
    @Query("""
            select b from BloccoDisponibilita b
            where b.tipologiaCamera.id = :tipologiaCameraId
              and b.dataInizio < :a
              and b.dataFine   > :da
            """)
    List<BloccoDisponibilita> occupazioniNellOrizzonte(@Param("tipologiaCameraId") Long tipologiaCameraId,
                                                       @Param("da") LocalDate da,
                                                       @Param("a") LocalDate a);

    /**
     * Cancella i blocchi che una sorgente aveva scritto l'ultima volta.
     *
     * <p><b>E' il primo passo di ogni sincronizzazione</b>, e la ragione per cui e' una
     * cancellazione e non un confronto: il calendario di un canale dice quel che vale
     * <i>adesso</i>, e riscriverlo da capo non puo' sbagliarsi, mentre confrontare gli UID
     * uno per uno vorrebbe dire tre casi (nuovo, cambiato, sparito) e la possibilita' di
     * sbagliarne uno. Costa qualche INSERT in piu' ogni quarto d'ora su una tabella di
     * poche righe.
     *
     * <p><b>Filtra per sorgente e non per origine</b>: con Booking e Airbnb sulla stessa
     * camera, un filtro sull'origine farebbe portare via a ognuno i blocchi dell'altro.
     *
     * <p>{@code flushAutomatically} perche' la cancellazione deve arrivare al database
     * <b>prima</b> delle scritture che seguono: il vincolo di esclusione del V15 non e'
     * differito, e un blocco riscritto sulle stesse notti urterebbe quello vecchio che nel
     * frattempo esiste ancora. Niente {@code clearAutomatically}, invece: staccherebbe
     * dalla sessione la sorgente e la camera che il chiamante sta usando, e qui non c'e'
     * niente di caricato che quella cancellazione renda obsoleto.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from BloccoDisponibilita b where b.sorgenteCalendario.id = :sorgenteId")
    int cancellaDellaSorgente(@Param("sorgenteId") Long sorgenteId);

}
