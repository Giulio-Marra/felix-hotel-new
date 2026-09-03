package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffAggiornamentoRequest;
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
import com.felixhotel.backend.security.IstanteRevoca;
import com.felixhotel.backend.service.impl.StaffServiceImpl;
import com.felixhotel.backend.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link StaffServiceImpl}: la classe sotto esame e' vera,
 * repository finti.
 *
 * <p>Due collaboratori sono <b>reali</b>, e in tutti e due i casi perche' un
 * finto direbbe quello che gli si e' detto di dire proprio dove sta la cosa da
 * verificare. Lo {@link StaffMapper} traduce il nome del ruolo nell'enum dello
 * spec; il {@link PasswordEncoder} e' quello vero dell'applicazione, perche' la
 * promessa "la password non viene salvata in chiaro" con un cifratore finto
 * sarebbe verificata da noi stessi.
 *
 * <p>Si verificano le decisioni: quale eccezione sceglie ciascun ramo, cosa il
 * service fa o non fa prima di sollevarla, e la sola regola che questa risorsa
 * ha in piu' di un CRUD — l'ultimo amministratore attivo non si puo' togliere
 * di mezzo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffServiceImpl")
class StaffServiceImplTest {

    private static final Long ID = 3L;

    @Mock
    private StaffRepository staffRepository;
    @Mock
    private UtenteRepository utenteRepository;
    @Mock
    private RuoloRepository ruoloRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    /** L'invito che parte alla creazione. Mock: qui interessa che parta, non cosa dica. */
    @Mock
    private ServizioNotifiche servizioNotifiche;

    private StaffServiceImpl staffService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializza() {
        dati = new TestDataFactory();
        staffService = new StaffServiceImpl(staffRepository, utenteRepository, ruoloRepository,
                new BCryptPasswordEncoder(), servizioNotifiche, new StaffMapper(), apiResponseMapper);
    }

    private Ruolo ruolo(String nome) {
        Ruolo ruolo = new Ruolo();
        ruolo.setNome(nome);
        return ruolo;
    }

    private Staff staffEsistente(String nomeRuolo, boolean attivo) {
        Staff staff = new Staff();
        staff.setId(ID);
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        staff.setEmail("anna.bianchi@felixhotel.it");
        staff.impostaPassword("hash-di-prima", IstanteRevoca.adesso());
        staff.setTelefono("+39 333 1234567");
        staff.setDataAssunzione(LocalDate.of(2024, 3, 1));
        staff.setAttivo(attivo);
        staff.setRuolo(ruolo(nomeRuolo));
        return staff;
    }

    /** L'email della richiesta e' libera da entrambe le parti: e' il caso normale. */
    private void emailLibera(String email) {
        when(staffRepository.existsByEmailIgnoreCase(email)).thenReturn(false);
        when(utenteRepository.existsByEmailIgnoreCase(email)).thenReturn(false);
    }

    private void ruoloEsiste(String nome) {
        when(ruoloRepository.findByNome(nome)).thenReturn(Optional.of(ruolo(nome)));
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("senza filtri li passa entrambi a null e ordina per cognome e nome")
        void elenca_senzaFiltri_passaNullEOrdinaPerCognome() {
            // given: una pagina qualsiasi
            when(staffRepository.cerca(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(staffEsistente("STAFF", true))));

            // when: nessun filtro — e' come si apre la pagina la prima volta, e comprende
            // gli account disattivati
            staffService.elenca(1, 5, null, null);

            // then: i null arrivano alla query, che sa gestirli. L'ordine ha due criteri
            // perche' due Bianchi in organico non sono un caso di scuola
            ArgumentCaptor<Pageable> paginaRichiesta = ArgumentCaptor.forClass(Pageable.class);
            verify(staffRepository).cerca(isNull(), isNull(), paginaRichiesta.capture());

            assertThat(paginaRichiesta.getValue()).isEqualTo(
                    PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "cognome", "nome")));
        }

        @Test
        @DisplayName("col ruolo filtrato passa alla query il suo nome, non l'enum")
        void elenca_conRuolo_passaIlNome() {
            // given: si filtra per i soli amministratori, attivi
            when(staffRepository.cerca(eq("ADMIN"), eq(true), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when
            staffService.elenca(0, 20, RuoloStaff.ADMIN, true);

            // then: alla query arriva la stringa. Il ruolo e' una riga di un'altra tabella
            // e il suo id non lo conosce nessuno fuori dal database: il nome e' l'unica
            // cosa che il contratto e la tabella hanno in comune
            verify(staffRepository).cerca(eq("ADMIN"), eq(true), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("con dati validi nasce senza password, attivo, e parte l'invito")
        void crea_conDatiValidi_rispondeCreated() {
            // given: email libera e ruolo esistente
            StaffRequest richiesta = dati.staffRequest();
            emailLibera(richiesta.getEmail());
            ruoloEsiste("STAFF");
            when(staffRepository.saveAndFlush(any(Staff.class))).thenReturn(staffEsistente("STAFF", true));

            // when
            staffService.crea(richiesta);

            // then: l'account nasce **senza password** e attivo. Fino al 2026-09-02 questo
            // test verificava che la password fosse arrivata in tabella gia' cifrata; da
            // quel giorno la password alla creazione non c'e' proprio, e l'assenza e' la
            // cosa da provare — e' quel che rende l'account non autenticabile finche' la
            // persona non accetta l'invito.
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).saveAndFlush(salvato.capture());

            assertThat(salvato.getValue().getEmail()).isEqualTo(richiesta.getEmail());
            assertThat(salvato.getValue().getPasswordHash())
                    .as("un account invitato non ha credenziali finche' non accetta")
                    .isNull();
            // "Attivo" qui vuol dire "non disattivato", che e' una cosa diversa
            // dall'essere utilizzabile: il login lo rifiuta comunque.
            assertThat(salvato.getValue().isAttivo()).isTrue();
            assertThat(salvato.getValue().getRuolo().getNome()).isEqualTo("STAFF");

            // e l'invito e' partito, sull'entita' **salvata** e non su quella costruita:
            // il destinatario del token e' l'id, che prima della scrittura non esiste.
            // Senza l'invito l'account resterebbe irraggiungibile per sempre, e nessuno
            // se ne accorgerebbe finche' la persona non prova a entrare.
            verify(servizioNotifiche).invitoPersonale(any(Staff.class));

            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("con un'email gia' di un altro membro del personale risponde 409")
        void crea_conEmailDelPersonale_sollevaConflict() {
            // given
            StaffRequest richiesta = dati.staffRequest();
            when(staffRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(true);

            // when/then: e non si arriva nemmeno a cifrare la password
            assertThatThrownBy(() -> staffService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(staffRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("con un'email gia' di un cliente risponde 409")
        void crea_conEmailDiUnCliente_sollevaConflict() {
            // given: l'indirizzo non e' di nessun collega, ma di un cliente registrato
            StaffRequest richiesta = dati.staffRequest();
            when(staffRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(true);

            // when/then: 409 come sopra, e non e' zelo. L'email e' la credenziale di login
            // e CustomUserDetailsService cerca prima fra i clienti: un account del
            // personale che ne condividesse una non riuscirebbe mai ad autenticarsi
            assertThatThrownBy(() -> staffService.crea(richiesta))
                    .isInstanceOf(ConflictException.class);

            verify(staffRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("con l'indice unico violato durante la scrittura risponde 409 e non 500")
        void crea_conIndiceViolato_sollevaConflict() {
            // given: al controllo l'email risultava libera, ma nel frattempo e' arrivata la
            // richiesta gemella — la finestra che nessun controllo preventivo puo' chiudere
            StaffRequest richiesta = dati.staffRequest();
            emailLibera(richiesta.getEmail());
            ruoloEsiste("STAFF");
            when(staffRepository.saveAndFlush(any(Staff.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_staff_email"));

            // when/then: la violazione del database diventa la stessa risposta del
            // controllo preventivo, invece di uscire come guasto interno
            assertThatThrownBy(() -> staffService.crea(richiesta))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("dettaglio")
    class Dettaglio {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException")
        void dettaglio_conIdInesistente_sollevaNotFound() {
            // given
            when(staffRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> staffService.dettaglio(ID))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(ex -> ((NotFoundException) ex).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("non tocca ne' la password ne' l'attivazione")
        void aggiorna_conDatiValidi_lasciaPasswordEAttivazione() {
            // given: un account attivo di cui si cambia solo il nome
            Staff esistente = staffEsistente("STAFF", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest(esistente.getEmail(), RuoloStaff.STAFF).nome("Anna Maria");
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            ruoloEsiste("STAFF");
            when(staffRepository.saveAndFlush(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.aggiorna(ID, richiesta);

            // then: sono le due cose che una dimenticanza non deve poter fare — riaprire
            // un account chiuso o cambiare una password. Hanno i loro endpoint
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).saveAndFlush(salvato.capture());

            assertThat(salvato.getValue().getNome()).isEqualTo("Anna Maria");
            assertThat(salvato.getValue().getPasswordHash()).isEqualTo("hash-di-prima");
            assertThat(salvato.getValue().isAttivo()).isTrue();
        }

        @Test
        @DisplayName("con un'email di qualcun altro risponde 409")
        void aggiorna_conEmailDiUnAltro_sollevaConflict() {
            // given
            Staff esistente = staffEsistente("STAFF", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest("gia.presa@felixhotel.it", RuoloStaff.STAFF);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(true);

            // when/then
            assertThatThrownBy(() -> staffService.aggiorna(ID, richiesta))
                    .isInstanceOf(ConflictException.class);

            verify(staffRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("riconfermando la propria email non da' 409 contro se stessi")
        void aggiorna_conLaPropriaEmail_nonSollevaConflict() {
            // given: la PUT rimanda tutti i campi, email compresa, e quell'email e' sua
            Staff esistente = staffEsistente("STAFF", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest(esistente.getEmail(), RuoloStaff.STAFF);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            ruoloEsiste("STAFF");
            when(staffRepository.saveAndFlush(any(Staff.class))).thenReturn(esistente);

            // when/then: l'esclusione per id e' quello che rende innocuo il caso piu'
            // frequente di tutti — salvare senza aver cambiato l'indirizzo
            staffService.aggiorna(ID, richiesta);

            verify(staffRepository).saveAndFlush(any(Staff.class));
        }

        @Test
        @DisplayName("togliendo ADMIN all'ultimo amministratore attivo risponde 409")
        void aggiorna_degradandoLUltimoAdmin_sollevaConflict() {
            // given: l'unico ADMIN attivo che si degrada a STAFF
            Staff esistente = staffEsistente("ADMIN", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest(esistente.getEmail(), RuoloStaff.STAFF);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            ruoloEsiste("STAFF");
            when(staffRepository.countByRuoloNomeAndAttivoTrueAndIdNot("ADMIN", ID)).thenReturn(0L);

            // when/then: 409 e non 400 — la richiesta e' scritta bene, e' lo stato del
            // sistema a renderla impossibile. Senza nessun ADMIN il backoffice si riapre
            // solo scrivendo nel database
            assertThatThrownBy(() -> staffService.aggiorna(ID, richiesta))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ultimo amministratore");

            verify(staffRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("togliendo ADMIN a uno di due amministratori attivi passa")
        void aggiorna_degradandoUnAdminFraDue_salva() {
            // given: c'e' un altro ADMIN attivo oltre a questo
            Staff esistente = staffEsistente("ADMIN", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest(esistente.getEmail(), RuoloStaff.STAFF);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            ruoloEsiste("STAFF");
            when(staffRepository.countByRuoloNomeAndAttivoTrueAndIdNot("ADMIN", ID)).thenReturn(1L);
            when(staffRepository.saveAndFlush(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.aggiorna(ID, richiesta);

            // then: la regola protegge l'ultimo, non il ruolo in se'. Il conteggio esclude
            // chi si sta degradando, perche' in database e' ancora un ADMIN attivo
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).saveAndFlush(salvato.capture());

            assertThat(salvato.getValue().getRuolo().getNome()).isEqualTo("STAFF");
        }

        @Test
        @DisplayName("promuovendo uno STAFF ad ADMIN non conta gli amministratori")
        void aggiorna_promuovendoAAdmin_nonContaGliAdmin() {
            // given: uno STAFF che diventa ADMIN
            Staff esistente = staffEsistente("STAFF", true);
            StaffAggiornamentoRequest richiesta =
                    dati.staffAggiornamentoRequest(esistente.getEmail(), RuoloStaff.ADMIN);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.existsByEmailIgnoreCaseAndIdNot(richiesta.getEmail(), ID)).thenReturn(false);
            when(utenteRepository.existsByEmailIgnoreCase(richiesta.getEmail())).thenReturn(false);
            ruoloEsiste("ADMIN");
            when(staffRepository.saveAndFlush(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.aggiorna(ID, richiesta);

            // then: aggiungere un amministratore non puo' lasciarne zero, quindi la query
            // non si fa nemmeno. Contare comunque sarebbe una lettura per niente
            verify(staffRepository, never()).countByRuoloNomeAndAttivoTrueAndIdNot(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("impostaAttivazione")
    class ImpostaAttivazione {

        @Test
        @DisplayName("disattivando l'ultimo amministratore attivo risponde 409")
        void attivazione_disattivandoLUltimoAdmin_sollevaConflict() {
            // given: l'unico ADMIN attivo che si spegne
            when(staffRepository.findById(ID)).thenReturn(Optional.of(staffEsistente("ADMIN", true)));
            when(staffRepository.countByRuoloNomeAndAttivoTrueAndIdNot("ADMIN", ID)).thenReturn(0L);

            // when/then: e' la stessa regola del degrado, vista dall'altro verso — le due
            // strade che tolgono di mezzo un amministratore passano dallo stesso controllo
            assertThatThrownBy(() -> staffService.impostaAttivazione(ID, dati.staffAttivazioneRequest(false)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ultimo amministratore");

            verify(staffRepository, never()).save(any());
        }

        @Test
        @DisplayName("il conteggio degli amministratori si fa dopo aver preso il lock")
        void attivazione_prendeIlLockPrimaDiContare() {
            // **Perche' questo test guarda una chiamata e non un esito.** La regola
            // "l'ultimo ADMIN non si tocca" si verifica contando gli altri, e fra il
            // conteggio e la scrittura c'e' una finestra: due disattivazioni simultanee su
            // due ADMIN vedono ognuna *un altro* amministratore e passano tutte e due,
            // lasciando il backoffice senza nessuno che possa entrarci.
            //
            // La corsa vera non si puo' mettere in scena come per le camere (vedi
            // ConcorrenzaApiIT): il conteggio guarda **tutti** gli ADMIN attivi del
            // database, e la suite ne crea a decine — "restano in due" non e' una
            // situazione costruibile finche' gli IT condividono lo stesso database. Quel
            // che si puo' provare e' che il lock venga preso, e **prima** del conteggio:
            // preso dopo, lascerebbe la finestra esattamente dov'era.
            when(staffRepository.findById(ID)).thenReturn(Optional.of(staffEsistente("ADMIN", true)));
            when(staffRepository.countByRuoloNomeAndAttivoTrueAndIdNot("ADMIN", ID)).thenReturn(1L);
            when(staffRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            staffService.impostaAttivazione(ID, dati.staffAttivazioneRequest(false));

            InOrder ordine = inOrder(ruoloRepository, staffRepository);
            ordine.verify(ruoloRepository).bloccaPerConteggio("ADMIN");
            ordine.verify(staffRepository).countByRuoloNomeAndAttivoTrueAndIdNot("ADMIN", ID);
        }

        @Test
        @DisplayName("disattivando uno STAFF non prende nessun lock")
        void attivazione_disattivandoUnoStaff_nonPrendeIlLock() {
            // L'uscita anticipata viene prima del lock, ed e' voluto: chi non e' un ADMIN
            // attivo non deve far aspettare chi lo e'. Un lock preso comunque sarebbe una
            // coda su ogni disattivazione dell'albergo, per una regola che a quei conti non
            // partecipa
            when(staffRepository.findById(ID)).thenReturn(Optional.of(staffEsistente("STAFF", true)));
            when(staffRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            staffService.impostaAttivazione(ID, dati.staffAttivazioneRequest(false));

            verify(ruoloRepository, never()).bloccaPerConteggio(any());
        }

        @Test
        @DisplayName("disattivando uno STAFF non conta gli amministratori")
        void attivazione_disattivandoUnoStaff_nonContaGliAdmin() {
            // given: un account senza privilegi di amministratore
            Staff esistente = staffEsistente("STAFF", true);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.save(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.impostaAttivazione(ID, dati.staffAttivazioneRequest(false));

            // then: chi non e' ADMIN non puo' essere l'ultimo ADMIN, quindi non si conta
            // niente. E l'account si spegne davvero
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).save(salvato.capture());

            assertThat(salvato.getValue().isAttivo()).isFalse();
            verify(staffRepository, never()).countByRuoloNomeAndAttivoTrueAndIdNot(anyString(), anyLong());
        }

        @Test
        @DisplayName("riattivando un amministratore spento non conta gli amministratori")
        void attivazione_riattivando_nonContaGliAdmin() {
            // given: un ADMIN disattivato che si riaccende
            Staff esistente = staffEsistente("ADMIN", false);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.save(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.impostaAttivazione(ID, dati.staffAttivazioneRequest(true));

            // then: riaccendere un amministratore non puo' lasciarne zero. Il controllo
            // guarda com'e' l'account adesso, e questo adesso non e' un ADMIN attivo
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).save(salvato.capture());

            assertThat(salvato.getValue().isAttivo()).isTrue();
            verify(staffRepository, never()).countByRuoloNomeAndAttivoTrueAndIdNot(anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("impostaPassword")
    class ImpostaPassword {

        @Test
        @DisplayName("salva un hash diverso dalla password e diverso da quello di prima")
        void password_conNuovaPassword_salvaUnHash() {
            // given
            Staff esistente = staffEsistente("STAFF", true);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.save(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.impostaPassword(ID, dati.staffPasswordRequest("NuovaPassword456"));

            // then: e' la promessa dello spec — "verra' salvata come hash BCrypt, mai in
            // chiaro" — e con un cifratore finto la verificheremmo contro noi stessi
            ArgumentCaptor<Staff> salvato = ArgumentCaptor.forClass(Staff.class);
            verify(staffRepository).save(salvato.capture());

            assertThat(salvato.getValue().getPasswordHash())
                    .isNotEqualTo("NuovaPassword456")
                    .isNotEqualTo("hash-di-prima")
                    .startsWith("$2");
        }

        @Test
        @DisplayName("risponde con 'data' null, cioe' non rimanda niente dell'account")
        void password_conNuovaPassword_nonRestituisceLAccount() {
            // given
            Staff esistente = staffEsistente("STAFF", true);
            when(staffRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(staffRepository.save(any(Staff.class))).thenReturn(esistente);

            // when
            staffService.impostaPassword(ID, dati.staffPasswordRequest("NuovaPassword456"));

            // then: su un endpoint che maneggia credenziali la risposta piu' utile e'
            // quella che non dice niente di piu' di "e' andata"
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), isNull());
        }

        @Test
        @DisplayName("con id inesistente solleva NotFoundException e non cifra niente")
        void password_conIdInesistente_sollevaNotFound() {
            // given
            when(staffRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> staffService.impostaPassword(ID, dati.staffPasswordRequest("NuovaPassword456")))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(apiResponseMapper);
        }
    }
}
