package com.felixhotel.backend.entity.enums;

/**
 * Come il denaro e' arrivato.
 *
 * <p><b>Sono tre e non quattro: manca il pagamento online</b>, e l'assenza e'
 * deliberata. Nascera' col branch di Stripe, insieme al codice che lo produce:
 * dichiarare adesso un valore che nessuna riga puo' avere sarebbe una promessa senza
 * codice che la mantenga, cioe' la cosa che la regola 17 vieta. Un elenco che descrive
 * il futuro invece del presente e' anche il modo in cui un frontend finisce per
 * disegnare una voce di menu che non funziona.
 *
 * <p><b>Non e' l'elenco dei modi di pagare di un albergo, e' l'elenco di quelli che
 * questo registro sa distinguere.</b> Un assegno o un buono vacanza si registrano come
 * quel che sono nel riferimento; spaccare l'enum ad ogni strumento nuovo vorrebbe dire
 * una migration per ogni abitudine locale.
 *
 * <p>Vive come enum Java e non come tabella, per la stessa ragione di
 * {@link StatoPrenotazione}: l'elenco e' chiuso e lo impone un CHECK nel DDL
 * (V19__pagamenti.sql). I nomi coincidono con i valori del CHECK e con l'enum generato
 * dallo spec OpenAPI: tre elenchi che devono restare allineati.
 */
public enum MetodoPagamento {

    /** Contanti al banco. E' l'unico che non lascia un riferimento da riconciliare. */
    CONTANTI,

    /**
     * Bonifico bancario, di solito la caparra versata prima dell'arrivo.
     *
     * <p>E' quello per cui la data dell'incasso conta davvero: il denaro arriva sul
     * conto un giorno e chi sta al banco lo registra un altro.
     */
    BONIFICO,

    /** Carta passata sul POS della struttura, quindi con l'ospite presente. */
    POS
}
