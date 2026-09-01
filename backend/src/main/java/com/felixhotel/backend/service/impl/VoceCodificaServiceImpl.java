package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.entity.TipoCodifica;
import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.VoceCodificaMapper;
import com.felixhotel.backend.repository.VoceCodificaRepository;
import com.felixhotel.backend.service.VoceCodificaService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementazione delle tabelle di codifica ministeriali.
 *
 * <p><b>E' il Service piu' corto del progetto, e la ragione e' che qui non c'e'
 * dominio.</b> Nessuna regola di business, nessuna macchina a stati, nessun
 * calcolo: questi dati non li produce l'applicazione, li ripete. Tutto quel che
 * fa e' leggerli in ordine e sostituirli in blocco.
 *
 * <p><b>Le due cose che vale la pena leggere</b> riguardano tutte e due l'import:
 * <ul>
 *   <li><b>si cancella e si riscrive</b>, invece di confrontare riga per riga.
 *       Un aggiornamento del Ministero puo' <i>togliere</i> una voce — i comuni si
 *       fondono — e un merge lascerebbe in tabella un codice soppresso che nessuno
 *       si accorge di avere. La sostituzione ha anche la proprieta' che il
 *       risultato non dipende da quante volte la si ripete;</li>
 *   <li><b>i doppioni si trovano prima di scrivere</b>, non lasciandoli sbattere
 *       contro l'indice unico. Un file del Ministero con due volte lo stesso codice
 *       e' un file rotto, e dirlo con il codice che lo rompe risparmia a chi lo ha
 *       caricato di cercarselo fra ottomila righe.</li>
 * </ul>
 */
@Service
public class VoceCodificaServiceImpl implements VoceCodificaService {

    private final VoceCodificaRepository voceCodificaRepository;
    private final VoceCodificaMapper voceCodificaMapper;
    private final ApiResponseMapper apiResponseMapper;

    public VoceCodificaServiceImpl(VoceCodificaRepository voceCodificaRepository,
                                   VoceCodificaMapper voceCodificaMapper,
                                   ApiResponseMapper apiResponseMapper) {
        this.voceCodificaRepository = voceCodificaRepository;
        this.voceCodificaMapper = voceCodificaMapper;
        this.apiResponseMapper = apiResponseMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(com.felixhotel.backend.dto.TipoCodifica tipo,
                                           String filtro, int page, int size) {
        Page<VoceCodifica> pagina = voceCodificaRepository.cerca(
                tipoEntita(tipo), normalizza(filtro), PageRequest.of(page, size));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Codifiche recuperate",
                voceCodificaMapper.toResponseList(pagina.getContent()), pagina);
    }

    /**
     * Sostituisce l'elenco intero di una famiglia.
     *
     * <p>Il conteggio di cio' che c'era prima finisce nel messaggio, e non e'
     * decorazione: chi importa ottomila comuni vuole sapere se ne ha scritti
     * ottomila o zero, e un "fatto" senza numeri non lo direbbe. E' la stessa
     * ragione per cui il 429 dei limiti di frequenza scrive nel messaggio quanto
     * resta da aspettare — la busta standard non ha un campo per questi dettagli.
     */
    @Override
    @Transactional
    public ApiBaseResponse importa(com.felixhotel.backend.dto.TipoCodifica tipo,
                                   List<com.felixhotel.backend.dto.VoceCodifica> voci) {
        TipoCodifica tipoEntita = tipoEntita(tipo);

        assicuraCodiciDistinti(voci);

        int cancellate = voceCodificaRepository.cancellaPerTipo(tipoEntita);
        // Il flush esplicito serve: senza, Hibernate potrebbe eseguire gli insert
        // prima della delete e far scattare l'indice unico su codici che stiamo
        // proprio sostituendo. E' lo stesso ordine che il codice legge, ma
        // l'ordine che il codice legge non e' quello che Hibernate esegue.
        voceCodificaRepository.flush();

        List<VoceCodifica> nuove = voceCodificaMapper.toEntita(tipoEntita, voci);
        voceCodificaRepository.saveAll(nuove);

        return apiResponseMapper.toResponse(HttpStatus.OK,
                "Codifica " + tipoEntita.name() + " sostituita: " + nuove.size()
                        + " voci al posto delle " + cancellate + " precedenti",
                null);
    }

    /**
     * Che nell'elenco mandato non ci sia due volte lo stesso codice.
     *
     * <p>Lo garantirebbe anche l'indice unico del V12, ma con un messaggio che non
     * dice <b>quale</b> codice — e cercarlo a mano fra ottomila righe non e' un
     * lavoro che si possa chiedere a nessuno. E' la stessa divisione dei compiti
     * gia' in uso: qui la cortesia, li' la garanzia.
     *
     * <p>400 e non 409: non c'e' nessun conflitto con qualcosa che esiste gia' —
     * quel che c'era sta per essere cancellato comunque — e' il corpo della
     * richiesta a non stare in piedi da solo.
     */
    private void assicuraCodiciDistinti(List<com.felixhotel.backend.dto.VoceCodifica> voci) {
        Set<String> visti = new HashSet<>();
        for (com.felixhotel.backend.dto.VoceCodifica voce : voci) {
            if (!visti.add(voce.getCodice())) {
                throw new BadRequestException(
                        "Il codice " + voce.getCodice() + " compare due volte nell'elenco");
            }
        }
    }

    /**
     * Il tipo dell'entita' a partire da quello del contratto.
     *
     * <p>Stessa forma della conversione gia' fatta per il tipo di documento e per
     * il motivo di esenzione, e stessa ragione: due enum diversi che si somigliano,
     * con questa riga sola a tenerne allineati gli elenchi. Non puo' fallire — il
     * valore l'ha gia' validato il bordo, che dal 2026-09-01 riceve un enum e non
     * una stringa — ma se il contratto guadagnasse una famiglia che l'entita' non
     * ha, e' qui che si vedrebbe.
     */
    private TipoCodifica tipoEntita(com.felixhotel.backend.dto.TipoCodifica tipo) {
        return TipoCodifica.valueOf(tipo.getValue());
    }

    /**
     * Un filtro fatto di soli spazi vale come nessun filtro.
     *
     * <p>Senza, una tendina che manda quel che l'utente ha digitato — spazio
     * compreso, mentre sta ancora scrivendo — restituirebbe zero risultati invece
     * dell'elenco intero, e sembrerebbe un difetto.
     */
    private String normalizza(String filtro) {
        return filtro == null || filtro.isBlank() ? null : filtro.trim();
    }
}
