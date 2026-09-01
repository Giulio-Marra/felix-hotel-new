package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;

/**
 * Il conto della tassa di soggiorno per una prenotazione.
 *
 * <p><b>E' un calcolo e non una risorsa</b>, ed e' la ragione per cui questa
 * interfaccia ha un metodo solo e nessuna scrittura: non esiste da nessuna parte
 * una riga "tassa dovuta". Il numero si ottiene ogni volta dagli ospiti registrati
 * e dalle aliquote in vigore, e non si congela mai.
 *
 * <p><b>Perche' non e' una fotografia come {@code importoTotale}.</b> Il prezzo
 * della camera si fissa alla creazione perche' e' il contratto fra albergo e
 * cliente: quel che si e' pattuito non cambia se domani il listino cambia. La
 * tassa non e' pattuita con nessuno — e' un debito verso il comune — e soprattutto
 * alla creazione <b>non sarebbe calcolabile</b>: dipende da chi dorme in albergo, e
 * gli ospiti si registrano dopo la conferma. Congelarla prima vorrebbe dire
 * congelare un numero inventato.
 *
 * <p><b>Il permesso e' quello delle prenotazioni e non quello degli ospiti</b>, ed
 * e' una distinzione voluta: STAFF e ADMIN vedono tutto, il cliente vede la
 * <i>propria</i>. Il registro degli ospiti resta chiuso al personale perche'
 * contiene documenti d'identita' di terzi; questo conto contiene nomi e importi, e
 * l'importo lo paghera' il cliente all'arrivo. Nascondergli quanto sara' non
 * servirebbe a proteggere nessuno. La prenotazione di un altro cliente risponde
 * <b>404 e non 403</b>, come ogni altra rotta sotto {@code /api/prenotazioni}.
 *
 * <p><b>Le esenzioni sono di due specie e vanno tenute separate</b>: quelle che il
 * sistema calcola — l'eta' e il tetto di notti — e quelle che qualcuno dichiara
 * sull'ospite. Il perche' della divisione sta su
 * {@code MotivoEsenzione}; qui conta la conseguenza, cioe' che il calcolo non
 * chiede mai a nessuno di confermare cio' che sa gia'.
 */
public interface TassaSoggiornoService {

    /**
     * Quanto deve questa prenotazione, e persona per persona quante notti paga.
     *
     * <p>Solleva {@code NotFoundException} se la prenotazione non esiste <b>o se
     * non e' del cliente che chiede</b>, {@code UnauthorizedException} se non c'e'
     * nessuno autenticato.
     *
     * <p><b>Non ha finestra di stato</b>, come la lettura del registro degli
     * ospiti: su una prenotazione ancora IN_ATTESA gli ospiti sono zero e quindi il
     * totale e' zero, e dopo la partenza il conto si rilegge — che e' anzi quando
     * serve di piu', perche' e' allora che si versa al comune.
     */
    ApiBaseResponse calcola(Long prenotazioneId);
}
