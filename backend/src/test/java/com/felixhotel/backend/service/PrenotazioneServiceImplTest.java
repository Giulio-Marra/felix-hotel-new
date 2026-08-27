package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.CanalePrenotazione;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.mapper.PrenotazioneMapper;
import com.felixhotel.backend.mapper.StaffMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.mapper.UtenteMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.repository.UtenteRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.TipoAccount;
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
    private ApiResponseMapper apiResponseMapper;

    private PrenotazioneServiceImpl prenotazioneService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializza() {
        dati = new TestDataFactory();

        // Mapper veri: la conversione fra i due StatoPrenotazione e i due
        // CanalePrenotazione e' logica, e con dei finti non verrebbe esercitata.
        PrenotazioneMapper prenotazioneMapper = new PrenotazioneMapper(
                new UtenteMapper(), new TipologiaCameraMapper(new DotazioneMapper()), new StaffMapper());

        prenotazioneService = new PrenotazioneServiceImpl(prenotazioneRepository, tipologiaCameraRepository,
                cameraRepository, utenteRepository, staffRepository, prenotazioneMapper, apiResponseMapper,
                new OrologioPilotato(OGGI.atStartOfDay().toInstant(ZoneOffset.UTC)));
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
     * <p>Non e' un doppione di {@link #autenticaStaff()}: {@code personale()}
     * riconosce due ruoli con un {@code ||}, e finche' nessun test arriva qui
     * con ADMIN la meta' ADMIN di quella condizione non si e' mai vista agire —
     * cioe' la frase dello spec <i>"STAFF e ADMIN vedono tutte"</i> aveva una
     * meta' senza prove.
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
        Ruolo ruolo = new Ruolo();
        ruolo.setNome("USER");

        Utente utente = new Utente();
        utente.setId(ID_CLIENTE);
        utente.setNome("Mario");
        utente.setCognome("Rossi");
        utente.setEmail(EMAIL_CLIENTE);
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
    private void disponibilita(long camere, long occupate) {
        when(cameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(camere);
        when(prenotazioneRepository.occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(LocalDate.class),
                any(LocalDate.class), any(Collection.class), any())).thenReturn(occupate);
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("da un cliente intesta a lui, mette ONLINE e calcola il totale sulle notti")
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
            // Il totale e' 120 x 3 notti: tre e non quattro, perche' chi arriva il 7 e
            // parte il 10 dorme tre notti — ed e' la stessa aritmetica che lascia il
            // giorno di partenza libero per chi arriva quel giorno
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
        @DisplayName("da un ruolo di personale che non sta nella tabella staff risponde 400")
        void crea_daPersonaleSenzaRigaStaff_sollevaBadRequest() {
            // given: un account che vive nella tabella dei clienti e porta il ruolo STAFF —
            // cioe' un cliente a cui qualcuno ha cambiato il ruolo scrivendo a mano nel
            // database. E' il caso in cui ruolo e tipo dell'account non combaciano, e da
            // quando il principal porta il tipo si riconosce senza interrogare nessuno
            autentica(TipoAccount.CLIENTE, ID_CLIENTE, EMAIL_CLIENTE, "STAFF");
            tipologiaEsiste();
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));

            // when/then: si rifiuta invece di scrivere una riga senza gestore. Quella
            // situazione va vista, non aggirata in silenzio
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()
                    .utenteId(ID_CLIENTE)
                    .canale(CanalePrenotazione.TELEFONO)))
                    .isInstanceOf(BadRequestException.class);

            verify(staffRepository, never()).findById(anyLong());
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

            // when/then: 401 e non 400, ed e' la coppia del test qui sopra — stesso metodo,
            // due esiti diversi. La' la richiesta chiede un'operazione da personale a un
            // account che personale non e'; qui non c'e' niente di sbagliato nella
            // richiesta, e' il token a valere per un account che non esiste piu'
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
            // given: prezzo massimo per un soggiorno lunghissimo
            autenticaCliente();
            TipologiaCamera cara = tipologia();
            cara.setPrezzoNotte(new BigDecimal("99999999.99"));
            when(utenteRepository.findById(ID_CLIENTE)).thenReturn(Optional.of(cliente()));
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.of(cara));
            disponibilita(1, 0);

            // when/then: il totale non lo manda il client, nasce da una moltiplicazione,
            // quindi nessuna validazione dello schema puo' fermarlo prima di noi
            assertThatThrownBy(() -> prenotazioneService.crea(richiestaValida()))
                    .isInstanceOf(BadRequestException.class);

            verify(prenotazioneRepository, never()).save(any());
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
            // nuovo ruolo e tipo che non combaciano, stavolta nel verso opposto. Il suo
            // id vale su 'staff', e lo stesso numero su 'utente' e' quasi certamente
            // il cliente di qualcun altro
            autentica(TipoAccount.PERSONALE, ID_CLIENTE, EMAIL_STAFF, "USER");

            // when/then: 401. E' il motivo per cui il tipo va guardato e non solo letto:
            // senza il controllo, questo id finirebbe nel filtro della query come se
            // fosse quello di un cliente, e la risposta conterrebbe le prenotazioni di
            // un utente che non ha niente a che vedere con chi ha chiamato
            assertThatThrownBy(() -> prenotazioneService.elenca(0, 20, null))
                    .isInstanceOf(UnauthorizedException.class);

            verifyNoInteractions(prenotazioneRepository);
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
