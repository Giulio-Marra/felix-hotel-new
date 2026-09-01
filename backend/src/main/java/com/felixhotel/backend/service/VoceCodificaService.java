package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.TipoCodifica;
import com.felixhotel.backend.dto.VoceCodifica;

import java.util.List;

/**
 * Le tabelle di codifica pubblicate dal Ministero: comuni italiani, stati esteri,
 * tipi di documento e tipi di alloggiato.
 *
 * <p><b>Due sole operazioni, e non e' un CRUD incompleto.</b> Si legge, per
 * riempire una tendina, e si sostituisce l'elenco intero, quando il Ministero ne
 * pubblica una versione nuova. Non esiste un modo di creare, correggere o
 * cancellare <i>una</i> voce, ed e' la quarta riga della regola 24 applicata: sono
 * dati che devono solo essere <b>esatti</b>, quindi si aggiornano in blocco e non
 * si digitano. Una schermata da cui l'albergatore corregge il codice di un comune
 * non e' flessibilita', e' il modo di farsi rifiutare le schedine dalla Questura
 * senza accorgersene.
 *
 * <p><b>Il livello di permesso e' quello delle tariffe</b>: leggere e' di STAFF o
 * ADMIN — serve a chi compila una schedina al banco — importare e' solo degli
 * ADMIN. Niente di pubblico: non e' un elenco che interessi a un cliente.
 *
 * <p><b>All'installazione le tabelle sono vuote</b>, ed e' scritto nel contratto
 * perche' chi le trova cosi' non pensi a un difetto. I valori veri stanno sul
 * portale Alloggiati e inventarli avrebbe voluto dire scrivere dati falsi proprio
 * dove il dato deve essere esatto.
 */
public interface VoceCodificaService {

    /**
     * Le voci di una famiglia, filtrate per descrizione, in ordine alfabetico.
     *
     * <p>Una famiglia vuota e' una pagina vuota e non un errore: vuol dire che
     * nessuno ha ancora importato quell'elenco.
     *
     * @param filtro testo cercato dentro la descrizione senza distinguere le
     *               maiuscole, oppure null per l'elenco intero
     */
    ApiBaseResponsePaginated elenca(TipoCodifica tipo, String filtro, int page, int size);

    /**
     * Sostituisce l'intero elenco di una famiglia.
     *
     * <p><b>Sostituisce e non fonde</b>: un aggiornamento del Ministero puo' anche
     * togliere una voce — i comuni si fondono — e un merge lascerebbe in tabella un
     * codice soppresso che nessuno si accorge di avere.
     *
     * <p>Solleva {@code BadRequestException} se lo stesso codice compare due volte
     * nell'elenco mandato: e' un file rotto, e accorgersene qui e' meglio che
     * lasciarlo scoprire all'indice unico con un messaggio che non dice quale.
     *
     * <p>Un elenco vuoto svuota la famiglia, ed e' il modo di annullare un import
     * sbagliato.
     */
    ApiBaseResponse importa(TipoCodifica tipo, List<VoceCodifica> voci);
}
