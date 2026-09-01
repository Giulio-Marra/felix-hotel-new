package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.AliquotaTassaSoggiornoRequest;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;

/**
 * Il calendario della tassa di soggiorno: in che date si paga quanto, fino a
 * quante notti, e sotto che eta' non si paga.
 *
 * <p><b>Non e' una sottorisorsa</b>, al contrario dei periodi tariffari: nessun
 * metodo prende l'id di qualcos'altro, perche' l'aliquota e' dell'albergo. Il
 * prezzo di una camera lo decide chi vende e appartiene alla tipologia; l'aliquota
 * la decide il comune e vale per chiunque dorma li'.
 *
 * <p><b>Il livello di permesso e' quello delle tariffe</b>: leggere e' di STAFF o
 * ADMIN, scrivere solo di ADMIN. Chi sta al banco deve poter rispondere a
 * "quant'e' la tassa di soggiorno?" senza chiamare nessuno; trascrivere il
 * regolamento comunale e' un'altra cosa. Il ruolo lo fa rispettare
 * {@code @PreAuthorize} sul Controller.
 *
 * <p><b>La regola che tiene in piedi il resto e' la stessa delle tariffe</b>: due
 * aliquote non possono sovrapporsi. Senza, "quanto si paga per la notte del 15
 * agosto" avrebbe due risposte, e su un importo che si versa al comune "dipende da
 * come gira la query" non e' una risposta. Qui la verifica risponde 409 dicendo
 * con quale; la garanzia e' il vincolo di esclusione del V11, che regge anche
 * quando due richieste arrivano nello stesso istante.
 *
 * <p><b>Cio' che succede qui tocca le prenotazioni gia' fatte</b>, ed e' la
 * differenza piu' importante rispetto alle tariffe. L'{@code importoTotale} di una
 * prenotazione e' una fotografia presa alla creazione, quindi cambiare un prezzo
 * non riscrive la storia di chi ha gia' comprato; la tassa invece non e' mai
 * scritta da nessuna parte — si ricalcola ad ogni richiesta — quindi modificare o
 * cancellare un'aliquota <b>cambia il conto anche a chi e' gia' in albergo</b>.
 * Non e' un difetto: la tassa e' un debito verso il comune, e vale quello che il
 * regolamento dice. Ma vuol dire che a un comune che cambia aliquota a meta'
 * stagione si risponde aggiungendo un'aliquota nuova accanto alla vecchia, non
 * riscrivendo quella vecchia.
 */
public interface AliquotaTassaSoggiornoService {

    /**
     * Le aliquote, dalla piu' vecchia alla piu' recente.
     *
     * <p>Paginato come i periodi tariffari: si accumulano di anno in anno e niente
     * le limita. Nessuna aliquota configurata e' una pagina vuota e non un errore
     * — un comune senza tassa di soggiorno esiste.
     */
    ApiBaseResponsePaginated elenca(int page, int size);

    /**
     * Aggiunge un'aliquota. Solleva {@code BadRequestException} se la data di fine
     * precede quella di inizio o se l'importo ha piu' di due decimali, e
     * {@code ConflictException} se le date si sovrappongono a un'altra aliquota.
     */
    ApiBaseResponse crea(AliquotaTassaSoggiornoRequest request);

    /**
     * Riscrive un'aliquota per intero. Stesse eccezioni della creazione, piu' il
     * {@code NotFoundException} di quella che non esiste, e con il controllo di
     * sovrapposizione che <b>ignora l'aliquota stessa</b>: riconfermarle le proprie
     * date non e' un conflitto, altrimenti correggere il solo importo sarebbe
     * impossibile.
     *
     * <p>E' una PUT: {@code nottiMassimeTassate} ed {@code etaEsenzione} omessi
     * vengono <b>azzerati</b>, cioe' il tetto sparisce e i bambini cominciano a
     * pagare. E' quel che una PUT promette, ed e' scritto nel contratto perche' su
     * questi due campi la conseguenza si vede sul conto di qualcuno.
     */
    ApiBaseResponse aggiorna(Long aliquotaId, AliquotaTassaSoggiornoRequest request);

    /**
     * Cancella un'aliquota.
     *
     * <p><b>Le notti che copriva smettono di essere tassate</b>, senza errori e
     * senza rete: non c'e' nessun valore di base a cui ricadere, al contrario del
     * prezzo di listino che raccoglie le notti senza periodo tariffario. E'
     * l'effetto piu' brusco di questa risorsa, e sta scritto anche nel contratto.
     */
    ApiBaseResponse elimina(Long aliquotaId);
}
