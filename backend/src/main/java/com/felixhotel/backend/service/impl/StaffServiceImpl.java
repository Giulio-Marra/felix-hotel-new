package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.ApiBaseResponsePaginated;
import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffAggiornamentoRequest;
import com.felixhotel.backend.dto.StaffAttivazioneRequest;
import com.felixhotel.backend.dto.StaffPasswordRequest;
import com.felixhotel.backend.dto.StaffRequest;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.StaffMapper;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.service.ServizioNotifiche;
import com.felixhotel.backend.service.StaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementazione della gestione degli account del personale.
 *
 * <p>Ricalca gli altri CRUD del progetto — doppio controllo sul duplicato,
 * violazione di vincolo intercettata dov'e' generata col {@code saveAndFlush}
 * dentro il try — e ne aggiunge due cose sue.
 *
 * <p><b>La prima: qui si scrivono credenziali.</b> La password arriva in chiaro
 * e non deve restare tale un istante piu' del necessario: viene cifrata con lo
 * stesso {@code PasswordEncoder} dell'applicazione, e nessun metodo di questa
 * classe la rimanda indietro in nessuna forma. L'email non e' un dato
 * anagrafico ma <b>la chiave di accesso</b>, e per questo l'unicita' si
 * verifica su entrambe le popolazioni: {@code CustomUserDetailsService} cerca
 * prima fra i clienti e poi fra il personale, quindi un indirizzo duplicato
 * fra le due tabelle renderebbe uno dei due account irraggiungibile.
 *
 * <p><b>La seconda: l'ultimo amministratore non si tocca.</b> Ogni operazione
 * che smetterebbe di lasciare almeno un ADMIN attivo viene rifiutata con 409.
 * Non e' prudenza generica: e' l'unica cosa che si puo' fare da qui e da cui non
 * si torna indietro <i>da qui</i> — chi si chiude fuori dal backoffice si
 * riapre la porta solo scrivendo nel database, cioe' proprio la cosa che questi
 * endpoint esistono per non dover piu' fare.
 */
@Service
@RequiredArgsConstructor
public class StaffServiceImpl implements StaffService {

    /** Il ruolo che non puo' restare senza nessuno che lo porti. */
    private static final String RUOLO_ADMIN = "ADMIN";

    private final StaffRepository staffRepository;

    /**
     * Serve solo a sapere se un'email e' gia' di un cliente: gli account del
     * personale non hanno nessun'altra relazione con gli utenti.
     */
    private final UtenteRepository utenteRepository;

    /** Serve a risolvere il ruolo indicato nelle richieste di scrittura. */
    private final RuoloRepository ruoloRepository;

    private final PasswordEncoder passwordEncoder;
    /** Manda l'invito con cui la persona sceglie la propria password. */
    private final ServizioNotifiche servizioNotifiche;

    private final StaffMapper staffMapper;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Elenco paginato e filtrato, in ordine di cognome e poi di nome.
     *
     * <p>L'ordine e' quello con cui si cerca una persona in un elenco di
     * personale, e il nome serve come secondo criterio perche' due Bianchi in
     * organico non sono un caso di scuola.
     *
     * <p><b>Comprende gli account disattivati</b> quando il filtro non e'
     * valorizzato, ed e' voluto: un account disattivato non sparisce, e chi
     * gestisce il personale deve poterlo ritrovare per riattivarlo.
     */
    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponsePaginated elenca(int page, int size, RuoloStaff ruolo, Boolean attivo) {
        Page<Staff> pagina = staffRepository.cerca(
                ruolo == null ? null : ruolo.getValue(),
                attivo,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "cognome", "nome")));

        return apiResponseMapper.toPaginatedResponse(HttpStatus.OK, "Personale recuperato",
                staffMapper.toResponseList(pagina.getContent()), pagina);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiBaseResponse dettaglio(Long id) {
        Staff staff = trovaOrElseThrow(id);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Account del personale recuperato",
                staffMapper.toResponse(staff));
    }

    /**
     * Creazione. L'account nasce <b>attivo</b>: si crea un account quando serve
     * che qualcuno entri, e farlo nascere spento vorrebbe dire due operazioni
     * per fare una cosa sola.
     */
    @Override
    @Transactional
    public ApiBaseResponse crea(StaffRequest request) {
        String email = request.getEmail();
        verificaEmailLibera(email, null);

        // Il ruolo si risolve prima di costruire l'account. Il commento che stava qui
        // diceva che cosi' una richiesta destinata a essere rifiutata non paga anche il
        // BCrypt: dal 2026-09-02 il BCrypt in questo metodo non c'e' piu' — la password
        // la sceglie la persona accettando l'invito — ma risolvere prima resta giusto,
        // perche' un ruolo inesistente e' un 400 che non deve costare una INSERT.
        Ruolo ruolo = trovaRuoloOrElseThrow(request.getRuolo());

        Staff staff = new Staff();
        staff.setNome(request.getNome());
        staff.setCognome(request.getCognome());
        staff.setEmail(email);
        // Nessuna password: la scegliera' la persona accettando l'invito. Dal V14 la
        // colonna e' nullable proprio per rendere rappresentabile questo stato, ed e' il
        // caso normale fra la creazione e il primo accesso — non un account rotto.
        // Finche' resta nulla, CustomUserDetailsService non considera l'account abilitato.
        staff.setTelefono(request.getTelefono());
        staff.setDataAssunzione(request.getDataAssunzione());
        staff.setAttivo(true);
        staff.setRuolo(ruolo);

        Staff salvato = salvaGestendoIlDuplicato(staff);

        // L'invito. Parte dopo il commit e non fa fallire la creazione se l'SMTP e'
        // irraggiungibile (vedi ServizioEmail): l'account resta, e la via di riserva e'
        // PUT /api/staff/{id}/password.
        servizioNotifiche.invitoPersonale(salvato);

        return apiResponseMapper.toResponse(HttpStatus.CREATED,
                "Account del personale creato: la persona ha ricevuto un invito per scegliere la password",
                staffMapper.toResponse(salvato));
    }

    /**
     * Aggiornamento completo dei campi anagrafici e del ruolo: e' una PUT,
     * quindi i campi facoltativi assenti vengono azzerati e non lasciati
     * com'erano.
     *
     * <p><b>Password e attivazione restano fuori</b> e hanno i loro endpoint.
     * Non e' una simmetria mancata con le camere, dove la PUT sostituisce anche
     * lo stato: la' un campo dimenticato riporta una stanza a LIBERA, qui
     * riaprirebbe l'accesso a un account chiuso o ne cambierebbe la password.
     * Sono le due cose che una dimenticanza non deve poter fare.
     */
    @Override
    @Transactional
    public ApiBaseResponse aggiorna(Long id, StaffAggiornamentoRequest request) {
        Staff staff = trovaOrElseThrow(id);

        String email = request.getEmail();
        verificaEmailLibera(email, id);

        Ruolo nuovoRuolo = trovaRuoloOrElseThrow(request.getRuolo());
        // Il controllo si fa prima di scrivere, e guarda cosa l'account e' adesso: se
        // smette di essere un ADMIN attivo, dev'esserci qualcun altro a restarlo.
        if (!RUOLO_ADMIN.equals(nuovoRuolo.getNome())) {
            verificaNonSiaUltimoAdmin(staff, "Impossibile togliere il ruolo ADMIN");
        }

        staff.setNome(request.getNome());
        staff.setCognome(request.getCognome());
        staff.setEmail(email);
        staff.setTelefono(request.getTelefono());
        staff.setDataAssunzione(request.getDataAssunzione());
        staff.setRuolo(nuovoRuolo);

        Staff salvato = salvaGestendoIlDuplicato(staff);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Account del personale aggiornato",
                staffMapper.toResponse(salvato));
    }

    /**
     * Attivazione e disattivazione, che e' il posto in cui questa risorsa mette
     * quello che altrove sarebbe un DELETE.
     *
     * <p><b>Non esiste un DELETE, e non e' una dimenticanza.</b> Il personale
     * compare nelle prenotazioni che ha gestito ({@code gestita_da_staff_id}), e
     * cancellare la riga porterebbe via l'unica risposta alla domanda "chi ha
     * registrato questa prenotazione". Chi non lavora piu' qui si disattiva: non
     * puo' piu' autenticarsi — il controllo e' quello che gia' esiste ad ogni
     * richiesta, {@code AppUserPrincipal.isEnabled()} — e le sue prenotazioni
     * restano intestate a lui, perche' sono storia e la storia dice chi c'era
     * davvero.
     */
    @Override
    @Transactional
    public ApiBaseResponse impostaAttivazione(Long id, StaffAttivazioneRequest request) {
        Staff staff = trovaOrElseThrow(id);

        // Il getter si legge una volta sola e si tiene, come gia' fatto altrove: chiamarlo
        // due volte vuol dire fidarsi che dia la stessa risposta a entrambe (SpotBugs,
        // NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
        boolean attivo = Boolean.TRUE.equals(request.getAttivo());

        if (!attivo) {
            verificaNonSiaUltimoAdmin(staff, "Impossibile disattivare l'account");
        }

        staff.setAttivo(attivo);

        Staff salvato = staffRepository.save(staff);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Attivazione dell'account aggiornata",
                staffMapper.toResponse(salvato));
    }

    /**
     * Nuova password, scelta da un ADMIN per conto di qualcun altro.
     *
     * <p>La risposta non porta niente: rimandare indietro l'account servirebbe a
     * mostrare campi che non sono cambiati, e su un endpoint che maneggia
     * credenziali la risposta piu' utile e' quella che non dice niente di piu'
     * di "e' andata".
     */
    @Override
    @Transactional
    public ApiBaseResponse impostaPassword(Long id, StaffPasswordRequest request) {
        Staff staff = trovaOrElseThrow(id);

        staff.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        staffRepository.save(staff);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Password aggiornata", null);
    }

    /** Lettura per id, con il 404 gia' pronto: e' il preambolo di tutti i metodi tranne l'elenco. */
    private Staff trovaOrElseThrow(Long id) {
        return staffRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account del personale non trovato"));
    }

    /**
     * L'email non deve essere di nessun altro, <b>su entrambe le popolazioni</b>.
     *
     * <p>Il controllo sui clienti non e' eccesso di zelo: l'email e' la
     * credenziale di login e {@code CustomUserDetailsService} cerca prima in
     * {@code utente} e poi in {@code staff}, quindi un account del personale che
     * ne condividesse una con un cliente non riuscirebbe mai ad autenticarsi —
     * il login troverebbe sempre l'altro. E' lo stesso controllo che fa la
     * registrazione pubblica, visto dal lato opposto.
     *
     * <p>Resta la rete sotto: l'indice unico in database, che copre la richiesta
     * gemella arrivata nel frattempo. Copre pero' solo la stessa tabella —
     * fra {@code utente} e {@code staff} non esiste un vincolo che le leghi, e
     * quella finestra la si accetta qui come gia' la accetta la registrazione.
     *
     * @param idDaEscludere l'account che si sta modificando, o null in creazione:
     *                      senza, riconfermargli la propria email darebbe 409
     */
    private void verificaEmailLibera(String email, Long idDaEscludere) {
        boolean giaDelPersonale = idDaEscludere == null
                ? staffRepository.existsByEmailIgnoreCase(email)
                : staffRepository.existsByEmailIgnoreCaseAndIdNot(email, idDaEscludere);

        if (giaDelPersonale || utenteRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email gia' registrata");
        }
    }

    /**
     * Rifiuta l'operazione se lascerebbe l'albergo senza nessun ADMIN attivo.
     *
     * <p>Guarda com'e' l'account <b>adesso</b>: se non e' un amministratore
     * attivo, toglierli il ruolo o spegnerlo non cambia il conto e non c'e'
     * niente da proteggere. Se lo e', ne serve almeno un altro — e il conteggio
     * lo esclude, perche' in database la sua riga e' ancora quella di prima.
     *
     * <p>Il 409 e non il 400: la richiesta e' formulata bene, e' lo stato del
     * sistema a renderla impossibile.
     *
     * @param motivo l'inizio del messaggio, perche' le due operazioni che
     *               finiscono qui sono diverse e chi le riceve deve capire quale
     *               delle due gli e' stata rifiutata
     */
    private void verificaNonSiaUltimoAdmin(Staff staff, String motivo) {
        if (!staff.isAttivo() || !RUOLO_ADMIN.equals(staff.getRuolo().getNome())) {
            return;
        }

        if (staffRepository.countByRuoloNomeAndAttivoTrueAndIdNot(RUOLO_ADMIN, staff.getId()) == 0) {
            throw new ConflictException(
                    motivo + ": e' l'ultimo amministratore attivo, e senza nessun ADMIN"
                            + " il backoffice non sarebbe piu' raggiungibile");
        }
    }

    /**
     * Risolve il ruolo indicato nella richiesta.
     *
     * <p>Un ruolo assente in tabella e' un guasto della nostra installazione e
     * non un errore di chi chiama: i tre ruoli li inserisce il V1, e se non ci
     * sono e' il database ad essere sbagliato. Stessa scelta gia' fatta dalla
     * registrazione per il ruolo USER.
     */
    private Ruolo trovaRuoloOrElseThrow(RuoloStaff ruolo) {
        String nome = ruolo.getValue();

        return ruoloRepository.findByNome(nome)
                .orElseThrow(() -> new IllegalStateException(
                        "Ruolo " + nome + " mancante in DB: verificare V1__init_schema.sql"));
    }

    /**
     * Scrive subito, invece di aspettare il commit, per poter tradurre in 409 la
     * violazione dell'indice unico sull'email. E' la rete sotto al controllo
     * {@code existsBy}: copre la richiesta gemella arrivata nel frattempo, che
     * nessun controllo preventivo puo' vedere.
     */
    private Staff salvaGestendoIlDuplicato(Staff staff) {
        try {
            return staffRepository.saveAndFlush(staff);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Email gia' registrata", ex);
        }
    }
}
