package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.AuthResponse;
import com.felixhotel.backend.dto.EmailRequest;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.NuovaPasswordRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.TokenRequest;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.TokenEmail;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.entity.enums.TipoTokenEmail;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.AuthMapper;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.IstanteRevoca;
import com.felixhotel.backend.security.JwtService;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.AuthService;
import com.felixhotel.backend.service.LoginAttemptService;
import com.felixhotel.backend.service.RegistrationAttemptService;
import com.felixhotel.backend.service.ServizioNotifiche;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Implementazione della logica di autenticazione.
 *
 * <p>Gli errori si sollevano come sottoclassi di {@code AppException}, ognuna
 * delle quali porta con se' lo status che il caso merita: a impacchettarle
 * nella busta standard pensa il {@code GlobalExceptionHandler}, qui non si
 * costruisce nessuna risposta d'errore a mano. Fa eccezione il login, che
 * cattura l'{@code AuthenticationException} di Spring Security per poter
 * rispondere "Credenziali non valide" invece del messaggio generico
 * dell'handler.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String RUOLO_USER = "USER";

    private final UtenteRepository utenteRepository;
    private final StaffRepository staffRepository;
    private final RuoloRepository ruoloRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final RegistrationAttemptService registrationAttemptService;
    /** Manda le tre email che partono da qui: verifica, reinvio e reset. */
    private final ServizioNotifiche servizioNotifiche;

    /** Verifica i token che arrivano dai link, e li consuma. */
    private final ServizioTokenEmail servizioToken;

    private final UtenteMapper utenteMapper;
    private final AuthMapper authMapper;
    private final ApiResponseMapper apiResponseMapper;

    /** Chi sta chiamando: qui serve solo a /api/auth/me, che non fa altro che raccontarlo. */
    private final ChiamanteCorrente chiamanteCorrente;

    /**
     * Registra un nuovo cliente (Utente) con ruolo USER. La registrazione
     * pubblica di Staff non e' prevista: quegli account nascono dal backoffice,
     * con POST /api/staff, che e' riservato agli ADMIN.
     */
    @Override
    @Transactional
    public ApiBaseResponse register(RegisterRequest request, String clientIp) {
        // Prima di ogni altra cosa, e in particolare prima di toccare il database e di
        // calcolare l'hash della password: chi ha gia' registrato troppi account da questo
        // indirizzo viene fermato qui con un 429. E' il punto della difesa — il tentativo
        // di troppo non deve costarci ne' una query ne' un BCrypt.
        // Resta fuori dal risparmio una cosa sola: il metodo e' @Transactional, quindi la
        // transazione viene aperta da Spring prima di arrivare a questa riga. Costa un
        // EntityManager e non una connessione (Hibernate la prende alla prima query, che
        // qui non c'e'), e spostare il controllo piu' a monte vorrebbe dire metterlo nel
        // Controller — cioe' logica di sicurezza in un layer che per convenzione non ne ha.
        registrationAttemptService.checkNotThrottled(clientIp);

        // Il tentativo si conta subito e a prescindere da come andra' a finire: qui non e'
        // il fallimento a essere sospetto (come nel login) ma la frequenza. Contare solo le
        // registrazioni riuscite lascerebbe fuori chi martella l'endpoint con email gia'
        // esistenti, che a noi costa comunque una query per ogni chiamata.
        registrationAttemptService.recordAttempt(clientIp);

        // Il consenso privacy (GDPR) deve essere esplicitamente true: OpenAPI puo' dichiarare
        // il campo obbligatorio ma non che debba valere true, quindi il vincolo si verifica
        // qui. E' un problema dell'input, non di stato: 400, non 409.
        if (!Boolean.TRUE.equals(request.getConsensoPrivacy())) {
            throw new BadRequestException("Il consenso al trattamento dei dati personali e' obbligatorio");
        }

        // L'unicita' email va controllata su entrambe le popolazioni: utente.email e staff.email
        // sono due indici unici indipendenti in DB, altrimenti un cliente potrebbe registrarsi
        // con l'email di un account Staff/ADMIN esistente e "oscurarlo" ai login successivi
        // (CustomUserDetailsService cerca prima tra gli Utente). E' lo stesso controllo che fa
        // StaffServiceImpl.verificaEmailLibera, visto dal lato dei clienti.
        // Il confronto ignora le maiuscole perche' cosi' fanno gli indici (vedi
        // V6__unicita_email_case_insensitive.sql): con un controllo case-sensitive il
        // duplicato passerebbe di qui per schiantarsi la', cioe' 500 invece di 409.
        String email = request.getEmail();

        if (utenteRepository.existsByEmailIgnoreCase(email) || staffRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email gia' registrata");
        }

        Ruolo ruoloUser = ruoloRepository.findByNome(RUOLO_USER)
                .orElseThrow(() -> new IllegalStateException(
                        "Ruolo USER mancante in DB: verificare V1__init_schema.sql"));

        Utente utente = new Utente();
        utente.setNome(request.getNome());
        utente.setCognome(request.getCognome());
        utente.setEmail(email);
        utente.impostaPassword(passwordEncoder.encode(request.getPassword()), IstanteRevoca.adesso());
        utente.setTelefono(request.getTelefono());
        utente.setDataNascita(request.getDataNascita());
        utente.setDataRegistrazione(LocalDateTime.now());
        utente.setAttivo(true);
        // Nasce NON verificato, dal 2026-09-02. Fino a ieri nasceva a true, e il commento
        // che stava qui diceva: "torna a false il giorno che la verifica esistera' davvero
        // — invio, token con scadenza e login bloccato finche' non e' confermata — e non
        // un momento prima". Quel giorno e' oggi: le tre cose ci sono tutte e tre, e il
        // campo e' tornato a significare qualcosa.
        utente.setEmailVerificata(false);
        // Arrivati qui il consenso e' per forza true (controllato sopra), quindi la data si
        // valorizza sempre: e' l'istante in cui il consenso e' stato raccolto.
        utente.setConsensoPrivacy(true);
        utente.setDataConsenso(LocalDateTime.now());
        utente.setRuolo(ruoloUser);

        Utente salvato = utenteRepository.save(utente);

        // Il link di conferma. Parte dopo il commit e non fa fallire niente se l'SMTP e'
        // irraggiungibile (vedi ServizioEmail): una registrazione riuscita resta riuscita,
        // e chi non riceve niente se ne fa mandare un altro.
        servizioNotifiche.verificaIndirizzo(salvato);

        // Nessun token qui: la registrazione crea l'account e basta, l'autenticazione si
        // ottiene con una chiamata esplicita a /api/auth/login. Il 201 restituisce la
        // risorsa appena creata, non una sessione: sono due operazioni distinte e chi
        // registra per conto d'altri (un domani, dal backoffice) non deve ritrovarsi loggato.
        //
        // Il messaggio dice cosa fare adesso, e non e' una gentilezza: il login rifiutera'
        // questo account finche' l'indirizzo non e' confermato, e rispondera' "Credenziali
        // non valide" come per ogni altro rifiuto — perche' distinguere i motivi direbbe a
        // chi prova email a caso quali esistono (decisione gia' presa, vedi login()).
        // Questo messaggio e' quindi l'unico posto in cui glielo si puo' spiegare.
        return apiResponseMapper.toResponse(HttpStatus.CREATED,
                "Registrazione completata: conferma il tuo indirizzo email per poter accedere",
                utenteMapper.toAccountSummary(salvato));
    }

    /**
     * Autentica email+password (Utente o Staff, vedi CustomUserDetailsService)
     * tramite l'AuthenticationManager di Spring Security e restituisce un
     * nuovo JWT.
     */
    @Override
    public ApiBaseResponse login(LoginRequest request, String clientIp) {
        // Prima di ogni altra cosa, e in particolare prima di andare a leggere l'utente
        // dal database: chi ha gia' accumulato troppi tentativi falliti viene fermato
        // qui con un 429, senza che le credenziali vengano nemmeno guardate. E' il
        // punto della difesa — l'attacco non deve costare niente a noi e molto a chi
        // lo porta.
        loginAttemptService.checkNotThrottled(request.getEmail(), clientIp);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (AuthenticationException ex) {
            // Si conta qualunque motivo di fallimento (password sbagliata, utente
            // inesistente, account disattivato): distinguerli darebbe a chi prova email
            // a caso un modo per capire quali esistono, e comunque sono tutti tentativi
            // di accesso non riusciti.
            loginAttemptService.recordFailure(request.getEmail(), clientIp);

            // L'eccezione originale si conserva come cause: distingue nei log un utente
            // inesistente da una password sbagliata, cosa che la risposta non fa apposta.
            throw new UnauthorizedException("Credenziali non valide", ex);
        }

        loginAttemptService.recordSuccess(request.getEmail(), clientIp);

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(
                principal.getUserId(), principal.getUsername(), principal.getRuoloNome());

        // Solo il token: i dati dell'account li chiedera' il client all'endpoint dedicato.
        AuthResponse response = authMapper.toAuthResponse(token, jwtService.getExpirationMs());

        return apiResponseMapper.toResponse(HttpStatus.OK, "Login effettuato con successo", response);
    }

    /**
     * Riepilogo dell'account autenticato. L'utente si legge dal
     * SecurityContext e non da un parametro annotato
     * {@code @AuthenticationPrincipal}: la firma del metodo la impone
     * l'interfaccia generata dallo spec OpenAPI ({@code me()} senza
     * argomenti), e aggiungere un parametro nel Controller non sarebbe un
     * override — Spring mapperebbe il default method dell'interfaccia,
     * che risponde 501.
     *
     * <p>La lettura del contesto e il 401 sull'anonimo stanno in
     * {@link ChiamanteCorrente}, che e' l'unico posto del progetto a saperlo
     * fare: qui erano scritti una seconda volta, identici a quelli delle
     * prenotazioni, commento compreso.
     */
    @Override
    public ApiBaseResponse me() {
        return apiResponseMapper.toResponse(HttpStatus.OK, "Dati account recuperati",
                authMapper.toAccountSummary(chiamanteCorrente.autenticato()));
    }
    /**
     * Conferma l'indirizzo di un cliente consumando il token del link.
     *
     * <p><b>Non restituisce un token di accesso</b>, e non e' una dimenticanza: e' la
     * stessa scelta gia' fatta dalla registrazione, che crea l'account e non autentica
     * nessuno. Confermare l'indirizzo e accedere sono due operazioni, e chi apre il link
     * puo' benissimo farlo da un dispositivo diverso da quello su cui vuole entrare.
     *
     * <p><b>Nessun limite di frequenza</b>, al contrario del reinvio: qui non si manda
     * niente e non si scrive niente finche' il token non e' valido, e indovinarne uno
     * vuol dire indovinare 256 bit.
     */
    @Override
    @Transactional
    public ApiBaseResponse verificaEmail(TokenRequest request) {
        TokenEmail token = servizioToken.consuma(TipoTokenEmail.VERIFICA_EMAIL, request.getToken());

        // Il token dice a chi appartiene, e il tipo di account dice in quale tabella
        // cercarlo. Un token di verifica e' sempre di un cliente — lo emette solo la
        // registrazione — ma il controllo c'e' lo stesso: e' l'unica cosa che
        // impedirebbe a una futura emissione sbagliata di scrivere sulla riga di un altro.
        if (token.getTipoAccount() != TipoAccount.CLIENTE) {
            throw new BadRequestException("Il link non e' valido");
        }

        Utente utente = utenteRepository.findById(token.getSoggettoId())
                // L'account e' stato cancellato fra l'invio e il clic. Stesso messaggio
                // degli altri casi: chi legge non deve poter distinguere "non esiste piu'"
                // da "non e' mai esistito".
                .orElseThrow(() -> new BadRequestException("Il link non e' valido"));

        utente.setEmailVerificata(true);
        utenteRepository.save(utente);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Indirizzo confermato, ora puoi accedere", null);
    }

    /**
     * Rimanda il link di conferma.
     *
     * <p><b>Risponde 200 sempre, e il messaggio e' al condizionale.</b> Un indirizzo che
     * non esiste, uno gia' confermato e uno in attesa danno la stessa risposta: qualunque
     * differenza trasformerebbe questa rotta in un modo per sapere quali indirizzi sono
     * registrati, senza nemmeno provare una password. E' lo stesso criterio del login.
     *
     * <p><b>Ha un limite di frequenza</b>, al contrario della conferma, e la ragione e'
     * che qui una richiesta <i>manda un'email</i>: senza, chiunque potrebbe usare questa
     * rotta per riempire la casella di qualcun altro, e farlo usando il nostro dominio
     * come mittente.
     */
    @Override
    @Transactional
    public ApiBaseResponse reinviaVerificaEmail(EmailRequest request, String clientIp) {
        registrationAttemptService.checkNotThrottled(clientIp);
        registrationAttemptService.recordAttempt(clientIp);

        utenteRepository.findByEmailIgnoreCase(request.getEmail())
                // Gia' confermato: non si rimanda niente, ma la risposta non lo dice.
                .filter(utente -> !utente.isEmailVerificata())
                .ifPresent(servizioNotifiche::verificaIndirizzo);

        return apiResponseMapper.toResponse(HttpStatus.OK,
                "Se l'indirizzo e' registrato e non ancora confermato, hai ricevuto un link", null);
    }

    /**
     * Accetta un invito e imposta la password scelta dalla persona.
     *
     * <p>E' il modo in cui nasce l'accesso di uno STAFF o di un ADMIN dal 2026-09-02.
     * Fino a quel momento l'account esiste, e' attivo, e <b>non si autentica</b>: la sua
     * password e' nulla, e {@code CustomUserDetailsService} lo tratta come non abilitato.
     */
    @Override
    @Transactional
    public ApiBaseResponse attivaAccountPersonale(NuovaPasswordRequest request) {
        TokenEmail token = servizioToken.consuma(TipoTokenEmail.INVITO_STAFF, request.getToken());

        if (token.getTipoAccount() != TipoAccount.PERSONALE) {
            throw new BadRequestException("Il link non e' valido");
        }

        Staff staff = staffRepository.findById(token.getSoggettoId())
                .orElseThrow(() -> new BadRequestException("Il link non e' valido"));

        staff.impostaPassword(passwordEncoder.encode(request.getPassword()), IstanteRevoca.adesso());
        staffRepository.save(staff);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Password impostata, ora puoi accedere", null);
    }

    /**
     * Manda il link per reimpostare la password.
     *
     * <p><b>Cerca in tutte e due le popolazioni</b>, clienti e personale, nello stesso
     * ordine del login: la credenziale e' una sola per tutto il progetto, quindi chi
     * dimentica la password non deve sapere in quale tabella vive.
     *
     * <p><b>Risponde 200 sempre</b>, come il reinvio e per una ragione che qui pesa di
     * piu': una risposta diversa direbbe a chiunque quali indirizzi hanno un account,
     * gratis.
     *
     * <p><b>Chi non ha ancora accettato l'invito non riceve un reset</b>: non ha una
     * password da reimpostare, e mandargliene uno vorrebbe dire una seconda strada per
     * entrare che scavalca l'invito. Anche questo caso risponde 200 e non manda niente.
     */
    @Override
    @Transactional
    public ApiBaseResponse richiediResetPassword(EmailRequest request, String clientIp) {
        registrationAttemptService.checkNotThrottled(clientIp);
        registrationAttemptService.recordAttempt(clientIp);

        utenteRepository.findByEmailIgnoreCase(request.getEmail()).ifPresentOrElse(
                utente -> servizioNotifiche.resetPassword(
                        utente.getEmail(), utente.getNome(), TipoAccount.CLIENTE, utente.getId()),
                () -> staffRepository.findByEmailIgnoreCase(request.getEmail())
                        .filter(staff -> staff.getPasswordHash() != null)
                        .ifPresent(staff -> servizioNotifiche.resetPassword(
                                staff.getEmail(), staff.getNome(), TipoAccount.PERSONALE, staff.getId())));

        return apiResponseMapper.toResponse(HttpStatus.OK,
                "Se l'indirizzo e' registrato, hai ricevuto un link per reimpostare la password", null);
    }

    /**
     * Scrive la nuova password consumando il token di reset.
     *
     * <p><b>E' il token a dire in quale tabella scrivere</b>, non chi chiama: lasciarlo
     * scegliere al corpo della richiesta vorrebbe dire accettare che indichi la tabella
     * sbagliata, e un id che esiste in tutte e due le sequenze e' il caso normale, non
     * quello raro.
     *
     * <p><b>Chi ha gia' un token di accesso lo tiene</b>, ed e' un limite noto del
     * progetto e non di questa rotta: non esiste nessun modo di revocare un JWT, quindi
     * cambiare la password non butta fuori chi era gia' entrato. Sta nei gap dal
     * 2026-08-06.
     */
    @Override
    @Transactional
    public ApiBaseResponse reimpostaPassword(NuovaPasswordRequest request) {
        TokenEmail token = servizioToken.consuma(TipoTokenEmail.RESET_PASSWORD, request.getToken());
        String hash = passwordEncoder.encode(request.getPassword());

        if (token.getTipoAccount() == TipoAccount.CLIENTE) {
            Utente utente = utenteRepository.findById(token.getSoggettoId())
                    .orElseThrow(() -> new BadRequestException("Il link non e' valido"));
            utente.impostaPassword(hash, IstanteRevoca.adesso());
            utenteRepository.save(utente);
        } else {
            Staff staff = staffRepository.findById(token.getSoggettoId())
                    .orElseThrow(() -> new BadRequestException("Il link non e' valido"));
            staff.impostaPassword(hash, IstanteRevoca.adesso());
            staffRepository.save(staff);
        }

        return apiResponseMapper.toResponse(HttpStatus.OK, "Password reimpostata, ora puoi accedere", null);
    }

}
