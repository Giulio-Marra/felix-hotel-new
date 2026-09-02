package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.SchedineAlloggiatiResponse;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.entity.enums.Sesso;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.entity.enums.TipoAlloggiato;
import com.felixhotel.backend.entity.enums.TipoCodifica;
import com.felixhotel.backend.entity.enums.TipoDocumento;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.AlloggiatiMapper;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.VoceCodificaRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.AlloggiatiServiceImpl;
import com.felixhotel.backend.service.impl.CodiciAlloggiati;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L'export delle schedine alloggiati, provato senza database.
 *
 * <p><b>Qui si guardano i rami, non il formato</b>: che riga esca da un insieme di
 * dati corretti lo prova {@code TracciatoAlloggiatiTest}, che e' fatto apposta e non
 * ha bisogno di costruire entita'. Questa classe verifica le tre cose che solo il
 * Service sa fare — <i>chi entra nel file</i>, <i>quando l'export si rifiuta di
 * produrlo</i> e <i>che i due stadi siano davvero in quest'ordine</i>.
 *
 * <p><b>Il mapper e' vero e non finto</b>, come negli altri unitari del progetto:
 * costruisce il nome del file, cioe' l'unica cosa che aggiunge, e con un finto non la
 * proverebbe niente. {@code ApiResponseMapper} invece e' un mock, perche' la busta e'
 * gia' provata altrove.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlloggiatiServiceImpl")
class AlloggiatiServiceImplTest {

    private static final LocalDate ARRIVO = LocalDate.of(2026, 9, 1);
    private static final Long ID_PRENOTAZIONE = 42L;
    private static final Long ID_STAFF = 3L;

    private static final String COMUNE = "058091";
    private static final String STATO = "100000100";

    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private OspiteRepository ospiteRepository;
    @Mock
    private VoceCodificaRepository voceCodificaRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private AlloggiatiServiceImpl alloggiatiService;

    @BeforeEach
    void inizializza() {
        alloggiatiService = new AlloggiatiServiceImpl(prenotazioneRepository, ospiteRepository,
                voceCodificaRepository, new AlloggiatiMapper(), apiResponseMapper,
                new ChiamanteCorrente());
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
        void esporta_conAccountIbrido_sollevaUnauthorized() {
            // given: una riga di 'utente' col ruolo STAFF. @PreAuthorize la lascerebbe
            // passare, perche' guarda il ruolo e non da quale tabella l'account viene
            autentica(TipoAccount.CLIENTE, "STAFF");

            // when/then: 401 e non 403, come sugli ospiti — il ruolo basterebbe, e' il
            // token a valere per un account che non e' quello che dice di essere
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(UnauthorizedException.class);

            // e non ha guardato niente: il permesso viene prima di ogni lettura
            verifyNoInteractions(prenotazioneRepository, ospiteRepository, voceCodificaRepository);
        }
    }

    @Nested
    @DisplayName("chi entra nel file")
    class ChiEntra {

        @Test
        @DisplayName("si chiedono solo le prenotazioni gia' arrivate")
        void esporta_chiedeSoloCheckInECheckOut() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.arriviDelGiorno(eq(ARRIVO), anyCollection()))
                    .thenReturn(List.of());
            when(ospiteRepository.findByPrenotazioneIdInOrderByPrenotazioneIdAscIdAsc(anyCollection()))
                    .thenReturn(List.of());

            // when
            alloggiatiService.esportaSchedine(ARRIVO);

            // then: CONFERMATA non c'e', ed e' il punto — una prenotazione confermata e
            // mai arrivata non e' un arrivo, e comunicarla vorrebbe dire dichiarare alla
            // Questura che qualcuno dorme qui quando non c'e'
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<StatoPrenotazione>> stati =
                    ArgumentCaptor.forClass(Collection.class);
            verify(prenotazioneRepository)
                    .arriviDelGiorno(eq(ARRIVO), stati.capture());
            assertThat(stati.getValue())
                    .containsExactlyInAnyOrder(StatoPrenotazione.CHECK_IN, StatoPrenotazione.CHECK_OUT);
        }

        @Test
        @DisplayName("un giorno senza arrivi e' 200 con zero schedine e file vuoto")
        void esporta_senzaArrivi_fileVuoto() {
            // given
            autenticaStaff();
            when(prenotazioneRepository.arriviDelGiorno(eq(ARRIVO), anyCollection()))
                    .thenReturn(List.of());
            when(ospiteRepository.findByPrenotazioneIdInOrderByPrenotazioneIdAscIdAsc(anyCollection()))
                    .thenReturn(List.of());

            // when
            alloggiatiService.esportaSchedine(ARRIVO);

            // then: e' una risposta e non un errore. Chi automatizza il download non deve
            // distinguere una giornata vuota da un guasto
            SchedineAlloggiatiResponse risposta = rispostaCatturata();
            assertThat(risposta.getNumeroSchedine()).isZero();
            // La stringa vuota davvero, non un CRLF solo: un file con un terminatore e
            // nessuna riga non e' un file senza schedine, e' un file con una riga vuota
            assertThat(risposta.getContenuto()).isEmpty();
            assertThat(risposta.getNomeFile()).isEqualTo("schedine_20260901.txt");
        }

        @Test
        @DisplayName("una riga per ospite, terminata anche l'ultima")
        void esporta_dueOspiti_dueRigheTerminate() {
            // given: un capofamiglia e un figlio, cioe' il gruppo tipico
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite capo = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.CAPOFAMIGLIA, prenotazione);
            Ospite figlio = ospiteMinorenne("ROSSI", "LUCA", prenotazione);
            arrivi(prenotazione, List.of(capo, figlio));
            codificheComplete();

            // when
            alloggiatiService.esportaSchedine(ARRIVO);

            // then
            SchedineAlloggiatiResponse risposta = rispostaCatturata();
            assertThat(risposta.getNumeroSchedine()).isEqualTo(2);
            // Due righe da 168 piu' due terminatori: anche l'ultima riga e' terminata,
            // perche' un file di record a lunghezza fissa non finisce a meta'
            assertThat(risposta.getContenuto()).hasSize(2 * 168 + 2 * 2);
            assertThat(risposta.getContenuto()).endsWith("\r\n");
            // e i due gruppi di codici sono quelli giusti: il capo porta il documento,
            // il figlio no
            assertThat(risposta.getContenuto()).startsWith(CodiciAlloggiati.codice(TipoAlloggiato.CAPOFAMIGLIA));
        }
    }

    @Nested
    @DisplayName("quando l'export si rifiuta")
    class Rifiuti {

        @Test
        @DisplayName("una prenotazione arrivata e senza ospiti e' 409")
        void esporta_senzaOspiti_sollevaConflict() {
            // given: raggiungibile davvero — il check-in pretende gli ospiti al completo,
            // ma cancellarne uno dopo e' permesso, e cancellarli tutti lascia un arrivo
            // senza registro
            autenticaStaff();
            arrivi(prenotazione(), List.of());

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("nessun ospite registrato");
        }

        @Test
        @DisplayName("un campo della schedina che manca e' 409, e il messaggio dice chi")
        void esporta_campoMancante_sollevaConflictConNome() {
            // given: un ospite registrato prima che i campi della schedina esistessero,
            // oppure registrato in fretta al banco. E' il caso normale, non un difetto
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite senzaSesso = ospiteCompleto("BIANCHI", "ANNA", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            senzaSesso.setSesso(null);
            arrivi(prenotazione, List.of(senzaSesso));

            // when/then: il messaggio nomina la persona, ed e' cio' che rende l'errore
            // riparabile in un minuto invece che riaprendo le prenotazioni una per una
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("BIANCHI")
                    .hasMessageContaining("ANNA")
                    .hasMessageContaining("sesso");
        }

        @Test
        @DisplayName("un capo senza documento e' 409, e il messaggio dice cosa fare")
        void esporta_capoSenzaDocumento_sollevaConflict() {
            // given: un minorenne registrato senza documento (V10) a cui e' stato dato un
            // ruolo da capo. E' l'incrocio fra le due regole, ed e' il caso in cui un
            // messaggio generico manderebbe chi legge nella direzione sbagliata
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite bambinoCapo = ospiteMinorenne("ROSSI", "LUCA", prenotazione);
            bambinoCapo.setTipoAlloggiato(TipoAlloggiato.CAPOFAMIGLIA);
            arrivi(prenotazione, List.of(bambinoCapo));

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("familiare o membro del gruppo");
        }

        @Test
        @DisplayName("dei familiari senza capofamiglia sono 409")
        void esporta_familiareSenzaCapo_sollevaConflict() {
            // given: due familiari e nessuno che risponda per loro. La regola non e' "ci
            // vuole un capo" ma "chi dichiara di essere accompagnato deve avere qualcuno
            // che lo accompagna"
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite primo = ospiteMinorenne("ROSSI", "LUCA", prenotazione);
            Ospite secondo = ospiteMinorenne("ROSSI", "GIULIA", prenotazione);
            arrivi(prenotazione, List.of(primo, secondo));

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("nessun capofamiglia");
        }

        @Test
        @DisplayName("due persone entrambe ospite singolo sono legittime")
        void esporta_dueOspitiSingoli_passa() {
            // given: due colleghi che dividono una stanza. Presentano due documenti e
            // fanno due schedine indipendenti — non e' un gruppo senza capo
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite uno = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            Ospite due = ospiteCompleto("BIANCHI", "ANNA", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            arrivi(prenotazione, List.of(uno, due));
            codificheComplete();

            // when
            alloggiatiService.esportaSchedine(ARRIVO);

            // then: due righe, nessun 409
            assertThat(rispostaCatturata().getNumeroSchedine()).isEqualTo(2);
        }

        @Test
        @DisplayName("un codice che il Ministero non ha pubblicato e' 409 e dice quale famiglia")
        void esporta_codiceNonImportato_sollevaConflict() {
            // given: il caso piu' probabile di tutti, e non e' un bug — un'installazione
            // nuova, con le tabelle di codifica ancora vuote (V12). Senza questo
            // controllo il primo export produrrebbe un file di codici inventati
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            arrivi(prenotazione, List.of(ospite));
            when(voceCodificaRepository.findByTipoAndCodiceIn(any(), anyCollection()))
                    .thenReturn(List.of());

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("/api/codifiche/");
        }

        @Test
        @DisplayName("un comune importato senza provincia e' 409: il tracciato la pretende")
        void esporta_comuneSenzaProvincia_sollevaConflict() {
            // given: non un codice sbagliato ma una riga importata a meta'. Il tracciato
            // ha una casella per la provincia e lasciarla vuota fa scartare la schedina
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            arrivi(prenotazione, List.of(ospite));
            codificheCon(voce(TipoCodifica.COMUNE, COMUNE, "MILANO", null));

            // when/then: il messaggio manda a rifare l'import, che e' la riparazione
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("provincia");
        }

        @Test
        @DisplayName("se una sola schedina non e' compilabile non si scrive nessuna riga")
        void esporta_unaIncompleta_nonProduceNiente() {
            // given: quattro persone di cui una senza cittadinanza
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite buono = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            Ospite incompleto = ospiteCompleto("BIANCHI", "ANNA", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            incompleto.setCittadinanza(null);
            arrivi(prenotazione, List.of(buono, incompleto));

            // when/then: tutto o niente. Un file parziale sembra buono e la Questura non
            // ha modo di sapere che mancano delle persone — e' la ragione per cui i tre
            // stadi sono in quest'ordine
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class);
            verifyNoInteractions(apiResponseMapper);
        }
    }

    @Nested
    @DisplayName("il gruppo e i campi che mancano")
    class GruppoECampi {

        @Test
        @DisplayName("due capi dichiarati sulla stessa prenotazione sono 409")
        void esporta_dueCapi_sollevaConflict() {
            // given: sulla schedina il capo e' uno solo, e due farebbero due gruppi
            // dentro la stessa prenotazione senza dire quale sia quale
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite uno = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.CAPOFAMIGLIA, prenotazione);
            Ospite due = ospiteCompleto("ROSSI", "ANNA", TipoAlloggiato.CAPOFAMIGLIA, prenotazione);
            arrivi(prenotazione, List.of(uno, due));

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("piu' di un capo");
        }

        @Test
        @DisplayName("dei membri del gruppo senza capogruppo sono 409")
        void esporta_membroSenzaCapogruppo_sollevaConflict() {
            // given: il gemello del test sui familiari. Sono due catene separate — una
            // famiglia non copre un gruppo e viceversa — ed e' il motivo per cui i
            // controlli sono due e non uno
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite capofamiglia = ospiteCompleto("ROSSI", "MARIO", TipoAlloggiato.CAPOFAMIGLIA, prenotazione);
            Ospite membro = ospiteCompleto("BIANCHI", "ANNA", TipoAlloggiato.MEMBRO_GRUPPO, prenotazione);
            arrivi(prenotazione, List.of(capofamiglia, membro));

            // when/then: un capofamiglia c'e', ma non risponde di un membro di gruppo
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("nessun capogruppo");
        }

        @Test
        @DisplayName("senza ne' comune ne' stato di nascita e' 409")
        void esporta_senzaLuogoDiNascita_sollevaConflict() {
            // given
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("VERDI", "LUIGI", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setComuneNascita(null);
            arrivi(prenotazione, List.of(ospite));

            // when/then: il tracciato ha due caselle e ne vuole compilata esattamente
            // una. Nessuna delle due non e' un caso legittimo, e' un modulo a meta'
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("luogo di nascita");
        }

        @Test
        @DisplayName("con comune e stato insieme e' 409, anche se il database lo vieta gia'")
        void esporta_conComuneEStato_sollevaConflict() {
            // given: uno stato in cui il CHECK del V13 non lascia arrivare nessuno. Ci si
            // finisce solo scrivendo a mano nel database, e il controllo esiste perche'
            // quella riga produrrebbe altrimenti una schedina con due caselle piene dove
            // ne va una
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("VERDI", "LUIGI", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setStatoNascita(STATO);
            arrivi(prenotazione, List.of(ospite));

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("sia il comune sia lo stato");
        }

        @Test
        @DisplayName("un capo senza luogo di rilascio del documento e' 409")
        void esporta_senzaLuogoRilascio_sollevaConflict() {
            // given
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("VERDI", "LUIGI", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setLuogoRilascioDocumento(null);
            arrivi(prenotazione, List.of(ospite));

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("luogo di rilascio");
        }

        @Test
        @DisplayName("un luogo di rilascio che non esiste in nessuna delle due famiglie e' 409")
        void esporta_luogoRilascioIgnoto_sollevaConflict() {
            // given: e' l'unico campo cercato in due famiglie, perche' un documento puo'
            // essere stato emesso da un comune italiano o da uno stato estero
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("VERDI", "LUIGI", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setLuogoRilascioDocumento("999999");
            arrivi(prenotazione, List.of(ospite));
            codificheComplete();

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ne' fra i comuni ne' fra gli stati");
        }

        @Test
        @DisplayName("un comune di nascita che il Ministero non ha pubblicato e' 409")
        void esporta_comuneIgnoto_sollevaConflict() {
            // given: la famiglia COMUNE e' importata ma non contiene questo codice —
            // diverso dal caso "nessuna codifica importata", che ha un test suo
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("VERDI", "LUIGI", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setComuneNascita("999999");
            arrivi(prenotazione, List.of(ospite));
            codificheComplete();

            // when/then
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("COMUNE");
        }
    }

    @Nested
    @DisplayName("chi e' nato all'estero")
    class NatoAllEstero {

        /** Le due caselle del luogo di nascita sul tracciato, indice di fine escluso. */
        private static final int COMUNE_DA = 105;
        private static final int PROVINCIA_A = 116;
        private static final int STATO_DA = 116;
        private static final int STATO_A = 125;

        @Test
        @DisplayName("porta lo stato di nascita al posto del comune, e la riga esce lo stesso")
        void esporta_natoAllEstero_produceLaRiga() {
            // given: e' un percorso intero che nessun altro test tocca — comune e
            // provincia restano vuoti e la casella dello stato si riempie
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("SCHMIDT", "HANS", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setComuneNascita(null);
            ospite.setStatoNascita(STATO);
            arrivi(prenotazione, List.of(ospite));
            codificheComplete();

            // when
            alloggiatiService.esportaSchedine(ARRIVO);

            // then
            String contenuto = rispostaCatturata().getContenuto();
            assertThat(contenuto.substring(COMUNE_DA, PROVINCIA_A))
                    .as("comune e provincia restano in bianco per chi e' nato fuori")
                    .isBlank();
            assertThat(contenuto.substring(STATO_DA, STATO_A).trim()).isEqualTo(STATO);
        }

        @Test
        @DisplayName("con uno stato che il Ministero non ha pubblicato e' 409")
        void esporta_statoNascitaIgnoto_sollevaConflict() {
            // given
            autenticaStaff();
            Prenotazione prenotazione = prenotazione();
            Ospite ospite = ospiteCompleto("SCHMIDT", "HANS", TipoAlloggiato.OSPITE_SINGOLO, prenotazione);
            ospite.setComuneNascita(null);
            ospite.setStatoNascita("999999999");
            arrivi(prenotazione, List.of(ospite));
            codificheComplete();

            // when/then: il ramo dello stato di nascita ha il suo controllo, distinto da
            // quello del comune perche' guarda un'altra famiglia
            assertThatThrownBy(() -> alloggiatiService.esportaSchedine(ARRIVO))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("STATO");
        }
    }

    // ---------------------------------------------------------------- supporto

    /** La prenotazione delle prove: arriva il primo settembre e resta tre notti. */
    private Prenotazione prenotazione() {
        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(ID_PRENOTAZIONE);
        prenotazione.setDataCheckIn(ARRIVO);
        prenotazione.setDataCheckOut(ARRIVO.plusDays(3));
        prenotazione.setStato(StatoPrenotazione.CHECK_IN);
        return prenotazione;
    }

    /** Un ospite con tutti i sei campi della schedina valorizzati. */
    private Ospite ospiteCompleto(String cognome, String nome, TipoAlloggiato tipo, Prenotazione prenotazione) {
        Ospite ospite = new Ospite();
        ospite.setPrenotazione(prenotazione);
        ospite.setCognome(cognome);
        ospite.setNome(nome);
        ospite.setDataNascita(LocalDate.of(1985, 4, 17));
        ospite.setTipoDocumento(TipoDocumento.CARTA_IDENTITA);
        ospite.setNumeroDocumento("CA12345AB");
        ospite.setTipoAlloggiato(tipo);
        ospite.setSesso(Sesso.M);
        ospite.setComuneNascita(COMUNE);
        ospite.setCittadinanza(STATO);
        ospite.setLuogoRilascioDocumento(COMUNE);
        return ospite;
    }

    /**
     * Un minorenne senza documento — il caso che il V10 esiste per permettere —
     * registrato come familiare, che e' il ruolo che la schedina gli da'.
     */
    private Ospite ospiteMinorenne(String cognome, String nome, Prenotazione prenotazione) {
        Ospite ospite = ospiteCompleto(cognome, nome, TipoAlloggiato.FAMILIARE, prenotazione);
        ospite.setDataNascita(LocalDate.of(2016, 5, 2));
        ospite.setTipoDocumento(null);
        ospite.setNumeroDocumento(null);
        ospite.setLuogoRilascioDocumento(null);
        return ospite;
    }

    private void arrivi(Prenotazione prenotazione, List<Ospite> ospiti) {
        when(prenotazioneRepository.arriviDelGiorno(eq(ARRIVO), anyCollection()))
                .thenReturn(List.of(prenotazione));
        when(ospiteRepository.findByPrenotazioneIdInOrderByPrenotazioneIdAscIdAsc(anyCollection()))
                .thenReturn(ospiti);
    }

    /** Tutte le codifiche importate e coerenti: e' lo stato di un'installazione a posto. */
    private void codificheComplete() {
        codificheCon(voce(TipoCodifica.COMUNE, COMUNE, "MILANO", "MI"));
    }

    /**
     * Le codifiche, col comune passato come parametro perche' e' l'unico che cambia
     * fra i test. Gli altri codici tornano sempre presenti: il caso "non importato"
     * ha un test suo, e ripeterlo qui renderebbe questi indipendenti dal dato.
     */
    private void codificheCon(VoceCodifica comune) {
        lenient().when(voceCodificaRepository.findByTipoAndCodiceIn(eq(TipoCodifica.COMUNE), anyCollection()))
                .thenReturn(List.of(comune));
        lenient().when(voceCodificaRepository.findByTipoAndCodiceIn(eq(TipoCodifica.STATO), anyCollection()))
                .thenReturn(List.of(voce(TipoCodifica.STATO, STATO, "ITALIA", null)));
        lenient().when(voceCodificaRepository
                        .findByTipoAndCodiceIn(eq(TipoCodifica.TIPO_DOCUMENTO), anyCollection()))
                .thenAnswer(invocazione -> vociDa(TipoCodifica.TIPO_DOCUMENTO, invocazione.getArgument(1)));
        lenient().when(voceCodificaRepository
                        .findByTipoAndCodiceIn(eq(TipoCodifica.TIPO_ALLOGGIATO), anyCollection()))
                .thenAnswer(invocazione -> vociDa(TipoCodifica.TIPO_ALLOGGIATO, invocazione.getArgument(1)));
    }

    /**
     * Restituisce una voce per ogni codice chiesto, cioe' finge una famiglia importata
     * che contiene tutto. E' l'unico modo di provare i rami del formato senza legare
     * il test alle costanti di {@code CodiciAlloggiati}: se un giorno cambiassero,
     * questi test resterebbero verdi per la ragione giusta.
     */
    @SuppressWarnings("unchecked")
    private List<VoceCodifica> vociDa(TipoCodifica tipo, Object codici) {
        return ((Collection<String>) codici).stream()
                .map(codice -> voce(tipo, codice, codice, null))
                .toList();
    }

    private VoceCodifica voce(TipoCodifica tipo, String codice, String descrizione, String provincia) {
        VoceCodifica voce = new VoceCodifica();
        voce.setTipo(tipo);
        voce.setCodice(codice);
        voce.setDescrizione(descrizione);
        voce.setProvincia(provincia);
        return voce;
    }

    /** Il DTO che il Service ha passato alla busta. */
    private SchedineAlloggiatiResponse rispostaCatturata() {
        ArgumentCaptor<Object> corpo = ArgumentCaptor.forClass(Object.class);
        verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), any(), corpo.capture());
        return (SchedineAlloggiatiResponse) corpo.getValue();
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
}
