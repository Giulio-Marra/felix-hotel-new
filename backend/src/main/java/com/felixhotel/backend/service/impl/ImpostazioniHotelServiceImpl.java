package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ImpostazioniHotelRequest;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.ImpostazioniHotelMapper;
import com.felixhotel.backend.repository.ImpostazioniHotelRepository;
import com.felixhotel.backend.service.ImpostazioniHotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Implementazione dell'anagrafica della struttura.
 *
 * <p>E' il Service piu' corto del progetto, e il motivo vale la pena scriverlo:
 * la meta' del lavoro di un CRUD sta nel <b>ciclo di vita</b> — nasce, si cerca,
 * puo' non esistere, puo' sparire mentre la si guarda — e qui quel ciclo non
 * c'e'. La riga e' una sola, esiste dalla migration
 * {@code V8__identita_struttura.sql} e il vincolo {@code CHECK (id = 1)}
 * impedisce che ne compaia una seconda. Restano due gesti: leggere e riscrivere.
 *
 * <p><b>Non c'e' nessun 404</b>, ed e' una conseguenza di quella scelta. Un
 * endpoint che risponde "non trovato" sull'unica riga che deve esistere
 * inviterebbe ogni consumatore futuro — le fatture, le schedine — a portarsi
 * dietro un ramo "e se non ci fossero?". Seminare la riga nella migration toglie
 * quel ramo a tutti quanti, una volta sola.
 *
 * <p><b>Non c'e' nemmeno un 409</b>, che negli altri CRUD nasce dai vincoli di
 * unicita': qui non ce n'e' nessuno da violare. Vale la pena dirlo dell'email —
 * e' un recapito pubblico della struttura, non una credenziale, quindi non
 * partecipa all'unicita' garantita dalla {@code V6} sugli account.
 */
@Service
@RequiredArgsConstructor
public class ImpostazioniHotelServiceImpl implements ImpostazioniHotelService {

    private final ImpostazioniHotelRepository impostazioniHotelRepository;
    private final ImpostazioniHotelMapper impostazioniHotelMapper;
    private final ApiResponseMapper apiResponseMapper;

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse leggi() {
        ImpostazioniHotel impostazioni = trovaLaRigaUnica();

        return apiResponseMapper.toResponse(HttpStatus.OK, "Impostazioni recuperate",
                impostazioniHotelMapper.toResponse(impostazioni));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse leggiPubbliche() {
        ImpostazioniHotel impostazioni = trovaLaRigaUnica();

        return apiResponseMapper.toResponse(HttpStatus.OK, "Impostazioni recuperate",
                impostazioniHotelMapper.toPubblicheResponse(impostazioni));
    }

    /**
     * Aggiornamento completo: e' una PUT, quindi i campi assenti dalla richiesta
     * vengono azzerati e non lasciati al valore precedente. Non e' un effetto
     * collaterale ma il significato del verbo — ed e' il motivo per cui la GET
     * riservata restituisce <b>tutto</b>: chi compila il modulo deve poter
     * rimandare indietro anche i campi che non sta cambiando.
     *
     * <p>La risposta e' la vista completa e non quella pubblica: a scrivere e'
     * un ADMIN, e ricevere in risposta meno di quello che si e' appena mandato
     * lascerebbe il dubbio che il resto non sia stato salvato.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(ImpostazioniHotelRequest request) {
        ImpostazioniHotel impostazioni = trovaLaRigaUnica();

        verificaDecimaliCaparra(request);
        applicaCampi(impostazioni, request);

        // save e non saveAndFlush: negli altri Service il flush esplicito serve a
        // intercettare la violazione di un indice unico dentro il metodo, per
        // tradurla in 409. Qui non c'e' nessun vincolo di unicita' da violare, quindi
        // non ci sarebbe niente da intercettare.
        ImpostazioniHotel salvate = impostazioniHotelRepository.save(impostazioni);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Impostazioni aggiornate",
                impostazioniHotelMapper.toResponse(salvate));
    }

    /**
     * Rifiuta una percentuale con piu' di due decimali.
     *
     * <p>Stessa regola dei prezzi e delle aliquote, e per la stessa ragione: la colonna
     * e' {@code NUMERIC(5,2)} e Postgres troncherebbe in silenzio, quindi la risposta
     * direbbe un numero e il database ne conserverebbe un altro. Qui pesa piu' che altrove,
     * perche' da questa percentuale esce un importo che si chiede a un cliente.
     */
    private void verificaDecimaliCaparra(ImpostazioniHotelRequest request) {
        BigDecimal percentuale = request.getPercentualeCaparra();

        if (percentuale != null && percentuale.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(
                    "La percentuale della caparra non puo' avere piu' di due decimali");
        }
    }

    /**
     * Legge l'unica riga dell'anagrafica.
     *
     * <p>Se non c'e', il guasto <b>non e' del client</b>: la riga la scrive la
     * migration, quindi la sua assenza vuol dire che qualcuno l'ha cancellata a
     * mano dal database. Da qui l'{@link IllegalStateException}, che il
     * {@code GlobalExceptionHandler} traduce in 500 con un messaggio generico —
     * e non una {@code NotFoundException}, che darebbe 404 dicendo a chi chiama
     * che ha sbagliato qualcosa lui.
     */
    private ImpostazioniHotel trovaLaRigaUnica() {
        return impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA)
                .orElseThrow(() -> new IllegalStateException(
                        "Le impostazioni della struttura non esistono: "
                                + "la riga creata dalla migration e' stata rimossa"));
    }

    /**
     * Copia nell'entity i campi che l'ADMIN puo' decidere. Sta qui e non nel
     * mapper perche' non e' una conversione: e' l'elenco di cosa e' modificabile
     * da fuori — id e date di audit non compaiono, e non e' una dimenticanza.
     */
    private void applicaCampi(ImpostazioniHotel impostazioni, ImpostazioniHotelRequest request) {
        impostazioni.setNome(request.getNome());
        impostazioni.setIndirizzo(request.getIndirizzo());
        impostazioni.setTelefono(request.getTelefono());
        impostazioni.setEmail(request.getEmail());
        impostazioni.setOrarioCheckInDefault(request.getOrarioCheckInDefault());
        impostazioni.setOrarioCheckOutDefault(request.getOrarioCheckOutDefault());
        impostazioni.setRagioneSociale(request.getRagioneSociale());
        impostazioni.setPartitaIva(request.getPartitaIva());
        impostazioni.setCodiceFiscale(request.getCodiceFiscale());
        impostazioni.setCin(request.getCin());
        impostazioni.setComune(request.getComune());
        impostazioni.setCodiceIstatComune(request.getCodiceIstatComune());
        impostazioni.setCodiceStrutturaAlloggiati(request.getCodiceStrutturaAlloggiati());

        // **Omessa vuol dire zero e non "lasciala com'era".** Questa PUT sostituisce, e
        // ogni altro campo facoltativo omesso qui si svuota: farne l'unica eccezione
        // vorrebbe dire un campo che non si puo' piu' riportare a zero senza saperlo.
        // Zero e' anche il default della colonna, cioe' "nessuna caparra".
        impostazioni.setPercentualeCaparra(
                request.getPercentualeCaparra() == null ? BigDecimal.ZERO : request.getPercentualeCaparra());
    }
}
