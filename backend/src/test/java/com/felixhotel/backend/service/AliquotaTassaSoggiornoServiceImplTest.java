package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.AliquotaTassaSoggiornoRequest;
import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TassaSoggiornoMapper;
import com.felixhotel.backend.repository.AliquotaTassaSoggiornoRepository;
import com.felixhotel.backend.service.impl.AliquotaTassaSoggiornoServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitari del CRUD delle aliquote.
 *
 * <p>Sono pochi e mirati, al contrario di quelli del calcolo: qui i rami veri sono
 * tre — l'ordine delle date, i decimali, la sovrapposizione — e tutto il resto e'
 * cablaggio che tocca all'IT. Il mapper e' vero come sempre.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AliquotaTassaSoggiornoServiceImpl")
class AliquotaTassaSoggiornoServiceImplTest {

    private static final Long ID_ALIQUOTA = 5L;
    private static final LocalDate INIZIO = LocalDate.of(2027, 1, 1);
    private static final LocalDate FINE = LocalDate.of(2027, 12, 31);

    @Mock
    private AliquotaTassaSoggiornoRepository aliquotaRepository;

    private AliquotaTassaSoggiornoServiceImpl aliquotaService;

    @BeforeEach
    void inizializza() {
        aliquotaService = new AliquotaTassaSoggiornoServiceImpl(aliquotaRepository,
                new TassaSoggiornoMapper(), new ApiResponseMapper());
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("scrive tutti i campi, facoltativi compresi")
        void crea_conDatiValidi_scrive() {
            // given
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, null)).thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            aliquotaService.crea(richiesta());

            // then
            ArgumentCaptor<AliquotaTassaSoggiorno> salvata =
                    ArgumentCaptor.forClass(AliquotaTassaSoggiorno.class);
            verify(aliquotaRepository).saveAndFlush(salvata.capture());
            assertThat(salvata.getValue().getImportoPerPersonaNotte()).isEqualByComparingTo("2.00");
            assertThat(salvata.getValue().getNottiMassimeTassate()).isEqualTo(5);
            assertThat(salvata.getValue().getEtaEsenzione()).isEqualTo(12);
        }

        @Test
        @DisplayName("i due campi facoltativi omessi restano vuoti")
        void crea_senzaTettoNeEta_liLasciaVuoti() {
            // given: un comune che tassa tutti e senza limite di notti
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, null)).thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            aliquotaService.crea(richiesta().nottiMassimeTassate(null).etaEsenzione(null));

            // then: null e non zero, che vorrebbe dire un'altra cosa
            ArgumentCaptor<AliquotaTassaSoggiorno> salvata =
                    ArgumentCaptor.forClass(AliquotaTassaSoggiorno.class);
            verify(aliquotaRepository).saveAndFlush(salvata.capture());
            assertThat(salvata.getValue().getNottiMassimeTassate()).isNull();
            assertThat(salvata.getValue().getEtaEsenzione()).isNull();
        }

        @Test
        @DisplayName("con la data di fine prima di quella di inizio risponde 400")
        void crea_conDateInvertite_sollevaBadRequest() {
            assertThatThrownBy(() -> aliquotaService.crea(
                    richiesta().dataFine(INIZIO.minusDays(1))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("precedere");
        }

        @Test
        @DisplayName("un'aliquota di un giorno solo e' legittima")
        void crea_conUnGiornoSolo_passa() {
            // given / when / then: come un periodo tariffario di Capodanno
            when(aliquotaRepository.trovaSovrapposte(INIZIO, INIZIO, null)).thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            aliquotaService.crea(richiesta().dataFine(INIZIO));

            verify(aliquotaRepository).saveAndFlush(any(AliquotaTassaSoggiorno.class));
        }

        @Test
        @DisplayName("con piu' di due decimali risponde 400")
        void crea_conTreDecimali_sollevaBadRequest() {
            // given / when / then: la colonna e' NUMERIC(10,2) e Postgres troncherebbe in
            // silenzio, cioe' la risposta direbbe un numero e il database ne conserverebbe
            // un altro
            assertThatThrownBy(() -> aliquotaService.crea(
                    richiesta().importoPerPersonaNotte(new BigDecimal("2.005"))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("due decimali");
        }

        @Test
        @DisplayName("gli zeri in coda non contano come decimali")
        void crea_conZeriInCoda_passa() {
            // given / when / then: 2.5000 e' 2.50, e rifiutarlo sarebbe pignoleria su una
            // scrittura legittima. E' lo stripTrailingZeros a distinguerli
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, null)).thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            aliquotaService.crea(richiesta().importoPerPersonaNotte(new BigDecimal("2.5000")));

            verify(aliquotaRepository).saveAndFlush(any(AliquotaTassaSoggiorno.class));
        }

        @Test
        @DisplayName("con le date sovrapposte risponde 409, e dice con quale")
        void crea_conDateSovrapposte_sollevaConflict() {
            // given
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, null))
                    .thenReturn(List.of(aliquotaEsistente()));

            // when / then: il messaggio porta le date dell'altra, altrimenti chi lo
            // riceve deve andarsele a cercare
            assertThatThrownBy(() -> aliquotaService.crea(richiesta()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2027-06-01");
        }

        @Test
        @DisplayName("la sovrapposizione che arriva dopo il controllo diventa 409, non 500")
        void crea_conSovrapposizioneConcorrente_traduceInConflict() {
            // given: il controllo preventivo non vede niente, il vincolo di esclusione si'
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, null)).thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenThrow(new DataIntegrityViolationException("ex_aliquota..."));

            // when / then: e' la rete sotto al controllo, e copre la richiesta gemella
            // arrivata nel frattempo — l'unica cosa che nessun controllo preventivo vede
            assertThatThrownBy(() -> aliquotaService.crea(richiesta()))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("riconfermare le proprie date non e' un conflitto")
        void aggiorna_conLeStesseDate_nonEUnConflitto() {
            // given
            when(aliquotaRepository.findById(ID_ALIQUOTA))
                    .thenReturn(Optional.of(aliquotaEsistente()));
            when(aliquotaRepository.trovaSovrapposte(INIZIO, FINE, ID_ALIQUOTA))
                    .thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            aliquotaService.aggiorna(ID_ALIQUOTA, richiesta());

            // then: l'aliquota stessa e' esclusa dalla ricerca, altrimenti correggere il
            // solo importo sarebbe impossibile
            verify(aliquotaRepository).trovaSovrapposte(INIZIO, FINE, ID_ALIQUOTA);
        }

        @Test
        @DisplayName("i campi facoltativi omessi vengono azzerati")
        void aggiorna_senzaTetto_loAzzera() {
            // given: un'aliquota che il tetto ce l'ha
            AliquotaTassaSoggiorno esistente = aliquotaEsistente();
            esistente.setNottiMassimeTassate(5);
            when(aliquotaRepository.findById(ID_ALIQUOTA)).thenReturn(Optional.of(esistente));
            when(aliquotaRepository.trovaSovrapposte(any(), any(), eq(ID_ALIQUOTA)))
                    .thenReturn(List.of());
            when(aliquotaRepository.saveAndFlush(any(AliquotaTassaSoggiorno.class)))
                    .thenAnswer(invocazione -> invocazione.getArgument(0));

            // when
            aliquotaService.aggiorna(ID_ALIQUOTA, richiesta().nottiMassimeTassate(null));

            // then: e' quel che una PUT promette, e qui la conseguenza si vede sul conto
            // di qualcuno — da adesso si paga ogni notte
            assertThat(esistente.getNottiMassimeTassate()).isNull();
        }

        @Test
        @DisplayName("su un'aliquota che non esiste risponde 404")
        void aggiorna_suAliquotaInesistente_sollevaNotFound() {
            when(aliquotaRepository.findById(ID_ALIQUOTA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> aliquotaService.aggiorna(ID_ALIQUOTA, richiesta()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("i controlli sulla richiesta vengono prima della ricerca")
        void aggiorna_conDateInvertite_nonCercaNiente() {
            // given / when / then: una richiesta che non sta in piedi da sola non merita
            // nemmeno una query — ed e' anche il motivo per cui questo test non prepara
            // nessun mock
            assertThatThrownBy(() -> aliquotaService.aggiorna(ID_ALIQUOTA,
                    richiesta().dataFine(INIZIO.minusDays(1))))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("cancella l'aliquota")
        void elimina_conAliquotaEsistente_laCancella() {
            AliquotaTassaSoggiorno esistente = aliquotaEsistente();
            when(aliquotaRepository.findById(ID_ALIQUOTA)).thenReturn(Optional.of(esistente));

            aliquotaService.elimina(ID_ALIQUOTA);

            verify(aliquotaRepository).delete(esistente);
        }

        @Test
        @DisplayName("su un'aliquota che non esiste risponde 404")
        void elimina_suAliquotaInesistente_sollevaNotFound() {
            when(aliquotaRepository.findById(ID_ALIQUOTA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> aliquotaService.elimina(ID_ALIQUOTA))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ---- fabbriche ---------------------------------------------------------------

    private AliquotaTassaSoggiornoRequest richiesta() {
        return new AliquotaTassaSoggiornoRequest()
                .dataInizio(INIZIO)
                .dataFine(FINE)
                .importoPerPersonaNotte(new BigDecimal("2.00"))
                .nottiMassimeTassate(5)
                .etaEsenzione(12);
    }

    /** Un'aliquota gia' in database, con date diverse da quelle di {@link #richiesta()}. */
    private AliquotaTassaSoggiorno aliquotaEsistente() {
        AliquotaTassaSoggiorno aliquota = new AliquotaTassaSoggiorno();
        aliquota.setId(ID_ALIQUOTA);
        aliquota.setDataInizio(LocalDate.of(2027, 6, 1));
        aliquota.setDataFine(LocalDate.of(2027, 6, 30));
        aliquota.setImportoPerPersonaNotte(new BigDecimal("3.00"));
        return aliquota;
    }
}
