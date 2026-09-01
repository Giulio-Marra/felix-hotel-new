package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.CanalePrenotazione;
import com.felixhotel.backend.dto.PrenotazioneCheckInRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.StatoCamera;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.CameraMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.mapper.PrenotazioneMapper;
import com.felixhotel.backend.mapper.StaffMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.PreventivoTipologia;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.DurataSoggiorno;
import com.felixhotel.backend.service.impl.PrenotazioneServiceImpl;
import com.felixhotel.backend.support.OrologioPilotato;
import com.felixhotel.backend.support.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link PrenotazioneServiceImpl}: la classe sotto esame e'
 * vera, repository finti.
 *
 * <p>I mapper invece sono <b>reali</b>, per la stessa ragione gia' scelta in
 * {@code CameraServiceImplTest}: convertono due coppie di enum omonimi passando
 * per il nome, e dei finti direbbero sempre quello che gli si e' detto di dire.
 *
 * <p><b>L'orologio e' pilotato</b> ({@link OrologioPilotato}) e non quello di
 * sistema. Serve a due cose diverse: verificare "non si prenota nel passato"
 * senza che il risultato dipenda dal giorno in cui la suite gira, e poter
 * affermare l'istante esatto scritto in {@code dataCancellazione} — con
 * l'orologio vero si potrebbe solo dire "piu' o meno adesso", che e'
 * un'asserzione che non fallisce mai.
 *
 * <p><b>Il {@code SecurityContextHolder} viene riempito a mano</b> in ogni test:
 * qui non c'e' filter chain, e chi chiama e' il dato da cui dipendono quasi
 * tutte le decisioni di questa classe. Va anche svuotato dopo, perche' e' uno
 * stato legato al thread e i test si passano lo stesso thread.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrenotazioneServiceImpl")
class PrenotazioneServiceImplTest {

    private static final Long ID_PRENOTAZIONE = 42L;
    private static final Long ID_TIPOLOGIA = 4L;
    private static final Long ID_CLIENTE = 7L;
    private static final Long ID_STAFF = 3L;
    private static final Long ID_CAMERA = 11L;

    private static final String EMAIL_CLIENTE = "mario.rossi@example.com";
    private static final String EMAIL_STAFF = "anna.bianchi@example.com";
    private static final String EMAIL_ADMIN = "luca.verdi@example.com";

    /** Un mercoledi' qualunque, scelto una volta: e' l'"oggi" di tutti i test di questa classe. */
    private static final LocalDate OGGI = LocalDate.of(2026, 9, 2);

    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private UtenteRepository utenteRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private OspiteRepository ospiteRepository;
    @Mock
    private PeriodoTariffarioRepository periodoTariffarioRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private PrenotazioneServiceImpl prenotazioneService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializza() {
        dati = new TestDataFactory();

        // Mapper veri: la conversione fra i due StatoPrenotazione e i due
        // CanalePrenotazione e' logica, e con dei finti non verrebbe esercitata.
        TipologiaCameraMapper tipologiaCameraMapper = new TipologiaCameraMapper(new DotazioneMapper());
        PrenotazioneMapper prenotazioneMapper = new PrenotazioneMapper(
                new UtenteMapper(), tipologiaCameraMapper, new StaffMapper(),
                new CameraMapper(tipologiaCameraMapper));

        // ChiamanteCorrente vero e non finto, per lo stesso motivo dei mapper: legge
        // il contesto che questi test riempiono a mano, e con un finto la regola che
        // pretende ruolo e tipo insieme non verrebbe esercitata da nessuno di loro.
        prenotazioneService = new PrenotazioneServiceImpl(prenotazioneRepository, tipologiaCameraRepository,
                cameraRepository, utenteRepository, staffRepository, ospiteRepository,
                periodoTariffarioRepository, prenotazioneMapper, apiResponseMapper, new ChiamanteCorrente(),
                new OrologioPilotato(OGGI.atStartOfDay().toInstant(ZoneOffset.UTC)));

        // Il preventivo di default: tre notti a 120, nessun soggiorno minimo. E'
        // lenient perche' la maggior parte dei test di creazione non ci arriva nemmeno
        // — si fermano prima su intestatario, canale o capienza — e senza questo ognuno
        // di loro dovrebbe dichiarare un mondo delle tariffe che non gli interessa.
        // Chi lo mette in discussione lo riscrive con preventivo(...).
        lenient().when(periodoTariffarioRepository.preventivoDi(eq(ID_TIPOLOGIA), any(LocalDate.class),
                any(LocalDate.class))).thenReturn(preventivo("360.00", 1));
    }

    @AfterEach
    void svuotaContesto() {
        SecurityContextHolder.clearContext();
    }

    /** Mette nel contesto un cliente autenticato: e' il caso normale di quasi tutti i test. */
    private void autenticaCliente() {
        autentica(TipoAccount.CLIENTE, ID_CLIENTE, EMAIL_CLIENTE, "USER");
    }

    /** Mette nel contesto un membro del personale. */
    private void autenticaStaff() {
        autentica(TipoAccount.PERSONALE, ID_STAFF, EMAIL_STAFF, "STAFF");
    }

    /**
     * Mette nel contesto un amministratore.
     *
     * <p>Non e' un doppione di {@link #autenticaStaff()}, ma il motivo e'
     * cambiato il 2026-08-28 e vale la pena riscriverlo invece di lasciarlo
     * dire una cosa non piu' vera. Diceva che senza un test con ADMIN la meta'
     * ADMIN del {@code ||} dentro {@code personale()} non si sarebbe mai vista
     * agire: adesso quella condizione ha i suoi test in
     * {@code ChiamanteCorrenteTest}, che la guardano da vicino. Quello che
     * questi due casi continuano a provare e' un'altra cosa — che la frase
     * dello spec <i>"STAFF e ADMIN vedono tutte"</i> vale per <b>le
     * prenotazioni</b>, cioe' che l'elenco chiede davvero quella risposta a
     * quella classe invece di ricavarsela da se'.
     */
    private void autenticaAdmin() {
        autentica(TipoAccount.PERSONALE, 1L, EMAIL_ADMIN, "ADMIN");
    }

    private void autentica(TipoAccount tipo, Long id, String email, String ruolo) {
        AppUserPrincipal principal =
                new AppUserPrincipal(tipo, id, email, "hash", "Mario", "Rossi", ruolo, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Utente cliente() {
        return cliente(true);
    }

    /**
     * Il cliente a cui si intesta la prenotazione. L'attivazione e' un parametro
     * perche' e' l'unica cosa che cambia fra il caso normale e il rifiuto: da
     * quando l'intestazione la guarda, un cliente costruito senza dirlo sarebbe
     * disattivato per svista e farebbe fallire i test che non parlano di questo.
     */
    private Utente cliente(boolean attivo) {
        Ruolo ruolo = new Ruolo();
        ruolo.setNome("USER");

        Utente utente = new Utente();
        utente.setId(ID_CLIENTE);
        utente.setNome("Mario");
        utente.setCognome("Rossi");
        utente.setEmail(EMAIL_CLIENTE);
        utente.setAttivo(attivo);
        utente.setRuolo(ruolo);
        return utente;
    }

    private Staff staff() {
        Staff staff = new Staff();
        staff.setId(ID_STAFF);
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        staff.setEmail(EMAIL_STAFF);
        return staff;
    }

    private TipologiaCamera tipologia() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia Superior");
        tipologia.setCapienzaMax(2);
        tipologia.setPrezzoNotte(new BigDecimal("120.00"));
        return tipologia;
    }

    private Prenotazione prenotazioneEsistente(StatoPrenotazione stato) {
        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(ID_PRENOTAZIONE);
        prenotazione.setUtente(cliente());
        prenotazione.setTipologiaCamera(tipologia());
        prenotazione.setDataCheckIn(OGGI.plusDays(7));
        prenotazione.setDataCheckOut(OGGI.plusDays(10));
        prenotazione.setNumeroOspiti(2);
        prenotazione.setStato(stato);
        prenotazione.setCanale(com.felixhotel.backend.entity.CanalePrenotazione.ONLINE);
        prenotazione.setImportoTotale(new BigDecimal("360.00"));
        return prenotazione;
    }

    /**
     * Una prenotazione il cui soggiorno comincia <b>oggi</b>, che e' la
     * condizione normale di un check-in.
     *
     * <p>{@link #prenotazioneEsistente} arriva fra una settimana, ed e' giusto
     * cosi' per creazione, conferma e annullamento — li' il futuro e' il caso
     * valido. Per il check-in il futuro e' il caso che va rifiutato, quindi la
     * base di partenza dev'essere un'altra.
     */
    private Prenotazione prenotazioneDiOggi(StatoPrenotazione stato) {
        Prenotazione prenotazione = prenotazioneEsistente(stato);
        prenotazione.setDataCheckIn(OGGI);
        prenotazione.setDataCheckOut(OGGI.plusDays(3));
        return prenotazione;
    }

    /** Una camera libera della tipologia prenotata. */
    private Camera camera(Long id, String numero) {
        return camera(id, numero, tipologia());
    }

    private Camera camera(Long id, String numero, TipologiaCamera tipologiaCamera) {
        Camera camera = new Camera();
        camera.setId(id);
        camera.setNumero(numero);
        camera.setPiano(1);
        camera.setStato(StatoCamera.LIBERA);
        camera.setTipologiaCamera(tipologiaCamera);
        return camera;
    }

    /** Richiesta valida, con le date ancorate all'orologio pilotato invece che a quello vero. */
    private PrenotazioneRequest richiestaValida() {
        return dati.prenotazioneRequest(ID_TIPOLOGIA)
                .dataCheckIn(OGGI.plusDays(7))
                .dataCheckOut(OGGI.plusDays(10));
    }

    /**
     * La tipologia richiesta esiste.
     *
     * <p>Serve a quasi ogni test di creazione, e non per completezza: la
     * tipologia si risolve <b>prima</b> dei controlli sull'intestatario e sul
     * canale, quindi senza questo stub il finto repository risponde "non esiste"
     * e il service solleva un BadRequestException per la tipologia. Che e' la
     * stessa eccezione che quei test si aspettano — cioe' passerebbero senza
     * arrivare mai dove volevano guardare.
     */
    private void tipologiaEsiste() {
        when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
    }

    /**
     * Dice al finto repository che di quella tipologia ci sono {@code camere}
     * stanze e che {@code occupate} sono impegnate nella notte peggiore del
     * periodo.
     *
     * <p><b>"Nella notte peggiore" non e' un dettaglio di formulazione</b>: e'
     * quello che il repository ora restituisce, e la differenza rispetto al
     * "quante prenotazioni toccano il periodo" di prima e' il difetto che questo
     * branch e' venuto a correggere. Qui il numero e' finto, quindi la
     * distinzione non si vede: a vederla e' l'integrazione, che quel massimo lo
     * fa calcolare davvero al database.
     */
    /**
     * Cosa risponde la query delle tariffe: quanto costa il soggiorno e quante
     * notti pretende come minimo.
     *
     * <p>Qui il numero e' finto, ed e' giusto che lo sia: la somma delle notti
     * la fa il database, e a provarla e' {@code PrenotazioneApiIT}. Quel che si
     * prova qui e' cosa il Service ne fa — lo fotografa, e rifiuta il soggiorno
     * piu' corto del minimo.
     */
    private PreventivoTipologia preventivo(String importo, int soggiornoMinimo) {
        return new PreventivoTipologia() {
            @Override
            public Long getTipologiaCameraId() {
                return ID_TIPOLOGIA;
            }

            @Override
            public BigDecimal getImportoTotale() {
                return new BigDecimal(importo);
            }

            @Override
            public int getSoggiornoMinimo() {
                return soggiornoMinimo;
            }
        };
    }

    /** Sostituisce il preventivo di default con uno preciso. */
    private void preventivoDice(String importo, int soggiornoMinimo) {
        when(periodoTariffarioRepository.preventivoDi(eq(ID_TIPOLOGIA), any(LocalDate.class),
                any(LocalDate.class))).thenReturn(preventivo(importo, soggiornoMinimo));
    }

    private void disponibilita(long camere, long occupate) {
        when(cameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(camere);
        when(prenotazioneRepository.occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(LocalDate.class),
                any(LocalDate.class), any(Collection.class), any())).thenReturn(occupate);
    }

    /**
     * Quali camere il finto repository considera assegnabili. Senza argomenti
     * vuol dire nessuna, che e' il caso del 409 al banco.
     *
     * <p>Qui il filtro e' finto e la distinzione fra "assegnabile" e "esiste" non
     * si vede: a vederla e' l'integrazione, dove stato operativo e
     * sovrapposizioni li valuta davvero il database.
     */
    private void assegnabili(Camera... camere) {
        when(cameraRepository.trovaAssegnabili(eq(ID_TIPOLOGIA), eq(StatoCamera.LIBERA),
                eq(StatoPrenotazione.CHECK_IN), any(LocalDate.class), any(LocalDate.class),
                any(Limit.class)))
                .thenReturn(List.of(camere));
    }

    /** La camera indicata non e' impegnata da nessun'altra prenotazione nel periodo. */
    private void liberaNelPeriodo() {
        when(prenotazioneRepository.esisteSovrapposizioneSuCamera(eq(ID_CAMERA),
                eq(StatoPrenotazione.CHECK_IN), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(false);
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("da un cliente intesta a lui, mette ONLINE e fotografa il totale del preventivo")
        void crea_daCliente_intestaAChiChiamaECalcolaTotale() {
            // given: un cliente autenticato, una camera libera su una sola esistente
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA));

            // when: la richiesta non nomina ne' utenteId ne' canale, come deve
            prenotazioneService.crea(richiestaValida());

            // then: intestata a chi ha il token, ONLINE per costruzione, nessun gestore.
            // Il totale e' quello che la query delle tariffe ha sommato: dal 2026-09-01
            // questa classe non lo calcola piu', lo fotografa. A provare che la somma sia
            // giusta — notte per notte, con i periodi e i giorni della settimana — e'
            // PrenotazioneApiIT, dove a farla e' il database vero
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());

            assertThat(salvata.getValue().getUtente().getId()).isEqualTo(ID_CLIENTE);
            assertThat(salvata.getValue().getCanale())
                    .isEqualTo(com.felixhotel.backend.entity.CanalePrenotazione.ONLINE);
            assertThat(salvata.getValue().getGestitaDaStaff()).isNull();
            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.IN_ATTESA);
            assertThat(salvata.getValue().getImportoTotale()).isEqualByComparingTo("360.00");

            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("da un cliente che indica utenteId risponde 400 invece di ignorarlo")
        void crea_daClienteConUtenteId_sollevaBadRequest() {
            // given: un cliente che prova a intestare la prenotazione a un altro
            autenticaCliente();
            tipologiaEsiste();

            // when/then: 400. Ignorare il campo in silenzio direbbe a chi ci ha provato
            // che ha funzionato, ed e' esattamente il tipo di cosa che si scopre tardi
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida().utenteId(99L)))
                    .isInstanceOf(BadRequestException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("da un cliente che indica il canale risponde 400")
        void crea_daClienteConCanale_sollevaBadRequest() {
            // given: un cliente che prova a dichiararsi arrivato per telefono
            autenticaCliente();
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));

            // when/then: il canale lo determina chi registra, non chi prenota
            assertThatThrownBy(() -> prenotazioneService.crea(
                    richiestaValida().canale(CanalePrenotazione.TELEFONO)))
                    .isInstanceOf(BadRequestException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("dal personale intesta al cliente indicato e registra chi l'ha presa")
        void crea_daPersonale_intestaAlClienteERegistraIlGestore() {
            // given: uno staff che registra una prenotazione telefonica
            autenticaStaff();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(staffRepository.findById(ID_STAFF)).thenReturn(Optional.of(staff()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA));

            // when
            prenotazioneService.crea(richiestaValida()
                    .utenteId(ID_CLIENTE)
                    .canale(CanalePrenotazione.TELEFONO));

            // then: due persone diverse sulla stessa riga — l'intestatario e chi l'ha
            // registrata. E' il motivo per cui gestitaDaStaff esiste
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());

            assertThat(salvata.getValue().getUtente().getId()).isEqualTo(ID_CLIENTE);
            assertThat(salvata.getValue().getGestitaDaStaff().getId()).isEqualTo(ID_STAFF);
            assertThat(salvata.getValue().getCanale())
                    .isEqualTo(com.felixhotel.backend.entity.CanalePrenotazione.TELEFONO);
        }

        @Test
        @DisplayName("dal personale senza utenteId risponde 400")
        void crea_daPersonaleSenzaUtenteId_sollevaBadRequest() {
            // given: uno staff che dimentica di dire per chi sta prenotando
            autenticaStaff();
            tipologiaEsiste();

            // when/then: senza intestatario la prenotazione non e' di nessuno
            assertThatThrownBy(() -> prenotazioneService.crea(
                    richiestaValida().canale(CanalePrenotazione.WALK_IN)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("dal personale senza canale risponde 400")
        void crea_daPersonaleSenzaCanale_sollevaBadRequest() {
            // given: uno staff che dice per chi ma non da dove
            autenticaStaff();
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));

            // when/then: il canale e' l'unica cosa che quel campo serve a sapere
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida().utenteId(ID_CLIENTE)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("dal personale con un cliente inesistente risponde 400 e non 404")
        void crea_daPersonaleConClienteInesistente_sollevaBadRequest() {
            // given: l'id del cliente non corrisponde a niente
            autenticaStaff();
            tipologiaEsiste();
            when(utenteRepository.findById(99L)).thenReturn(Optional.empty());

            // when/then: 400 e non 404, come per la tipologia: il 404 di questi endpoint
            // significa "questa prenotazione non esiste"
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .utenteId(99L)
                    .canale(CanalePrenotazione.AGENZIA)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("da un ruolo di personale che non sta nella tabella staff non ottiene i privilegi del personale")
        void crea_daRuoloDiPersonaleSuAccountCliente_nonOttieneIPrivilegi() {
            // given: un account che vive nella tabella dei clienti e porta il ruolo STAFF —
            // cioe' un cliente a cui qualcuno ha cambiato il ruolo scrivendo a mano nel
            // database. E' il caso in cui ruolo e tipo dell'account non combaciano
            autentica(TipoAccount.CLIENTE, ID_CLIENTE, EMAIL_CLIENTE, "STAFF");
            tipologiaEsiste();

            // when/then: dal 2026-08-27 il ruolo da solo non basta piu' — personale()
            // pretende anche il tipo dell'account — quindi questo resta un cliente, e a un
            // cliente e' vietato intestare a un altro. Il 400 e' lo stesso di prima ma per
            // una ragione diversa, e la differenza si vede da quel che NON succede: non si
            // legge nessuna delle due tabelle, perche' non c'e' niente da risolvere
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .utenteId(ID_CLIENTE)
                    .canale(CanalePrenotazione.TELEFONO)))
                    .isInstanceOf(BadRequestException.class);

            verify(staffRepository, never()).findById(anyLong());
            verify(utenteRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("dal personale con un cliente disattivato risponde 400")
        void crea_daPersonaleConClienteDisattivato_sollevaBadRequest() {
            // given: uno staff che intesta la prenotazione a un account chiuso
            autenticaStaff();
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente(false)));

            // when/then: quello che c'e' gia' resta, di nuovo non si aggiunge niente. E' un
            // 400 e non un 409: la richiesta nomina un account a cui non si puo' intestare
            // niente, come se avesse nominato un id inesistente
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .utenteId(ID_CLIENTE)
                    .canale(CanalePrenotazione.TELEFONO)))
                    .isInstanceOf(BadRequestException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("dal personale con la riga di staff sparita risponde 401")
        void crea_daPersonaleConRigaSparita_sollevaUnauthorized() {
            // given: il tipo dell'account e' giusto — sta davvero nella tabella staff — ma
            // quella riga nel frattempo non c'e' piu'
            autenticaStaff();
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(staffRepository.findById(ID_STAFF)).thenReturn(Optional.empty());

            // when/then: 401 e non 404 e non 400. Non c'e' niente di sbagliato nella
            // richiesta ne' nell'account nominato: e' il token di chi chiama a valere per
            // una riga che non esiste piu'. Dal 2026-08-27 e' l'unico esito negativo che
            // staffChiamante puo' produrre — il tipo dell'account lo garantisce
            // personale(), a monte
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .utenteId(ID_CLIENTE)
                    .canale(CanalePrenotazione.TELEFONO)))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("con tipologia inesistente risponde 400")
        void crea_conTipologiaInesistente_sollevaBadRequest() {
            // given
            autenticaCliente();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("con partenza non successiva all'arrivo risponde 400 senza arrivare al database")
        void crea_conDateInvertite_sollevaBadRequest() {
            // given: stesso giorno di arrivo e partenza, cioe' zero notti
            autenticaCliente();

            // when/then: il CHECK in database c'e' comunque, ma lasciarlo intervenire
            // trasformerebbe un errore di chi chiama in un 500 nostro
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .dataCheckOut(OGGI.plusDays(7))))
                    .isInstanceOf(BadRequestException.class);

            verify(cameraRepository, never()).countByTipologiaCameraId(anyLong());
        }

        @Test
        @DisplayName("con arrivo gia' passato risponde 400")
        void crea_conArrivoNelPassato_sollevaBadRequest() {
            // given: un arrivo di ieri, dove "ieri" lo decide l'orologio pilotato
            autenticaCliente();

            // when/then
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .dataCheckIn(OGGI.minusDays(1))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("con arrivo oggi non e' nel passato e passa")
        void crea_conArrivoOggi_nonSollevaNiente() {
            // given: si prenota per stanotte, che e' quel che fa un walk-in
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA));

            // when
            prenotazioneService.crea(richiestaValida()
                    .dataCheckIn(OGGI)
                    .dataCheckOut(OGGI.plusDays(1)));

            // then: il confine e' incluso. E' il test che distingue isBefore da
            // isEqual-or-before, cioe' l'errore che chiuderebbe fuori chi arriva oggi
            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }

        @Test
        @DisplayName("con piu' ospiti della capienza risponde 400")
        void crea_conTroppiOspiti_sollevaBadRequest() {
            // given: una doppia da due posti e tre persone
            autenticaCliente();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));

            // when/then: il tetto dipende dalla tipologia scelta, quindi non e'
            // esprimibile nello schema e deve stare qui
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida().numeroOspiti(3)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("senza camere libere per il periodo risponde 409")
        void crea_senzaDisponibilita_sollevaConflict() {
            // given: due camere, due gia' impegnate in quel periodo
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
            disponibilita(2, 2);

            // when/then: 409 e non 400 — la richiesta e' ben formata, e' lo stato del
            // mondo che non la permette
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(ConflictException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("su una tipologia senza nessuna camera risponde 409")
        void crea_conTipologiaSenzaCamere_sollevaConflict() {
            // given: una tipologia a catalogo di cui non esiste nessuna stanza
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(tipologia()));
            disponibilita(0, 0);

            // when/then: per chi prenota e' la stessa risposta del tutto esaurito — non
            // c'e' posto — e distinguerle direbbe a un estraneo com'e' fatto l'albergo
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("con un totale che non entra nella colonna risponde 400 invece di far esplodere Postgres")
        void crea_conImportoFuoriScala_sollevaBadRequest() {
            // given: un preventivo che supera quanto entra in NUMERIC(10,2). Dal
            // 2026-09-01 non lo si ottiene piu' alzando il prezzo della tipologia — il
            // totale lo calcolano le tariffe — quindi il caso si costruisce dicendo al
            // finto quanto ha sommato
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            tipologiaEsiste();
            preventivoDice("100000000.00", 1);
            disponibilita(1, 0);

            // when/then: il totale non lo manda il client, nasce da una somma, quindi
            // nessuna validazione dello schema puo' fermarlo prima di noi
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(BadRequestException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("un soggiorno piu' corto del minimo del periodo risponde 400 e non tocca il database")
        void crea_sottoIlSoggiornoMinimo_sollevaBadRequest() {
            // given: in quel periodo si vende da tre notti in su, e la richiesta ne
            // chiede tre... ma il minimo e' quattro
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            tipologiaEsiste();
            preventivoDice("480.00", 4);

            // when/then: 400 e non 409, come per la capienza — non c'e' nessuno stato
            // con cui la richiesta confligga, e' fuori da cio' che si puo' chiedere
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("4");

            // then: il controllo viene prima della disponibilita', perche' una richiesta
            // fuori dalle regole di vendita va detta tale anche quando per giunta non
            // c'e' posto
            verify(cameraRepository, never()).countByTipologiaCameraId(any());
            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("il soggiorno minimo esatto passa: e' un minimo, non una soglia da superare")
        void crea_conIlSoggiornoMinimoEsatto_creaLaPrenotazione() {
            // given: tre notti richieste, tre notti di minimo
            autenticaCliente();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            tipologiaEsiste();
            preventivoDice("540.00", 3);
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA));

            // when
            prenotazioneService.crea(richiestaValida());

            // then: creata, e col totale che il preventivo ha calcolato
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());
            assertThat(salvata.getValue().getImportoTotale()).isEqualByComparingTo("540.00");
        }

        @Test
        @DisplayName("un soggiorno oltre il tetto di notti risponde 400 senza chiedere niente alle tariffe")
        void crea_oltreIlTettoDiNotti_sollevaBadRequest() {
            // given: una notte piu' del massimo
            autenticaCliente();
            PrenotazioneRequest richiesta = richiestaValida();
            richiesta.setDataCheckOut(richiesta.getDataCheckIn().plusDays(DurataSoggiorno.MASSIMO_NOTTI + 1));

            // when/then: lo stesso rifiuto che darebbe la ricerca, con lo stesso numero
            assertThatThrownBy(() -> prenotazioneService.crea(richiesta))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(String.valueOf(DurataSoggiorno.MASSIMO_NOTTI));

            // then: il controllo sta fra quelli sulle date, quindi prima di ogni lettura
            verifyNoInteractions(periodoTariffarioRepository, cameraRepository);
        }

        @Test
        @DisplayName("senza nessuno autenticato risponde 401")
        void crea_senzaAutenticazione_sollevaUnauthorized() {
            // given: contesto vuoto, come se il filtro non avesse messo niente

            // when/then: 401 e non una ClassCastException, che sarebbe un 500
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("con il principal anonimo risponde 401 e non esplode")
        void crea_conPrincipalAnonimo_sollevaUnauthorized() {
            // given: quel che Spring Security mette nel contesto quando la richiesta non
            // porta nessun token — un'autenticazione c'e', ma il suo principal e' la
            // stringa "anonymousUser" e non un AppUserPrincipal. Non e' lo stesso caso
            // del contesto vuoto qui sopra: li' non c'era niente, qui c'e' la cosa
            // sbagliata
            SecurityContextHolder.getContext().setAuthentication(
                    new AnonymousAuthenticationToken("chiave", "anonymousUser",
                            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

            // when/then: 401. Senza l'instanceof che fa da guardia sarebbe una
            // ClassCastException, cioe' un 500 che da' la colpa a noi di una richiesta
            // arrivata senza token
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("con un token valido per un account sparito risponde 401")
        void crea_conAccountSparito_sollevaUnauthorized() {
            // given: il token e' buono ma la riga del cliente non c'e' piu'
            autenticaCliente();
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.empty());

            // when/then: 401 e non 404 — non manca la prenotazione, manca chi chiede
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("a un cliente restringe alle proprie e ordina per arrivo decrescente")
        void elenca_daCliente_restringeAlleProprie() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.cerca(eq(ID_CLIENTE), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA))));

            // when: il cliente non ha chiesto niente di particolare
            prenotazioneService.elenca(0, 20, null);

            // then: l'id arriva comunque alla query. Non e' un filtro che il client ha
            // scelto: e' il recinto, e un filtro che si puo' scegliere e' un filtro che
            // si puo' non mandare.
            // L'ordine ha due criteri e non uno: la data di arrivo non e' unica — in un
            // albergo pieno decine di prenotazioni cominciano lo stesso giorno — quindi
            // senza l'id a spareggiare la stessa riga potrebbe comparire in due pagine
            // o in nessuna
            verify(prenotazioneRepository).cerca(ID_CLIENTE, null,
                    PageRequest.of(0, 20, Sort.by(Sort.Order.desc("dataCheckIn"), Sort.Order.desc("id"))));
        }

        @Test
        @DisplayName("a un account del personale con ruolo USER risponde 401 invece delle prenotazioni di un altro")
        void elenca_daPersonaleConRuoloUtente_sollevaUnauthorized() {
            // given: un account che sta nella tabella staff ma porta il ruolo USER — di
            // nuovo ruolo e tipo che non combaciano, stavolta nel verso opposto. Il suo id
            // vale su 'staff', e lo stesso numero su 'utente' e' un cliente che non ha
            // niente a che vedere con lui
            autentica(TipoAccount.PERSONALE, ID_STAFF, EMAIL_STAFF, "USER");

            // when/then: 401. E' il motivo per cui il tipo va guardato e non solo letto:
            // senza il controllo, questo id finirebbe nel filtro della query come se
            // fosse quello di un cliente, e la risposta conterrebbe le prenotazioni di
            // un utente che non ha niente a che vedere con chi ha chiamato
            assertThatThrownBy(() -> prenotazioneService.elenca(0, 20, null))
                    .isInstanceOf(UnauthorizedException.class);

            verifyNoInteractions(prenotazioneRepository);
        }

        @Test
        @DisplayName("a un cliente con ruolo di personale restringe comunque alle proprie")
        void elenca_daClienteConRuoloDiPersonale_restringeAlleProprie() {
            // given: una riga di 'utente' a cui qualcuno ha messo a mano il ruolo ADMIN.
            // Fino al 2026-08-27 questo account leggeva TUTTE le prenotazioni, perche'
            // personale() guardava il solo ruolo: e' il buco che quel giorno si e' chiuso
            autentica(TipoAccount.CLIENTE, ID_CLIENTE, EMAIL_CLIENTE, "ADMIN");
            when(prenotazioneRepository.cerca(eq(ID_CLIENTE), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when
            prenotazioneService.elenca(0, 20, null);

            // then: il suo id arriva alla query come per qualunque altro cliente. Il ruolo
            // da solo non apre piu' niente — servono il privilegio e il tipo di account che
            // gli corrisponde, e chi ne ha uno solo resta quel che la sua tabella dice
            verify(prenotazioneRepository).cerca(eq(ID_CLIENTE), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("al personale passa null come utente, cioe' tutte")
        void elenca_daPersonale_nonRestringe() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.cerca(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when
            prenotazioneService.elenca(0, 20, null);

            // then: null qui e' un privilegio, non l'assenza di una preferenza. E il
            // personale non passa nemmeno da utenteRepository: non gli serve sapere chi e'
            verify(prenotazioneRepository).cerca(isNull(), isNull(), any(Pageable.class));
            verifyNoInteractions(utenteRepository);
        }

        @Test
        @DisplayName("con lo stato filtrato lo traduce nell'enum di dominio")
        void elenca_conStato_traduceEnum() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.cerca(isNull(), eq(StatoPrenotazione.ANNULLATA), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when
            prenotazioneService.elenca(0, 20, com.felixhotel.backend.dto.StatoPrenotazione.ANNULLATA);

            // then: al repository arriva l'enum di dominio, non quello dello spec
            verify(prenotazioneRepository).cerca(null, StatoPrenotazione.ANNULLATA,
                    PageRequest.of(0, 20, Sort.by(Sort.Order.desc("dataCheckIn"), Sort.Order.desc("id"))));
        }
    }

    @Nested
    @DisplayName("dettaglio")
    class Dettaglio {

        @Test
        @DisplayName("al titolare restituisce la propria prenotazione")
        void dettaglio_alTitolare_rispondeOk() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when
            prenotazioneService.dettaglio(ID_PRENOTAZIONE);

            // then
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }

        @Test
        @DisplayName("a un cliente che non e' il titolare risponde 404 e non 403")
        void dettaglio_aUnEstraneo_sollevaNotFound() {
            // given: la prenotazione esiste, ma e' di qualcun altro. L'id del cliente si
            // legge dal principal, quindi basta autenticarne uno con un id diverso: non
            // c'e' nessuna riga da preparare
            autentica(TipoAccount.CLIENTE, 99L, "altro.cliente@example.com", "USER");
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when/then: 404, la stessa risposta di un id inventato. Un 403 direbbe
            // "esiste ma non e' tua", cioe' lascerebbe scoprire quali id esistono
            // provandoli uno per uno
            assertThatThrownBy(() -> prenotazioneService.dettaglio(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("al personale restituisce anche la prenotazione di un altro")
        void dettaglio_alPersonale_rispondeOk() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when
            prenotazioneService.dettaglio(ID_PRENOTAZIONE);

            // then
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }

        @Test
        @DisplayName("a un amministratore restituisce anche la prenotazione di un altro")
        void dettaglio_allAmministratore_rispondeOk() {
            // given: un ADMIN, che non e' il titolare della prenotazione
            autenticaAdmin();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when
            prenotazioneService.dettaglio(ID_PRENOTAZIONE);

            // then: passa senza che nessuno gli chieda di chi sia. Il test accanto prova
            // la stessa cosa per lo STAFF, e non e' una ripetizione: sono i due rami
            // dello stesso ||, e quello dell'ADMIN non era coperto da niente
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
            verifyNoInteractions(utenteRepository);
        }

        @Test
        @DisplayName("con id inesistente risponde 404")
        void dettaglio_conIdInesistente_sollevaNotFound() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> prenotazioneService.dettaglio(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("conferma")
    class Conferma {

        @Test
        @DisplayName("da IN_ATTESA con posto passa a CONFERMATA")
        void conferma_conPosto_passaAConfermata() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA)));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.CONFERMATA));

            // when
            prenotazioneService.conferma(ID_PRENOTAZIONE);

            // then
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());

            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.CONFERMATA);
        }

        @Test
        @DisplayName("esclude se stessa dal conteggio delle sovrapposte")
        void conferma_escludeSeStessaDalConteggio() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA)));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.CONFERMATA));

            // when
            prenotazioneService.conferma(ID_PRENOTAZIONE);

            // then: il proprio id arriva alla query come esclusione. Oggi non
            // cambierebbe niente — una IN_ATTESA non occupa comunque — ma e' cio' che
            // rende il metodo indifferente allo stato di partenza, invece di dipendere
            // da un dettaglio che chi aggiungera' altre transizioni non conosce
            verify(prenotazioneRepository).occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(LocalDate.class),
                    any(LocalDate.class), any(Collection.class), eq(ID_PRENOTAZIONE));
        }

        @Test
        @DisplayName("se nel frattempo il posto e' finito risponde 409 e lascia IN_ATTESA")
        void conferma_senzaPosto_sollevaConflictSenzaToccare() {
            // given: una camera sola, gia' presa da un altro fra la creazione e adesso
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA)));
            disponibilita(1, 1);

            // when/then: 409, e soprattutto niente save — la prenotazione resta
            // IN_ATTESA e il cliente puo' ancora cambiarle le date. E' questo doppio
            // controllo che sostituisce la scadenza sul carrello
            assertThatThrownBy(() -> prenotazioneService.conferma(ID_PRENOTAZIONE))
                    .isInstanceOf(ConflictException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("su un carrello il cui arrivo e' ormai passato risponde 409")
        void conferma_conArrivoOrmaiPassato_sollevaConflict() {
            // given: una prenotazione creata quando l'arrivo era futuro e rimasta nel
            // carrello finche' quel giorno non e' passato. Il carrello non scade, quindi
            // questa situazione non e' un caso limite: e' quello che succede da solo
            autenticaCliente();
            Prenotazione dimenticata = prenotazioneEsistente(StatoPrenotazione.IN_ATTESA);
            dimenticata.setDataCheckIn(OGGI.minusDays(2));
            dimenticata.setDataCheckOut(OGGI.plusDays(1));
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(dimenticata));

            // when/then: 409, e nessun addebito per un soggiorno gia' cominciato. E' il
            // controllo che la creazione aveva gia' fatto e che va rifatto qui, perche'
            // allora quell'arrivo era futuro
            assertThatThrownBy(() -> prenotazioneService.conferma(ID_PRENOTAZIONE))
                    .isInstanceOf(ConflictException.class);

            // then: e il 409 arriva DA QUI, non dalla disponibilita'. La verifica non e'
            // un di piu': senza stub il conteggio finto vale zero, quindi la
            // disponibilita' solleverebbe lo stesso 409 e il test passerebbe anche
            // togliendo il controllo sulla data — cioe' non proverebbe niente. Visto
            // fallire per davvero prima di essere dichiarato buono (regola 22)
            verify(cameraRepository, never()).countByTipologiaCameraId(anyLong());
            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("con arrivo oggi si conferma ancora")
        void conferma_conArrivoOggi_passa() {
            // given: chi si presenta al banco conferma per stanotte
            autenticaStaff();
            Prenotazione perOggi = prenotazioneEsistente(StatoPrenotazione.IN_ATTESA);
            perOggi.setDataCheckIn(OGGI);
            perOggi.setDataCheckOut(OGGI.plusDays(1));
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(perOggi));
            disponibilita(1, 0);
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.CONFERMATA));

            // when
            prenotazioneService.conferma(ID_PRENOTAZIONE);

            // then: il confine e' incluso qui come lo e' in creazione — sono due
            // controlli diversi che devono rispondere allo stesso modo sullo stesso giorno
            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }

        @Test
        @DisplayName("su una gia' confermata risponde 409 e non e' idempotente")
        void conferma_suGiaConfermata_sollevaConflict() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when/then: al contrario del cambio di stato di una camera, qui ripetere
            // l'operazione non e' innocuo — la seconda chiamata chiederebbe un posto che
            // la prima ha gia' preso
            assertThatThrownBy(() -> prenotazioneService.conferma(ID_PRENOTAZIONE))
                    .isInstanceOf(ConflictException.class);

            verify(cameraRepository, never()).countByTipologiaCameraId(anyLong());
        }
    }

    @Nested
    @DisplayName("checkIn")
    class CheckIn {

        /**
         * Il registro degli ospiti e' completo, cioe' la condizione che il TULPS
         * pretende prima di dare la chiave.
         *
         * <p>Serve a quasi tutti i test di questa classe e non e' rumore da
         * preparazione: da questo branch il check-in ha <b>tre</b> cancelli prima
         * di arrivare alla camera — stato, calendario, registro — e quelli che
         * guardano il terzo devono poterlo oltrepassare per dire qualcosa sul
         * quarto. Prima che la regola esistesse questi nove test passavano senza
         * saperlo, ed e' stato il modo in cui la si e' vista mordere: aggiunta la
         * regola e non ancora toccati i test, sono diventati rossi tutti e nove
         * con il messaggio giusto.
         */
        private void ospitiTuttiRegistrati() {
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(2L);
        }

        @Test
        @DisplayName("senza nessun ospite registrato risponde 409")
        void checkIn_senzaOspitiRegistrati_sollevaConflict() {
            // given: la prenotazione e' confermata e il giorno e' quello giusto, ma al
            // banco non e' stato preso nessun documento
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(0L);

            // when/then: 409, e soprattutto **prima** di toccare le camere. Il TULPS
            // vuole il documento acquisito all'atto dell'arrivo: se la chiave si desse
            // comunque, "prima" diventerebbe "quando ci si ricorda"
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("0 ospiti su 2");

            verifyNoInteractions(cameraRepository);
            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("con un ospite su due registrati risponde 409")
        void checkIn_conRegistroIncompleto_sollevaConflict() {
            // given: e' stato preso il documento di chi ha prenotato, non quello di chi
            // viaggia con lui. E' il caso che la regola esiste per prendere: quello in
            // cui sembra fatto
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(1L);

            // when/then: la legge vuole il documento di ogni persona che soggiorna, non
            // di una. E' il motivo per cui la condizione e' un'uguaglianza
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("1 ospiti su 2");
        }

        @Test
        @DisplayName("con piu' ospiti registrati di quanti ne prevede la prenotazione risponde 409, e lo dice")
        void checkIn_conRegistroInEccesso_sollevaConflict() {
            // given: tre registrati su due posti. Dagli endpoint non e' raggiungibile —
            // la POST rifiuta l'ospite oltre numeroOspiti — ma due registrazioni
            // simultanee sull'ultimo posto ci arrivano, ed e' un limite scritto
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(3L);

            // when/then: il messaggio dice di **togliere** quelli di troppo. Con un
            // solo ramo "ne mancano N" chi sta al banco leggerebbe "ne mancano -1" e
            // andrebbe a cercare una persona che non esiste
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("vanno tolti quelli di troppo");
        }

        @Test
        @DisplayName("assegna la prima camera libera, la porta a OCCUPATA e passa a CHECK_IN")
        void checkIn_conCameraLibera_assegnaEOccupa() {
            // given: uno staff al banco, una prenotazione confermata che comincia oggi
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            assegnabili(camera(ID_CAMERA, "101"));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN));

            // when: nessun corpo, cioe' "scegli tu"
            prenotazioneService.checkIn(ID_PRENOTAZIONE, null);

            // then: due scritture, e nessuna delle due si puo' omettere — la
            // prenotazione senza la camera direbbe che l'ospite e' entrato in nessun
            // posto, la camera senza lo stato lascerebbe la stanza assegnabile a un altro
            ArgumentCaptor<Camera> cameraSalvata = ArgumentCaptor.forClass(Camera.class);
            verify(cameraRepository).save(cameraSalvata.capture());
            assertThat(cameraSalvata.getValue().getStato()).isEqualTo(StatoCamera.OCCUPATA);

            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());
            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.CHECK_IN);
            assertThat(salvata.getValue().getCamera()).isNotNull();
            assertThat(salvata.getValue().getCamera().getNumero()).isEqualTo("101");
        }

        @Test
        @DisplayName("con l'ospite arrivato un giorno in ritardo passa lo stesso")
        void checkIn_conArrivoInRitardo_passa() {
            // given: il soggiorno e' cominciato ieri e finisce fra due giorni
            autenticaStaff();
            ospitiTuttiRegistrati();
            Prenotazione inRitardo = prenotazioneDiOggi(StatoPrenotazione.CONFERMATA);
            inRitardo.setDataCheckIn(OGGI.minusDays(1));
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(inRitardo));
            assegnabili(camera(ID_CAMERA, "101"));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN));

            // when/then: succede davvero che qualcuno arrivi la sera dopo. Il confine e'
            // la partenza, non l'arrivo
            prenotazioneService.checkIn(ID_PRENOTAZIONE, null);

            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }

        @Test
        @DisplayName("prima del giorno di arrivo risponde 409")
        void checkIn_primaDellArrivo_sollevaConflict() {
            // given: la prenotazione base arriva fra una settimana
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));

            // when/then: registrarlo oggi metterebbe OCCUPATA una stanza per una
            // settimana, e quella stanza sparirebbe dall'assegnazione di tutti gli
            // arrivi veri
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);

            verifyNoInteractions(cameraRepository);
        }

        @Test
        @DisplayName("il giorno della partenza risponde 409: quella notte non e' prenotata")
        void checkIn_ilGiornoDellaPartenza_sollevaConflict() {
            // given: si presenta il giorno in cui avrebbe dovuto andarsene
            autenticaStaff();
            Prenotazione finita = prenotazioneDiOggi(StatoPrenotazione.CONFERMATA);
            finita.setDataCheckIn(OGGI.minusDays(3));
            finita.setDataCheckOut(OGGI);
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(finita));

            // when/then: e' lo stesso confine che rende la camera riassegnabile a chi
            // arriva oggi — chi parte il 13 non dorme la notte del 13
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("su una prenotazione non confermata risponde 409")
        void checkIn_suNonConfermata_sollevaConflict() {
            // given: un carrello, non una prenotazione
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.IN_ATTESA)));

            // when/then: prima si conferma, poi si arriva
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("senza nessuna camera assegnabile risponde 409")
        void checkIn_senzaCamereAssegnabili_sollevaConflict() {
            // given: la prenotazione e' regolare, ma nessuna stanza di quella tipologia
            // e' materialmente utilizzabile oggi
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            assegnabili();

            // when/then: non e' una contraddizione con la conferma, che aveva trovato
            // posto. Quella conta tutte le camere della tipologia, comprese quelle oggi
            // in manutenzione, perche' uno stato di oggi non dice niente su un soggiorno
            // futuro; qui la chiave va data su una stanza che esiste davvero
            assertThatThrownBy(() -> prenotazioneService.checkIn(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("con una camera indicata assegna quella, senza cercare fra le assegnabili")
        void checkIn_conCameraIndicata_assegnaQuella() {
            // given: chi sta al banco vuole la 203
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(ID_CAMERA, "203")));
            liberaNelPeriodo();
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN));

            // when
            prenotazioneService.checkIn(ID_PRENOTAZIONE, new PrenotazioneCheckInRequest().cameraId(ID_CAMERA));

            // then: la scelta e' sua e non si passa nemmeno dalla ricerca. Non e' un
            // dettaglio di efficienza: per un upgrade quella ricerca darebbe sempre zero
            // risultati, perche' cerca in un'altra tipologia
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());
            assertThat(salvata.getValue().getCamera().getNumero()).isEqualTo("203");
            verify(cameraRepository, never()).trovaAssegnabili(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("l'upgrade e' permesso: la camera puo' essere di un'altra tipologia, e l'importo non cambia")
        void checkIn_conCameraDiAltraTipologia_assegnaSenzaToccareImporto() {
            // given: la doppia comprata e una suite da dare al suo posto
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));

            TipologiaCamera suite = new TipologiaCamera();
            suite.setId(99L);
            suite.setNome("Suite");
            suite.setCapienzaMax(4);
            suite.setPrezzoNotte(new BigDecimal("400.00"));
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(ID_CAMERA, "500", suite)));
            liberaNelPeriodo();
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN));

            // when
            prenotazioneService.checkIn(ID_PRENOTAZIONE, new PrenotazioneCheckInRequest().cameraId(ID_CAMERA));

            // then: la camera e' la suite, ma tipologia e importo restano quelli
            // comprati. Sono le due cose che dicono cosa il cliente ha pagato, e un
            // upgrade che le riscrivesse cancellerebbe la vendita per registrare un
            // regalo
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());
            assertThat(salvata.getValue().getCamera().getTipologiaCamera().getNome()).isEqualTo("Suite");
            assertThat(salvata.getValue().getTipologiaCamera().getNome()).isEqualTo("Doppia Superior");
            assertThat(salvata.getValue().getImportoTotale()).isEqualByComparingTo("360.00");
        }

        @Test
        @DisplayName("con una camera indicata che non esiste risponde 400")
        void checkIn_conCameraInesistente_sollevaBadRequest() {
            // given
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.empty());

            // when/then: 400 e non 409 — un id che non esiste e' un errore della
            // richiesta, non uno stato del mondo che non permette l'operazione
            assertThatThrownBy(() -> prenotazioneService.checkIn(
                    ID_PRENOTAZIONE, new PrenotazioneCheckInRequest().cameraId(ID_CAMERA)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("con una camera indicata che non e' LIBERA risponde 409")
        void checkIn_conCameraNonLibera_sollevaConflict() {
            // given: la stanza voluta e' in manutenzione
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            Camera guasta = camera(ID_CAMERA, "203");
            guasta.setStato(StatoCamera.MANUTENZIONE);
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(guasta));

            // when/then: nemmeno un upgrade deciso a mano puo' mettere un ospite in una
            // stanza fuori servizio
            assertThatThrownBy(() -> prenotazioneService.checkIn(
                    ID_PRENOTAZIONE, new PrenotazioneCheckInRequest().cameraId(ID_CAMERA)))
                    .isInstanceOf(ConflictException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("con una camera LIBERA ma con dentro un ospite risponde 409")
        void checkIn_conCameraGiaImpegnata_sollevaConflict() {
            // given: la stanza risulta LIBERA — qualcuno l'ha rimessa cosi' a mano
            // sbagliando — ma c'e' una prenotazione in CHECK_IN su quelle notti
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(ID_CAMERA, "203")));
            when(prenotazioneRepository.esisteSovrapposizioneSuCamera(eq(ID_CAMERA),
                    eq(StatoPrenotazione.CHECK_IN), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(true);

            // when/then: lo stato operativo lo scrive anche una persona, e PUT
            // /api/camere/{id}/stato non ha nessuna macchina a stati che glielo impedisca.
            // Le prenotazioni in CHECK_IN le scrive solo l'applicazione: e' la fonte che
            // non si puo' sbagliare a mano, ed e' per questo che il controllo e' doppio
            assertThatThrownBy(() -> prenotazioneService.checkIn(
                    ID_PRENOTAZIONE, new PrenotazioneCheckInRequest().cameraId(ID_CAMERA)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("un corpo senza cameraId equivale a nessun corpo")
        void checkIn_conCorpoVuoto_scegliIlService() {
            // given: il client manda {} invece di niente
            autenticaStaff();
            ospitiTuttiRegistrati();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));
            assegnabili(camera(ID_CAMERA, "101"));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN));

            // when
            prenotazioneService.checkIn(ID_PRENOTAZIONE, new PrenotazioneCheckInRequest());

            // then: sono i due rami dello stesso ternario, e senza questo test quello
            // del corpo presente ma vuoto non lo esercita nessuno
            verify(cameraRepository, never()).findById(any());
            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }
    }

    @Nested
    @DisplayName("checkOut")
    class CheckOut {

        @Test
        @DisplayName("da CHECK_IN riporta la camera a LIBERA e passa a CHECK_OUT")
        void checkOut_daCheckIn_liberaLaCamera() {
            // given: l'ospite e' dentro la 101
            autenticaStaff();
            Prenotazione inCorso = prenotazioneDiOggi(StatoPrenotazione.CHECK_IN);
            Camera occupata = camera(ID_CAMERA, "101");
            occupata.setStato(StatoCamera.OCCUPATA);
            inCorso.setCamera(occupata);
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(inCorso));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_OUT));

            // when
            prenotazioneService.checkOut(ID_PRENOTAZIONE);

            // then
            ArgumentCaptor<Camera> cameraSalvata = ArgumentCaptor.forClass(Camera.class);
            verify(cameraRepository).save(cameraSalvata.capture());
            assertThat(cameraSalvata.getValue().getStato()).isEqualTo(StatoCamera.LIBERA);

            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());
            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.CHECK_OUT);

            // e la camera resta scritta: in quella stanza ci ha dormito qualcuno, ed e'
            // l'unico posto in cui quel fatto e' registrato
            assertThat(salvata.getValue().getCamera()).isNotNull();
        }

        @Test
        @DisplayName("una camera segnata MANUTENZIONE durante il soggiorno non torna LIBERA")
        void checkOut_conCameraInManutenzione_nonLaTocca() {
            // given: durante il soggiorno e' saltato il condizionatore e qualcuno ha
            // segnato la stanza
            autenticaStaff();
            Prenotazione inCorso = prenotazioneDiOggi(StatoPrenotazione.CHECK_IN);
            Camera guasta = camera(ID_CAMERA, "101");
            guasta.setStato(StatoCamera.MANUTENZIONE);
            inCorso.setCamera(guasta);
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(inCorso));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_OUT));

            // when
            prenotazioneService.checkOut(ID_PRENOTAZIONE);

            // then: il check-out avviene, ma la stanza resta fuori servizio. Rimetterla
            // LIBERA vorrebbe dire che la partenza di un ospite cancella in silenzio una
            // segnalazione tecnica
            verify(cameraRepository, never()).save(any());
            assertThat(guasta.getStato()).isEqualTo(StatoCamera.MANUTENZIONE);
            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }

        @Test
        @DisplayName("senza camera assegnata registra la partenza lo stesso invece di esplodere")
        void checkOut_senzaCamera_nonSollevaNiente() {
            // given: una riga in CHECK_IN senza camera. Dagli endpoint non ci si arriva
            // — il check-in la assegna sempre — ma una scrittura a mano nel database si'
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CHECK_IN)));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneDiOggi(StatoPrenotazione.CHECK_OUT));

            // when/then: senza la guardia sarebbe una NullPointerException, cioe' un 500
            // che da' la colpa a noi di un dato storto
            prenotazioneService.checkOut(ID_PRENOTAZIONE);

            verify(cameraRepository, never()).save(any());
            verify(prenotazioneRepository).save(any(Prenotazione.class));
        }

        @Test
        @DisplayName("su una prenotazione che non e' in CHECK_IN risponde 409")
        void checkOut_suNonInCheckIn_sollevaConflict() {
            // given: confermata ma l'ospite non e' mai arrivato
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneDiOggi(StatoPrenotazione.CONFERMATA)));

            // when/then: non si fa uscire chi non e' mai entrato
            assertThatThrownBy(() -> prenotazioneService.checkOut(ID_PRENOTAZIONE))
                    .isInstanceOf(ConflictException.class);

            verifyNoInteractions(cameraRepository);
        }
    }

    @Nested
    @DisplayName("annulla")
    class Annulla {

        @Test
        @DisplayName("da CONFERMATA registra stato, istante e motivo")
        void annulla_daConfermata_registraTutto() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CONFERMATA)));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.ANNULLATA));

            // when
            prenotazioneService.annulla(ID_PRENOTAZIONE, dati.annullamentoRequest("Cambio di programma"));

            // then: l'istante e' quello dell'orologio pilotato, quindi si puo' affermare
            // com'e' fatto invece di dire "piu' o meno adesso"
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());

            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.ANNULLATA);
            assertThat(salvata.getValue().getMotivoCancellazione()).isEqualTo("Cambio di programma");
            assertThat(salvata.getValue().getDataCancellazione()).isEqualTo(OGGI.atStartOfDay());
        }

        @Test
        @DisplayName("senza corpo annulla lo stesso e lascia il motivo vuoto")
        void annulla_senzaCorpo_lasciaIlMotivoNullo() {
            // given: il corpo e' facoltativo nello spec, quindi qui arriva null
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.IN_ATTESA)));
            when(prenotazioneRepository.save(any(Prenotazione.class)))
                    .thenReturn(prenotazioneEsistente(StatoPrenotazione.ANNULLATA));

            // when
            prenotazioneService.annulla(ID_PRENOTAZIONE, null);

            // then: chi annulla senza dire perche' non sta sbagliando niente
            ArgumentCaptor<Prenotazione> salvata = ArgumentCaptor.forClass(Prenotazione.class);
            verify(prenotazioneRepository).save(salvata.capture());

            assertThat(salvata.getValue().getStato()).isEqualTo(StatoPrenotazione.ANNULLATA);
            assertThat(salvata.getValue().getMotivoCancellazione()).isNull();
        }

        @Test
        @DisplayName("su una gia' annullata risponde 409")
        void annulla_suGiaAnnullata_sollevaConflict() {
            // given
            autenticaCliente();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.ANNULLATA)));

            // when/then
            assertThatThrownBy(() -> prenotazioneService.annulla(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);

            verify(prenotazioneRepository, never()).save(any());
        }

        @Test
        @DisplayName("su un soggiorno gia' cominciato risponde 409")
        void annulla_suSoggiornoCominciato_sollevaConflict() {
            // given: una prenotazione in CHECK_IN, stato che oggi nessun endpoint
            // produce ma che il database ammette e il check-in produrra'
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(prenotazioneEsistente(StatoPrenotazione.CHECK_IN)));

            // when/then: dopo il check-in non e' piu' un annullamento, e' un'altra cosa
            assertThatThrownBy(() -> prenotazioneService.annulla(ID_PRENOTAZIONE, null))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("stati che occupano una camera")
    class StatiCheOccupano {

        @Test
        @DisplayName("sono CONFERMATA, CHECK_IN e CHECK_OUT, e l'elenco e' uno solo")
        void statiCheOccupano_sonoITre() {
            // when/then: la query di disponibilita' riceve questo insieme come
            // parametro, perche' non puo' chiamare un metodo Java. Il test esiste per
            // fissare quali sono: sbagliarne uno vorrebbe dire vendere due volte la
            // stessa camera, oppure rifiutarne una libera
            assertThat(StatoPrenotazione.statiCheOccupano()).containsExactlyInAnyOrder(
                    StatoPrenotazione.CONFERMATA, StatoPrenotazione.CHECK_IN, StatoPrenotazione.CHECK_OUT);

            assertThat(StatoPrenotazione.IN_ATTESA.occupaCamera()).isFalse();
            assertThat(StatoPrenotazione.ANNULLATA.occupaCamera()).isFalse();
        }

        @Test
        @DisplayName("i nomi per la query nativa sono gli stessi tre, derivati e non riscritti")
        void nomiCheOccupano_seguonoIStati() {
            // when/then: la query e' nativa, quindi gli stati ci arrivano come stringhe
            // e non come enum. Il punto del test non e' che siano tre nomi: e' che
            // siano *quei* nomi, cioe' che il secondo elenco resti agganciato al primo.
            // Scritto a mano, il giorno che nasce un sesto stato sarebbero due posti da
            // aggiornare e uno solo che se ne accorge
            assertThat(StatoPrenotazione.nomiCheOccupano())
                    .containsExactlyInAnyOrderElementsOf(
                            StatoPrenotazione.statiCheOccupano().stream().map(Enum::name).toList());
        }
    }
}
