package com.felixhotel.backend.security;

import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Chi puo' vedere quale prenotazione.
 *
 * <p><b>La regola e' una sola, ed e' questa</b>: il personale vede tutto, il cliente
 * vede la propria, e la prenotazione di un altro cliente e' <b>404 e non 403</b> — un
 * 403 direbbe "esiste, ma non e' tua", cioe' regalerebbe l'informazione che quell'id e'
 * valido a chi sta provando gli id a caso.
 *
 * <p><b>Perche' esiste questa classe.</b> La stessa regola era scritta due volte,
 * identica riga per riga, in {@code PrenotazioneServiceImpl} e in
 * {@code TassaSoggiornoServiceImpl}. Il gap che lo annotava aveva un innesco preciso —
 * <i>la terza risorsa che deve rispondere alla domanda "e' tua?"</i> — e i pagamenti
 * sono quella terza: al terzo caso il componente si scrive, non si discute. Il rischio
 * che chiudeva non era la ripetizione in se' ma il giorno in cui la regola cambia (se
 * per esempio lo STAFF non dovesse piu' vedere tutte le prenotazioni) e <b>una delle
 * copie resta indietro in silenzio</b>.
 *
 * <p><b>Perche' non dentro {@link ChiamanteCorrente}.</b> Quella classe risponde a
 * domande sull'account — chi sta chiamando, e' personale — e non conosce le
 * prenotazioni: darle un repository vorrebbe dire trasformare un oggetto di sicurezza in
 * un servizio di dominio. Qui il repository c'e' perche' la domanda e' inseparabile dal
 * dato: per sapere se una prenotazione sia tua bisogna prima averla.
 *
 * <p><b>Cosa questa classe non fa, ancora.</b> Non e' il bean di autorizzazione su cui
 * {@code @PreAuthorize} possa appoggiarsi, che chiuderebbe anche gli altri due gap dello
 * stesso grappolo — la regola "ruolo <i>e</i> tipo" chiamata solo da alcuni Service, e i
 * metodi di {@code OspiteServiceImpl} che non sono obbligati a chiamarla. Fa una cosa
 * sola: toglie le copie di una regola che riguarda le prenotazioni. Il resto resta
 * scritto nei gap, perche' e' un lavoro di un'altra misura.
 *
 * <p><b>Va chiamata dentro una transazione</b>: l'entita' che restituisce ha relazioni
 * LAZY — l'utente su tutte — e il controllo stesso ne legge una (regola 15).
 */
@Component
@RequiredArgsConstructor
public class AccessoPrenotazioni {

    private final PrenotazioneRepository prenotazioneRepository;
    private final ChiamanteCorrente chiamanteCorrente;

    /**
     * La prenotazione, se chi sta chiamando ha il diritto di vederla.
     *
     * @throws NotFoundException se non esiste <b>oppure</b> se e' di un altro cliente:
     *                           sono deliberatamente indistinguibili da fuori
     */
    public Prenotazione visibileOrElseThrow(Long prenotazioneId) {
        Prenotazione prenotazione = prenotazioneRepository.findById(prenotazioneId)
                .orElseThrow(() -> new NotFoundException("Prenotazione non trovata"));

        AppUserPrincipal chiamante = chiamanteCorrente.autenticato();
        if (!chiamanteCorrente.personale(chiamante)
                && !prenotazione.getUtente().getId().equals(chiamanteCorrente.idCliente(chiamante))) {
            throw new NotFoundException("Prenotazione non trovata");
        }

        return prenotazione;
    }
}
