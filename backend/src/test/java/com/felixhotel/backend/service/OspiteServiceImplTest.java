package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.StatoPrenotazione;
import com.felixhotel.backend.entity.TipoDocumento;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.OspiteMapper;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.OspiteServiceImpl;
import com.felixhotel.backend.support.OrologioPilotato;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@code OspiteServiceImpl}: le decisioni del registro degli
 * ospiti, senza contesto Spring ne' Docker.
 *
 * <p><b>Il {@code SecurityContextHolder} si riempie a mano</b> in ogni test,
 * come per le prenotazioni, e per una ragione che qui pesa piu' che altrove:
 * ogni metodo di questo service comincia da un controllo su chi chiama, e
 * quel controllo e' meta' di una regola di sicurezza — l'altra meta' e'
 * {@code @PreAuthorize} sul Controller, che un unitario non vede. Con un
 * {@code ChiamanteCorrente} finto la meta' che *si* vede non verrebbe
 * esercitata da nessuno, quindi qui la classe e' quella vera.
 *
 * <p>Lo stesso vale per {@link OspiteMapper}: la conversione fra i due
 * {@code TipoDocumento} — quello dell'entita' e quello generato dallo spec — e'
 * logica e non copia, e con un mapper finto non la proverebbe niente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OspiteServiceImpl")
class OspiteServiceImplTest {

    private static final Long ID_PRENOTAZIONE = 42L;
    private static final Long ID_OSPITE = 12L;
    private static final Long ID_STAFF = 3L;

    /** La prenotazione delle prove e' per due persone: e' il numero che vincola tutto. */
    private static final int NUMERO_OSPITI = 2;

    private static final String NUMERO_DOCUMENTO = "CA12345AB";

    /** Un mercoledi' qualunque: e' l'"oggi" di tutti i test di questa classe. */
    private static final LocalDate OGGI = LocalDate.of(2026, 9, 2);

    @Mock
    private OspiteRepository ospiteRepository;
    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private OspiteServiceImpl ospiteService;

    @BeforeEach
    void inizializza() {
        ospiteService = new OspiteServiceImpl(ospiteRepository, prenotazioneRepository,
                new OspiteMapper(), apiResponseMapper, new ChiamanteCorrente(),
                new OrologioPilotato(OGGI.atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    @AfterEach
    void svuotaContesto() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("permesso")
    class Permesso {

        @Test
        @DisplayName("un account ibrido col ruolo di uno staff non passa, e prende 401")
        void elenca_conAccountIbrido_sollevaUnauthorized() {
            // given: una riga di 'utente' a cui una UPDATE a mano ha dato ruolo STAFF.
            // Nessun endpoint lo produce, ma @PreAuthorize lo lascerebbe passare: quello
            // guarda il ruolo e non sa da quale tabella l'account viene
            autentica(TipoAccount.CLIENTE, "STAFF");

            // when/then: 401 e non 403. Il ruolo che ha basterebbe — non e' una
            // questione di permessi — ma il token vale per un account che non e' quello
            // che dice di essere. E' la stessa scelta gia' fatta sul cliente in
            // PrenotazioneServiceImpl.idClienteChiamante
            assertThatThrownBy(() -> ospiteService.elenca(ID_PRENOTAZIONE))
                    .isInstanceOf(UnauthorizedException.class);

            // e non ha nemmeno guardato se la prenotazione esista: il permesso viene
            // prima di tutto, altrimenti un 404 direbbe a un ibrido quali id esistono
            verifyNoInteractions(prenotazioneRepository, ospiteRepository);
        }

        @Test
        @DisplayName("un account del personale col ruolo USER non passa")
        void aggiungi_conPersonaleSenzaRuolo_sollevaUnauthorized() {
            // given: l'ibrido dall'altra parte — sta nella tabella giusta ma non ha il
            // privilegio. In esercizio ci arriverebbe solo scavalcando @PreAuthorize,
            // che questo unitario non ha: il valore del test e' proprio che il Service
            // non si appoggi all'annotazione per l'unica meta' che sa verificare
            autentica(TipoAccount.PERSONALE, "USER");

            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("senza nessuna autenticazione nel contesto risponde 401")
        void elenca_senzaAutenticazione_sollevaUnauthorized() {
            // given: contesto vuoto
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> ospiteService.elenca(ID_PRENOTAZIONE))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("restituisce gli ospiti nell'ordine dato dalla query")
        void elenca_conOspiti_liRestituisceInOrdine() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.existsById(ID_PRENOTAZIONE)).thenReturn(true);
            when(ospiteRepository.findByPrenotazioneIdOrderByIdAsc(ID_PRENOTAZIONE))
                    .thenReturn(List.of(ospite(1L, "Mario", "CA1"), ospite(2L, "Anna", "YA2")));

            // when
            ospiteService.elenca(ID_PRENOTAZIONE);

            // then: il mapper non riordina niente, l'ordine e' quello della query
            verify(apiResponseMapper).toResponse(any(), any(), argomentoLista());
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404, non una lista vuota")
        void elenca_suPrenotazioneInesistente_sollevaNotFound() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.existsById(ID_PRENOTAZIONE)).thenReturn(false);

            // when/then: e' la ragione per cui il controllo di esistenza c'e' anche se la
            // query darebbe comunque una lista vuota. Chi sta al banco deve poter
            // distinguere "non ho ancora registrato nessuno" da "ho aperto la
            // prenotazione sbagliata"
            assertThatThrownBy(() -> ospiteService.elenca(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("si legge anche su una prenotazione conclusa")
        void elenca_suCheckOut_funziona() {
            // given: soggiorno finito
            autenticaStaff();
            when(prenotazioneRepository.existsById(ID_PRENOTAZIONE)).thenReturn(true);
            when(ospiteRepository.findByPrenotazioneIdOrderByIdAsc(ID_PRENOTAZIONE)).thenReturn(List.of());

            // when/then: nessuna eccezione. La finestra di stato vale solo per chi
            // scrive: un registro esiste per essere riletto dopo, e su una prenotazione
            // conclusa e' precisamente quando serve
            ospiteService.elenca(ID_PRENOTAZIONE);

            verify(ospiteRepository).findByPrenotazioneIdOrderByIdAsc(ID_PRENOTAZIONE);
        }
    }

    @Nested
    @DisplayName("aggiungi")
    class Aggiungi {

        @Test
        @DisplayName("registra l'ospite e lo lega alla prenotazione")
        void aggiungi_conPostoLibero_registra() {
            // given: una prenotazione confermata per due, con nessuno ancora registrato
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(0L);
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta());

            // then
            ArgumentCaptor<Ospite> salvato = ArgumentCaptor.forClass(Ospite.class);
            verify(ospiteRepository).saveAndFlush(salvato.capture());
            assertThat(salvato.getValue().getNome()).isEqualTo("Mario");
            assertThat(salvato.getValue().getTipoDocumento()).isEqualTo(TipoDocumento.CARTA_IDENTITA);
            assertThat(salvato.getValue().getPrenotazione().getId()).isEqualTo(ID_PRENOTAZIONE);
        }

        @Test
        @DisplayName("con i posti gia' tutti registrati risponde 409")
        void aggiungi_conRegistroCompleto_sollevaConflict() {
            // given: due su due
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumento(
                    any(), any(), any())).thenReturn(false);
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn((long) NUMERO_OSPITI);

            // when/then: non si registrano piu' persone di quanti posti letto sono stati
            // venduti. E' il tetto che rende un'uguaglianza la condizione del check-in
            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("sono gia' tutti registrati");

            verify(ospiteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("con un documento gia' registrato risponde 409, e lo dice prima del tetto")
        void aggiungi_conDocumentoDuplicato_sollevaConflict() {
            // given: la stessa persona reinviata, e i posti sono anche esauriti
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumento(
                    ID_PRENOTAZIONE, TipoDocumento.CARTA_IDENTITA, NUMERO_DOCUMENTO)).thenReturn(true);

            // when/then: a chi sta reinviando lo stesso modulo "questa persona c'e'
            // gia'" e' la risposta utile. "Sono gia' tutti" lo manderebbe a cercare chi
            // togliere per far posto a qualcuno che c'e' gia'
            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("gia' registrato");

            // il conteggio non e' nemmeno stato chiesto: l'ordine dei due 409 e' quello
            verify(ospiteRepository, never()).countByPrenotazioneId(any());
        }

        @Test
        @DisplayName("il duplicato che arriva dopo il controllo diventa 409, non 500")
        void aggiungi_conDuplicatoConcorrente_traduceInConflict() {
            // given: il controllo preventivo non vede niente, ma fra quello e la scrittura
            // e' passata la richiesta gemella e l'indice unico del V7 la ferma
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(0L);
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_ospite_prenotazione_documento"));

            // when/then: e' la rete sotto al controllo existsBy, e copre l'unico caso che
            // nessun controllo preventivo puo' vedere
            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("con una data di nascita nel futuro risponde 400")
        void aggiungi_conDataNascitaFutura_sollevaBadRequest() {
            // given
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);

            // when/then: 400 e non 409 — e' un valore sbagliato nel corpo, non un
            // conflitto con qualcosa che esiste. Quasi sempre e' l'anno digitato storto
            assertThatThrownBy(() -> ospiteService.aggiungi(
                    ID_PRENOTAZIONE, richiesta().dataNascita(OGGI.plusDays(1))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("con la data di nascita di oggi passa")
        void aggiungi_conDataNascitaDiOggi_passa() {
            // given: un neonato. E' il caso limite del controllo qui sopra, e sta scritto
            // perche' e' l'unico modo di sapere che il confronto e' isAfter e non
            // isAfterOrEqual — cioe' che nascere oggi e' permesso
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(0L);
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta().dataNascita(OGGI));

            verify(ospiteRepository).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("finestra di scrittura")
    class FinestraDiScrittura {

        @Test
        @DisplayName("su una prenotazione IN_ATTESA non si registra nessuno")
        void aggiungi_suInAttesa_sollevaConflict() {
            // given: un carrello, non un soggiorno
            autenticaStaff();
            prenotazione(StatoPrenotazione.IN_ATTESA);

            // when/then: finche' nessuno ha confermato non c'e' nessun soggiorno di cui
            // registrare gli ospiti
            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("IN_ATTESA");
        }

        @Test
        @DisplayName("su una prenotazione ANNULLATA non si registra nessuno")
        void aggiungi_suAnnullata_sollevaConflict() {
            autenticaStaff();
            prenotazione(StatoPrenotazione.ANNULLATA);

            assertThatThrownBy(() -> ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("su un soggiorno gia' concluso non si riscrive il registro")
        void aggiorna_suCheckOut_sollevaConflict() {
            // given: l'ospite e' partito
            autenticaStaff();
            prenotazione(StatoPrenotazione.CHECK_OUT);

            // when/then: e' la parte che vale piu' delle altre. Un registro di legge non
            // si corregge dopo che il soggiorno e' finito: quello che c'e' gia' resta
            assertThatThrownBy(() -> ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta()))
                    .isInstanceOf(ConflictException.class);

            verify(ospiteRepository, never()).findByIdAndPrenotazioneId(any(), any());
        }

        @Test
        @DisplayName("durante il soggiorno si registra ancora: qualcuno arriva il giorno dopo")
        void aggiungi_suCheckIn_funziona() {
            // given: la prenotazione e' gia' in corso
            autenticaStaff();
            prenotazione(StatoPrenotazione.CHECK_IN);
            when(ospiteRepository.countByPrenotazioneId(ID_PRENOTAZIONE)).thenReturn(1L);
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when/then: CHECK_IN e' dentro la finestra e non e' una concessione. Un
            // accompagnatore che arriva il giorno dopo il documento deve darlo lo stesso
            ospiteService.aggiungi(ID_PRENOTAZIONE, richiesta());

            verify(ospiteRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("su una prenotazione ANNULLATA non si cancella nessuno")
        void elimina_suAnnullata_sollevaConflict() {
            autenticaStaff();
            prenotazione(StatoPrenotazione.ANNULLATA);

            assertThatThrownBy(() -> ospiteService.elimina(ID_PRENOTAZIONE, ID_OSPITE))
                    .isInstanceOf(ConflictException.class);

            verify(ospiteRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("riscrive i campi dell'ospite")
        void aggiorna_conDatiNuovi_liScrive() {
            // given
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            Ospite esistente = ospite(ID_OSPITE, "Maria", NUMERO_DOCUMENTO);
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(esistente));
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when: il nome era digitato storto
            ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta());

            // then
            assertThat(esistente.getNome()).isEqualTo("Mario");
        }

        @Test
        @DisplayName("rimandare il proprio documento non e' un conflitto")
        void aggiorna_conLoStessoDocumento_nonEUnConflitto() {
            // given: si corregge il solo nome, quindi il numero di documento che arriva
            // e' quello che quella riga ha gia'
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(ospite(ID_OSPITE, "Maria", NUMERO_DOCUMENTO)));
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when/then: nessuna eccezione. E' il motivo per cui il controllo di
            // unicita' della PUT ignora l'ospite stesso: senza, correggere un nome
            // sarebbe impossibile
            ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta());

            // e la query interrogata e' quella che esclude l'id, non l'altra
            verify(ospiteRepository).existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumentoAndIdNot(
                    ID_PRENOTAZIONE, TipoDocumento.CARTA_IDENTITA, NUMERO_DOCUMENTO, ID_OSPITE);
            verify(ospiteRepository, never())
                    .existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumento(any(), any(), any());
        }

        @Test
        @DisplayName("col documento di un altro ospite della stessa prenotazione risponde 409")
        void aggiorna_conDocumentoDiUnAltro_sollevaConflict() {
            // given: si sta per dare a Mario il documento che e' gia' di Anna
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(ospite(ID_OSPITE, "Mario", "ALTRO")));
            when(ospiteRepository.existsByPrenotazioneIdAndTipoDocumentoAndNumeroDocumentoAndIdNot(
                    ID_PRENOTAZIONE, TipoDocumento.CARTA_IDENTITA, NUMERO_DOCUMENTO, ID_OSPITE))
                    .thenReturn(true);

            assertThatThrownBy(() -> ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("un altro ospite");
        }

        @Test
        @DisplayName("la data di nascita omessa viene azzerata, non lasciata com'era")
        void aggiorna_senzaDataNascita_laAzzera() {
            // given: l'ospite ne ha una
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            Ospite esistente = ospite(ID_OSPITE, "Mario", NUMERO_DOCUMENTO);
            esistente.setDataNascita(LocalDate.of(1985, 4, 17));
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(esistente));
            when(ospiteRepository.saveAndFlush(any(Ospite.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when: la richiesta non la porta
            ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta());

            // then: e' quel che una PUT promette, ed e' scritto nel contratto. Vale la
            // pena provarlo perche' e' il genere di cosa che si scambia per un difetto
            assertThat(esistente.getDataNascita()).isNull();
        }

        @Test
        @DisplayName("su un ospite di un'altra prenotazione risponde 404")
        void aggiorna_conOspiteDiAltraPrenotazione_sollevaNotFound() {
            // given: l'id dell'ospite e' valido, ma appartiene a un altro soggiorno — e
            // la query non lo trova, che e' esattamente il suo scopo
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> ospiteService.aggiorna(ID_PRENOTAZIONE, ID_OSPITE, richiesta()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("cancella la registrazione")
        void elimina_conOspiteEsistente_loCancella() {
            autenticaStaff();
            prenotazione(StatoPrenotazione.CONFERMATA);
            Ospite esistente = ospite(ID_OSPITE, "Mario", NUMERO_DOCUMENTO);
            when(ospiteRepository.findByIdAndPrenotazioneId(ID_OSPITE, ID_PRENOTAZIONE))
                    .thenReturn(Optional.of(esistente));

            ospiteService.elimina(ID_PRENOTAZIONE, ID_OSPITE);

            verify(ospiteRepository).delete(esistente);
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404")
        void elimina_suPrenotazioneInesistente_sollevaNotFound() {
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ospiteService.elimina(ID_PRENOTAZIONE, ID_OSPITE))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ---- fabbriche e scorciatoie -------------------------------------------------

    private OspiteRequest richiesta() {
        return new OspiteRequest()
                .nome("Mario")
                .cognome("Rossi")
                .tipoDocumento(com.felixhotel.backend.dto.TipoDocumento.CARTA_IDENTITA)
                .numeroDocumento(NUMERO_DOCUMENTO);
    }

    private Ospite ospite(Long id, String nome, String numeroDocumento) {
        Ospite ospite = new Ospite();
        ospite.setId(id);
        ospite.setNome(nome);
        ospite.setCognome("Rossi");
        ospite.setTipoDocumento(TipoDocumento.CARTA_IDENTITA);
        ospite.setNumeroDocumento(numeroDocumento);
        return ospite;
    }

    /** Mette in bocca al repository una prenotazione nello stato indicato. */
    private void prenotazione(StatoPrenotazione stato) {
        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(ID_PRENOTAZIONE);
        prenotazione.setNumeroOspiti(NUMERO_OSPITI);
        prenotazione.setStato(stato);
        when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(prenotazione));
    }

    private void autenticaStaff() {
        autentica(TipoAccount.PERSONALE, "STAFF");
    }

    private void autentica(TipoAccount tipo, String ruolo) {
        AppUserPrincipal principal = new AppUserPrincipal(
                tipo, ID_STAFF, "anna.bianchi@example.com", "hash", "Anna", "Bianchi", ruolo, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /**
     * Cattura il terzo argomento della busta come lista: serve solo a dire che il
     * service ha passato al mapper una lista e non altro.
     */
    private List<?> argomentoLista() {
        return org.mockito.ArgumentMatchers.argThat(argomento -> argomento instanceof List<?>);
    }
}
