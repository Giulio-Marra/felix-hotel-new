package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.MetodoPagamento;
import com.felixhotel.backend.dto.PagamentoRequest;
import com.felixhotel.backend.dto.PagamentoResponse;
import com.felixhotel.backend.dto.RiepilogoPagamentiResponse;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import com.felixhotel.backend.entity.Pagamento;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.PagamentoMapper;
import com.felixhotel.backend.mapper.StaffMapper;
import com.felixhotel.backend.repository.ImpostazioniHotelRepository;
import com.felixhotel.backend.repository.PagamentoRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.StaffRepository;
import com.felixhotel.backend.security.AccessoPrenotazioni;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.PagamentoServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitari del registro dei pagamenti.
 *
 * <p><b>E' qui che sta il peso dei test di questo branch</b>: quel che il Service decide
 * — chi puo' scrivere, quando si rifiuta, quanto risulta dovuto — sono rami in Java, e un
 * unitario li esercita in millisecondi. All'IT resta il cablaggio: filtri di sicurezza,
 * busta, migration.
 *
 * <p><b>Mapper veri e non finti</b>, come negli altri unitari del progetto: la
 * sottrazione del residuo, l'arrotondamento della caparra e la conversione dell'istante
 * sono logica, e con dei finti non le proverebbe niente.
 *
 * <p><b>L'orologio e' fermo</b> perche' uno dei rifiuti dipende da "adesso": un test che
 * dicesse "fra un'ora" calcolandolo dall'orologio vero verificherebbe la stessa
 * aritmetica che sta verificando.
 *
 * <p><b>E' un {@code Clock.fixed} nel fuso di sistema e non {@code OrologioPilotato}</b>,
 * che e' l'orologio condiviso di questi test: quello vive in UTC, e qui si confronta
 * un'ora locale — l'istante dichiarato dell'incasso, che il mapper riporta nel fuso di
 * sistema come ogni altra data del progetto. Con due fusi diversi il confronto
 * scivolerebbe di qualche ora, e i test sulla data passerebbero o no a seconda della
 * macchina. In esercizio il problema non esiste, perche' li' il {@code Clock} e' gia'
 * quello di sistema.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PagamentoServiceImpl")
class PagamentoServiceImplTest {

    private static final LocalDate ARRIVO = LocalDate.of(2026, 9, 10);
    private static final LocalDateTime ADESSO = LocalDateTime.of(2026, 9, 2, 12, 0);

    private static final Long ID_PRENOTAZIONE = 42L;
    private static final Long ID_CLIENTE = 7L;
    private static final Long ID_STAFF = 3L;

    private static final BigDecimal TOTALE = new BigDecimal("360.00");

    @Mock
    private PagamentoRepository pagamentoRepository;
    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private ImpostazioniHotelRepository impostazioniHotelRepository;
    @Mock
    private StaffRepository staffRepository;

    private PagamentoServiceImpl pagamentoService;

    @BeforeEach
    void inizializza() {
        pagamentoService = new PagamentoServiceImpl(pagamentoRepository, prenotazioneRepository,
                impostazioniHotelRepository, staffRepository, new PagamentoMapper(new StaffMapper()),
                new ApiResponseMapper(),
                new AccessoPrenotazioni(prenotazioneRepository, new ChiamanteCorrente()),
                new ChiamanteCorrente(),
                Clock.fixed(ADESSO.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    @AfterEach
    void svuotaContesto() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("chi puo' fare cosa")
    class Permessi {

        @Test
        @DisplayName("un cliente vede i pagamenti della propria prenotazione")
        void elenca_clienteProprietario_vede() {
            // given
            autenticaCliente(ID_CLIENTE);
            prenotazioneEsistente(prenotazione());
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(ID_PRENOTAZIONE))
                    .thenReturn(List.of());
            impostazioniConCaparra("0.00");

            // when/then: sono i suoi soldi
            assertThat(riepilogo(pagamentoService.elenca(ID_PRENOTAZIONE)).getImportoTotale())
                    .isEqualTo(TOTALE);
        }

        @Test
        @DisplayName("la prenotazione di un altro cliente e' 404 e non 403")
        void elenca_prenotazioneAltrui_sollevaNotFound() {
            // given: la prenotazione e' del cliente 7, chiama il cliente 99
            autenticaCliente(99L);
            prenotazioneEsistente(prenotazione());

            // when/then: 404, perche' un 403 direbbe "esiste, ma non e' tua" — cioe'
            // permetterebbe di scoprire quali id esistono provandoli
            assertThatThrownBy(() -> pagamentoService.elenca(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("un cliente non puo' registrare un incasso, nemmeno sulla propria")
        void registra_cliente_sollevaUnauthorized() {
            // given: il proprietario della prenotazione
            autenticaCliente(ID_CLIENTE);

            // when/then: dichiarare di aver pagato non e' un gesto che possa fare chi paga
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta("100.00")))
                    .isInstanceOf(UnauthorizedException.class);

            // e non ha nemmeno guardato se la prenotazione esista: il permesso viene prima
            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("un account ibrido col ruolo di uno staff non passa, e prende 401")
        void registra_conAccountIbrido_sollevaUnauthorized() {
            // given: una riga di 'utente' col ruolo STAFF. @PreAuthorize la lascerebbe
            // passare, perche' guarda il ruolo e non da quale tabella l'account viene
            autentica(TipoAccount.CLIENTE, "STAFF", ID_CLIENTE);

            // when/then: 401 e non 403 — il ruolo basterebbe, e' il token a valere per un
            // account che non e' quello che dice di essere
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta("100.00")))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Nested
    @DisplayName("quando l'incasso si rifiuta")
    class Rifiuti {

        @Test
        @DisplayName("su una prenotazione annullata e' 409")
        void registra_prenotazioneAnnullata_sollevaConflict() {
            // given
            autenticaStaff();
            Prenotazione annullata = prenotazione();
            annullata.setStato(StatoPrenotazione.ANNULLATA);
            prenotazioneEsistente(annullata);

            // when/then: incassare su un soggiorno che non avverra' e' quasi sempre uno
            // sbaglio di persona
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta("100.00")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("annullata");
        }

        @Test
        @DisplayName("un importo oltre il residuo e' 409, e il messaggio dice quanto resta")
        void registra_oltreIlResiduo_sollevaConflict() {
            // given: 360 di soggiorno, 108 gia' versati, e qualcuno digita 1080 invece di
            // 108. E' l'errore che capita davvero, ed e' la ragione per cui il controllo
            // esiste: senza, finirebbe nel registro fino ai conti di fine mese
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(new BigDecimal("108.00"));

            // when/then
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta("1080.00")))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("252.00");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("il residuo esatto invece passa: il confine e' l'ultimo importo buono")
        void registra_esattamenteIlResiduo_passa() {
            // given: 252 di residuo e un saldo da 252. Un controllo con il segno sbagliato
            // rifiuterebbe anche questo, e il test di sopra passerebbe lo stesso
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            staffEsistente();
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(new BigDecimal("108.00"));
            when(pagamentoRepository.save(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            pagamentoService.registra(ID_PRENOTAZIONE, richiesta("252.00"));

            // then
            verify(pagamentoRepository).save(any());
        }

        @Test
        @DisplayName("un importo con tre decimali e' 400, prima di toccare il database")
        void registra_treDecimali_sollevaBadRequest() {
            // given: la colonna e' NUMERIC(10,2) e Postgres troncherebbe in silenzio,
            // cioe' la risposta direbbe un numero e il registro ne conserverebbe un altro
            autenticaStaff();
            prenotazioneEsistente(prenotazione());

            // when/then
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta("10.005")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("due decimali");

            verify(pagamentoRepository, never()).save(any());
        }

        @Test
        @DisplayName("una data di incasso nel futuro e' 400")
        void registra_incassoNelFuturo_sollevaBadRequest() {
            // given: un versamento che deve ancora avvenire non e' un versamento, e
            // registrarlo vorrebbe dire un registro che dice di aver preso soldi che non
            // ci sono
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            PagamentoRequest richiesta = richiesta("100.00").incassatoIl(istante(ADESSO.plusHours(1)));

            // when/then
            assertThatThrownBy(() -> pagamentoService.registra(ID_PRENOTAZIONE, richiesta))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("futuro");
        }

        @Test
        @DisplayName("una data di incasso nel passato passa: un bonifico si registra dopo")
        void registra_incassoNelPassato_passa() {
            // given: il bonifico si vede sul conto il lunedi' e lo si registra il martedi'
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            staffEsistente();
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.save(any())).thenAnswer(invocazione -> invocazione.getArgument(0));
            LocalDateTime treGiorniFa = ADESSO.minusDays(3);

            // when
            pagamentoService.registra(ID_PRENOTAZIONE,
                    richiesta("100.00").incassatoIl(istante(treGiorniFa)));

            // then: in colonna finisce l'istante dichiarato, non adesso
            assertThat(pagamentoSalvato().getIncassatoIl()).isEqualTo(treGiorniFa);
        }
    }

    @Nested
    @DisplayName("il conto")
    class Conto {

        @Test
        @DisplayName("la caparra e' la percentuale configurata, arrotondata al centesimo")
        void elenca_conPercentuale_calcolaLaCaparra() {
            // given: il 30% di 360 fa 108
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            impostazioniConCaparra("30.00");
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(ID_PRENOTAZIONE))
                    .thenReturn(List.of());

            // when
            RiepilogoPagamentiResponse riepilogo = riepilogo(pagamentoService.elenca(ID_PRENOTAZIONE));

            // then
            assertThat(riepilogo.getCaparraDovuta()).isEqualByComparingTo("108.00");
            assertThat(riepilogo.getResiduo()).isEqualByComparingTo("360.00");
            assertThat(riepilogo.getSaldata()).isFalse();
        }

        @Test
        @DisplayName("percentuale a zero vuol dire nessuna caparra, non nessun conto")
        void elenca_senzaCaparra_restituisceZeroEIlResto() {
            // given: e' il default della migration, cioe' lo stato di un'installazione che
            // non ha configurato niente — e per un albergo che incassa all'arrivo e' anche
            // la politica giusta
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            impostazioniConCaparra("0.00");
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(ID_PRENOTAZIONE))
                    .thenReturn(List.of());

            // when
            RiepilogoPagamentiResponse riepilogo = riepilogo(pagamentoService.elenca(ID_PRENOTAZIONE));

            // then: zero dovuto in anticipo, ma il soggiorno costa lo stesso
            assertThat(riepilogo.getCaparraDovuta()).isEqualByComparingTo("0.00");
            assertThat(riepilogo.getImportoTotale()).isEqualByComparingTo("360.00");
        }

        @Test
        @DisplayName("saldata quando il residuo e' zero, anche se scritto con altri decimali")
        void elenca_tuttoIncassato_risultaSaldata() {
            // given: 360.00 di soggiorno e 360 di versamenti. Per BigDecimal i due numeri
            // sono uguali ma gli oggetti no, e con equals invece di compareTo questa
            // prenotazione risulterebbe non saldata
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            impostazioniConCaparra("30.00");
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(new BigDecimal("360"));
            when(pagamentoRepository.findByPrenotazioneIdOrderByIncassatoIlAscIdAsc(ID_PRENOTAZIONE))
                    .thenReturn(List.of());

            // when
            RiepilogoPagamentiResponse riepilogo = riepilogo(pagamentoService.elenca(ID_PRENOTAZIONE));

            // then
            assertThat(riepilogo.getSaldata()).isTrue();
            assertThat(riepilogo.getResiduo()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("chi registra finisce nel registro, e non lo sceglie chi chiama")
        void registra_scriveChiHaIncassato() {
            // given: un registro di denaro senza il nome di chi ci scrive non serve a
            // niente il giorno in cui i conti non tornano. La richiesta non ha nessun
            // campo per dirlo: si prende dal token
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            staffEsistente();
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.save(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            ApiBaseResponse risposta = pagamentoService.registra(ID_PRENOTAZIONE, richiesta("108.00"));

            // then
            assertThat(pagamentoSalvato().getRegistratoDa().getId()).isEqualTo(ID_STAFF);
            assertThat(risposta.getStatus()).isEqualTo(201);
            assertThat(((PagamentoResponse) risposta.getData()).getMetodo()).isEqualTo(MetodoPagamento.BONIFICO);
        }

        @Test
        @DisplayName("senza data dichiarata l'incasso vale adesso")
        void registra_senzaData_valeAdesso() {
            // given: e' il caso dei contanti e del POS, in cui il denaro arriva mentre lo
            // si sta registrando
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            staffEsistente();
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.save(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            pagamentoService.registra(ID_PRENOTAZIONE, richiesta("108.00"));

            // then
            assertThat(pagamentoSalvato().getIncassatoIl()).isEqualTo(ADESSO);
        }

        @Test
        @DisplayName("il lock si prende prima di contare, non dopo")
        void registra_prendeIlLockSullaPrenotazione() {
            // given: leggere la somma e scrivere la riga sono due gesti, e fra i due
            // un'altra transazione puo' fare lo stesso conto. Che il lock ci sia lo dice
            // questo test; che serva lo dice ConcorrenzaApiIT, dove due richieste partono
            // insieme davvero
            autenticaStaff();
            prenotazioneEsistente(prenotazione());
            staffEsistente();
            when(pagamentoRepository.sommaIncassata(ID_PRENOTAZIONE)).thenReturn(BigDecimal.ZERO);
            when(pagamentoRepository.save(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            pagamentoService.registra(ID_PRENOTAZIONE, richiesta("108.00"));

            // then
            verify(prenotazioneRepository).bloccaPerIncasso(ID_PRENOTAZIONE);
        }
    }

    // ---------------------------------------------------------------- supporto

    /** La prenotazione delle prove: tre notti a 120, intestata al cliente 7. */
    private Prenotazione prenotazione() {
        Utente cliente = new Utente();
        cliente.setId(ID_CLIENTE);

        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(ID_PRENOTAZIONE);
        prenotazione.setUtente(cliente);
        prenotazione.setDataCheckIn(ARRIVO);
        prenotazione.setDataCheckOut(ARRIVO.plusDays(3));
        prenotazione.setStato(StatoPrenotazione.CONFERMATA);
        prenotazione.setImportoTotale(TOTALE);
        return prenotazione;
    }

    private void prenotazioneEsistente(Prenotazione prenotazione) {
        when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(prenotazione));
    }

    private void staffEsistente() {
        Staff staff = new Staff();
        staff.setId(ID_STAFF);
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        when(staffRepository.findById(ID_STAFF)).thenReturn(Optional.of(staff));
    }

    private void impostazioniConCaparra(String percentuale) {
        ImpostazioniHotel impostazioni = new ImpostazioniHotel();
        impostazioni.setPercentualeCaparra(new BigDecimal(percentuale));
        lenient().when(impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA))
                .thenReturn(Optional.of(impostazioni));
    }

    /** Un bonifico dell'importo indicato, senza data: e' la forma minima della richiesta. */
    private PagamentoRequest richiesta(String importo) {
        return new PagamentoRequest()
                .importo(new BigDecimal(importo))
                .metodo(MetodoPagamento.BONIFICO);
    }

    /** Il pagamento passato al repository, che e' l'unico modo di guardare cosa si scrive. */
    private Pagamento pagamentoSalvato() {
        ArgumentCaptor<Pagamento> catturato = ArgumentCaptor.forClass(Pagamento.class);
        verify(pagamentoRepository).save(catturato.capture());
        return catturato.getValue();
    }

    private RiepilogoPagamentiResponse riepilogo(ApiBaseResponse risposta) {
        return (RiepilogoPagamentiResponse) risposta.getData();
    }

    /** Un istante locale nella forma in cui arriva dallo spec. */
    private OffsetDateTime istante(LocalDateTime locale) {
        return locale.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private void autenticaStaff() {
        autentica(TipoAccount.PERSONALE, "STAFF", ID_STAFF);
    }

    private void autenticaCliente(Long idCliente) {
        autentica(TipoAccount.CLIENTE, "USER", idCliente);
    }

    private void autentica(TipoAccount tipo, String ruolo, Long userId) {
        AppUserPrincipal principal = new AppUserPrincipal(
                tipo, userId, "mario.rossi@example.com", "hash", "Mario", "Rossi", ruolo, true, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
