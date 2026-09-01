package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.OspiteResponse;
import com.felixhotel.backend.entity.Ospite;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Conversione Entity -&gt; DTO per {@link Ospite}. Scritta a mano per scelta di
 * progetto (niente MapStruct); il DTO di destinazione e' generato dallo spec.
 *
 * <p>Come {@code MediaCameraMapper}, non tocca nessuna relazione LAZY — la
 * prenotazione non esce, sta gia' nell'URL di chi ha chiesto — quindi e' l'altro
 * caso del progetto in cui non serve l'avvertimento sul chiamarlo dentro la
 * transazione.
 *
 * <p><b>Escono tutti i campi, numero di documento compreso e in chiaro.</b> Non
 * e' una dimenticanza ed e' l'unica decisione di questa classe: queste rotte le
 * raggiungono solo STAFF e ADMIN, cioe' chi il documento l'ha appena avuto in
 * mano, e mascherare il numero renderebbe impossibile l'unica cosa per cui si
 * rilegge la lista — controllare di averlo digitato bene. Se un domani questi
 * dati dovessero comparire in una risposta che vede anche il cliente, e' questo
 * metodo che va guardato per primo: qui non c'e' nessuna rete.
 *
 * <p>La conversione fra i due {@code TipoDocumento} — quello dell'entita' e
 * quello generato dallo spec — e' logica vera, non copia: i due enum sono tipi
 * diversi che si somigliano, e la corrispondenza fra i loro valori la tiene in
 * piedi questa riga e nient'altro. E' il motivo per cui i test unitari dei
 * Service usano un mapper vero e non un finto.
 *
 * <p><b>Il documento puo' non esserci</b>, dal V10: un minorenne si registra senza,
 * e le due colonne restano vuote. Quindi la conversione dell'enum passa da
 * {@link #tipoDocumento(Ospite)} invece di stare in linea — la versione precedente,
 * scritta quando le colonne erano NOT NULL, chiamava {@code name()} sul valore e
 * sarebbe diventata un {@code NullPointerException} al primo bambino registrato.
 */
@Component
public class OspiteMapper {

    public OspiteResponse toResponse(Ospite ospite) {
        return new OspiteResponse()
                .id(ospite.getId())
                .nome(ospite.getNome())
                .cognome(ospite.getCognome())
                .tipoDocumento(tipoDocumento(ospite))
                .numeroDocumento(ospite.getNumeroDocumento())
                .dataNascita(ospite.getDataNascita());
    }

    /**
     * Il tipo di documento nel tipo del contratto, oppure {@code null} per chi non
     * ne ha uno.
     *
     * <p>Il {@code null} esce cosi' com'e' e non diventa un valore dell'enum: nel
     * contratto "questo ospite non ha un documento" e' l'assenza del campo, non un
     * valore in piu' fra CARTA_IDENTITA e PASSAPORTO. Aggiungerne uno vorrebbe dire
     * scrivere nel registro che il documento e' di tipo "nessuno", che e' una frase
     * diversa e piu' brutta.
     */
    private com.felixhotel.backend.dto.TipoDocumento tipoDocumento(Ospite ospite) {
        return ospite.getTipoDocumento() == null
                ? null
                : com.felixhotel.backend.dto.TipoDocumento.fromValue(ospite.getTipoDocumento().name());
    }

    /**
     * Versione per l'elenco intero. <b>L'ordine della lista in ingresso viene
     * conservato</b>: qui non si ordina niente, perche' chi chiama ha gia'
     * deciso — la query con il suo {@code order by} per id crescente, cioe'
     * l'ordine di registrazione.
     */
    public List<OspiteResponse> toResponseList(List<Ospite> ospiti) {
        return ospiti.stream().map(this::toResponse).toList();
    }
}
