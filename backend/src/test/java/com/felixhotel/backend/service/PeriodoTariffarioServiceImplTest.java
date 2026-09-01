package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.dto.PrezzoGiorno;
import com.felixhotel.backend.entity.PeriodoTariffario;
import com.felixhotel.backend.entity.PrezzoGiornoSettimana;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.PeriodoTariffarioMapper;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.PeriodoTariffarioServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link PeriodoTariffarioServiceImpl}: la classe sotto esame e'
 * vera, repository finti, mapper reale.
 *
 * <p><b>Cosa vede questa classe e cosa no.</b> Il calcolo del prezzo non passa
 * di qui — sta nella query, e a provarlo sono {@code PrenotazioneApiIT} e
 * {@code DisponibilitaApiIT} col database vero. Qui si prova cio' che il Service
 * decide: quali richieste rifiuta e con che codice, in che ordine guarda le cose,
 * e che la sostituzione dei prezzi per giorno passi dal metodo che tiene in piedi
 * {@code orphanRemoval} invece che da un assegnamento.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PeriodoTariffarioServiceImpl")
class PeriodoTariffarioServiceImplTest {

    private static final Long ID_TIPOLOGIA = 4L;
    private static final Long ID_PERIODO = 9L;
    private static final LocalDate INIZIO = LocalDate.of(2027, 7, 1);
    private static final LocalDate FINE = LocalDate.of(2027, 8, 31);

    @Mock
    private PeriodoTariffarioRepository periodoTariffarioRepository;
    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private PeriodoTariffarioServiceImpl periodoTariffarioService;

    @BeforeEach
    void inizializza() {
        // Mapper vero: la traduzione fra DayOfWeek e l'enum generato dallo spec e'
        // logica, e l'ordinamento della settimana pure — con un finto non li
        // eserciterebbe nessuno.
        periodoTariffarioService = new PeriodoTariffarioServiceImpl(periodoTariffarioRepository,
                tipologiaCameraRepository, new PeriodoTariffarioMapper(), apiResponseMapper);
    }

    private TipologiaCamera tipologia() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia Superior");
        tipologia.setCapienzaMax(2);
        tipologia.setPrezzoNotte(new BigDecimal("120.00"));
        return tipologia;
    }

    private PeriodoTariffarioRequest richiestaValida() {
        return new PeriodoTariffarioRequest()
                .nome("Alta stagione")
                .dataInizio(INIZIO)
                .dataFine(FINE)
                .prezzoNotte(new BigDecimal("180.00"))
                .soggiornoMinimo(3);
    }

    private PeriodoTariffario periodoEsistente() {
        PeriodoTariffario periodo = new PeriodoTariffario();
        periodo.setId(ID_PERIODO);
        periodo.setTipologiaCamera(tipologia());
        periodo.setNome("Alta stagione");
        periodo.setDataInizio(INIZIO);
        periodo.setDataFine(FINE);
        periodo.setPrezzoNotte(new BigDecimal("180.00"));
        periodo.setSoggiornoMinimo(1);
        return periodo;
    }

    private void tipologiaEsiste() {
        when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(true);
    }

    /** Il periodo che il repository restituirebbe come salvato. */
    private void salvataggioRiesce() {
        when(periodoTariffarioRepository.saveAndFlush(any(PeriodoTariffario.class)))
                .thenAnswer(invocazione -> invocazione.getArgument(0));
    }

    private PeriodoTariffario periodoSalvato() {
        ArgumentCaptor<PeriodoTariffario> salvato = ArgumentCaptor.forClass(PeriodoTariffario.class);
        verify(periodoTariffarioRepository).saveAndFlush(salvato.capture());
        return salvato.getValue();
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("chiede la pagina al repository e la impacchetta nella busta paginata")
        void elenca_conTipologiaEsistente_rispondePagina() {
            // given
            tipologiaEsiste();
            when(periodoTariffarioRepository.findByTipologiaCameraIdOrderByDataInizioAsc(
                    eq(ID_TIPOLOGIA), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(periodoEsistente())));

            // when
            periodoTariffarioService.elenca(ID_TIPOLOGIA, 1, 5);

            // then: la pagina richiesta e' quella ricevuta; l'ordine sta nel nome del
            // metodo derivato, quindi non c'e' nessun Sort da passare
            verify(periodoTariffarioRepository).findByTipologiaCameraIdOrderByDataInizioAsc(
                    ID_TIPOLOGIA, PageRequest.of(1, 5));
            verify(apiResponseMapper).toPaginatedResponse(eq(HttpStatus.OK), anyString(), any(), any());
        }

        @Test
        @DisplayName("su una tipologia inesistente solleva NotFound invece di dare una pagina vuota")
        void elenca_conTipologiaInesistente_sollevaNotFound() {
            // given
            when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(false);

            // when/then: senza questo controllo un id sbagliato e un calendario non
            // ancora configurato darebbero la stessa risposta
            assertThatThrownBy(() -> periodoTariffarioService.elenca(ID_TIPOLOGIA, 0, 20))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(periodoTariffarioRepository);
        }
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("salva il periodo con la tipologia dell'URL e i campi della richiesta")
        void crea_conRichiestaValida_salva() {
            // given
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologia()));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, null))
                    .thenReturn(List.of());
            salvataggioRiesce();

            // when
            periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida());

            // then
            PeriodoTariffario salvato = periodoSalvato();
            assertThat(salvato.getTipologiaCamera().getId()).isEqualTo(ID_TIPOLOGIA);
            assertThat(salvato.getNome()).isEqualTo("Alta stagione");
            assertThat(salvato.getSoggiornoMinimo()).isEqualTo(3);
            assertThat(salvato.getPrezzoNotte()).isEqualByComparingTo("180.00");

            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("i prezzi per giorno arrivano all'entita' col loro periodo gia' valorizzato")
        void crea_conPrezziGiorno_attaccaIlLatoProprietario() {
            // given: un sabato caro
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologia()));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, null))
                    .thenReturn(List.of());
            salvataggioRiesce();

            // when
            periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida()
                    .prezziGiorno(List.of(new PrezzoGiorno()
                            .giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                            .prezzo(new BigDecimal("210.00")))));

            // then: il lato proprietario e' quello del figlio, ed e' la colonna che
            // finisce davvero in database. Se restasse null l'INSERT fallirebbe su un
            // NOT NULL, cioe' un 500 su una richiesta corretta
            PeriodoTariffario salvato = periodoSalvato();
            assertThat(salvato.getPrezziGiorno()).singleElement().satisfies(prezzo -> {
                assertThat(prezzo.getGiorno()).isEqualTo(DayOfWeek.SATURDAY);
                assertThat(prezzo.getPrezzo()).isEqualByComparingTo("210.00");
                assertThat(prezzo.getPeriodoTariffario()).isSameAs(salvato);
            });
        }

        @Test
        @DisplayName("con la fine prima dell'inizio solleva BadRequest senza leggere niente")
        void crea_conDateInvertite_sollevaBadRequest() {
            // when/then
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA,
                    richiestaValida().dataFine(INIZIO.minusDays(1))))
                    .isInstanceOf(BadRequestException.class);

            // then: i controlli sulla sola richiesta vengono per primi, perche' sono
            // errori di chi chiama e non meritano un giro di query
            verifyNoInteractions(tipologiaCameraRepository, periodoTariffarioRepository);
        }

        @Test
        @DisplayName("con inizio e fine coincidenti passa: e' la notte di Capodanno")
        void crea_conUnaNotteSola_nonSolleva() {
            // given
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologia()));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, INIZIO, null))
                    .thenReturn(List.of());
            salvataggioRiesce();

            // when/then: nessuna eccezione. Gli estremi sono compresi tutti e due
            periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida().dataFine(INIZIO));

            assertThat(periodoSalvato().getDataFine()).isEqualTo(INIZIO);
        }

        @Test
        @DisplayName("un prezzo con tre decimali solleva BadRequest invece di essere troncato")
        void crea_conTreDecimali_sollevaBadRequest() {
            // when/then: la colonna e' NUMERIC(10,2), e arrotondare di nascosto vorrebbe
            // dire che la stessa risorsa dice due prezzi diversi a seconda di quando la
            // si chiede
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA,
                    richiestaValida().prezzoNotte(new BigDecimal("180.005"))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("anche il prezzo di un singolo giorno e' soggetto ai due decimali")
        void crea_conTreDecimaliSuUnGiorno_sollevaBadRequest() {
            // when/then: e' la stessa colonna, quindi la stessa regola. Il messaggio
            // nomina il giorno, perche' con sette righe possibili "il prezzo" non
            // basterebbe a capire quale correggere
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA,
                    richiestaValida().prezziGiorno(List.of(new PrezzoGiorno()
                            .giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                            .prezzo(new BigDecimal("210.001"))))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("SATURDAY");
        }

        @Test
        @DisplayName("lo stesso giorno due volte solleva BadRequest e li nomina tutti")
        void crea_conGiornoDuplicato_sollevaBadRequest() {
            // when/then: 400 e non 409 — non c'e' nessuno stato con cui la richiesta
            // confligga, e' la richiesta a contraddirsi, e lo si vede senza leggere
            // niente
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA,
                    richiestaValida().prezziGiorno(List.of(
                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                    .prezzo(new BigDecimal("210.00")),
                            new PrezzoGiorno().giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                                    .prezzo(new BigDecimal("220.00"))))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("SATURDAY");

            verifyNoInteractions(periodoTariffarioRepository);
        }

        @Test
        @DisplayName("su una tipologia inesistente solleva NotFound")
        void crea_conTipologiaInesistente_sollevaNotFound() {
            // given
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida()))
                    .isInstanceOf(NotFoundException.class);

            verify(periodoTariffarioRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("una sovrapposizione solleva Conflict nominando il periodo con cui si accavalla")
        void crea_conPeriodoSovrapposto_sollevaConflict() {
            // given: esiste gia' un periodo su quelle date
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologia()));
            PeriodoTariffario esistente = periodoEsistente();
            esistente.setNome("Ponte di primavera");
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, null))
                    .thenReturn(List.of(esistente));

            // when/then: il nome nel messaggio non e' un vezzo — chi configura un
            // calendario ha sotto gli occhi delle etichette, non degli id
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Ponte di primavera");

            verify(periodoTariffarioRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("la sovrapposizione che arriva dal database diventa 409 e non 500")
        void crea_conVincoloViolatoDalDatabase_sollevaConflict() {
            // given: il controllo preventivo non trova niente, ma fra quello e la
            // scrittura una richiesta gemella ha inserito lo stesso intervallo
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologia()));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, null))
                    .thenReturn(List.of());
            when(periodoTariffarioRepository.saveAndFlush(any(PeriodoTariffario.class)))
                    .thenThrow(new DataIntegrityViolationException("exclusion constraint"));

            // when/then: il vincolo di esclusione del V9 e' la garanzia, ma chi ci
            // sbatte non ha causato un guasto — ha solo perso una corsa
            assertThatThrownBy(() -> periodoTariffarioService.crea(ID_TIPOLOGIA, richiestaValida()))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("riscrive i campi e sostituisce i prezzi per giorno invece di aggiungerli")
        void aggiorna_sostituisceIPrezziGiorno() {
            // given: un periodo che oggi ha un sabato caro
            PeriodoTariffario esistente = periodoEsistente();
            esistente.sostituisciPrezziGiorno(new PeriodoTariffarioMapper().toPrezziGiorno(
                    richiestaValida().prezziGiorno(List.of(new PrezzoGiorno()
                            .giorno(PrezzoGiorno.GiornoEnum.SATURDAY)
                            .prezzo(new BigDecimal("210.00"))))));

            tipologiaEsiste();
            when(periodoTariffarioRepository.findByIdAndTipologiaCameraId(ID_PERIODO, ID_TIPOLOGIA))
                    .thenReturn(Optional.of(esistente));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, ID_PERIODO))
                    .thenReturn(List.of());
            salvataggioRiesce();

            // when: si manda una domenica al posto del sabato
            periodoTariffarioService.aggiorna(ID_TIPOLOGIA, ID_PERIODO, richiestaValida()
                    .prezziGiorno(List.of(new PrezzoGiorno()
                            .giorno(PrezzoGiorno.GiornoEnum.SUNDAY)
                            .prezzo(new BigDecimal("190.00")))));

            // then: il sabato non c'e' piu'. Senza orphanRemoval — cioe' se la lista
            // fosse riassegnata invece che svuotata — resterebbe in tabella a far prezzo
            assertThat(esistente.getPrezziGiorno()).singleElement()
                    .extracting(PrezzoGiornoSettimana::getGiorno)
                    .isEqualTo(DayOfWeek.SUNDAY);
        }

        @Test
        @DisplayName("esclude se stesso dal controllo di sovrapposizione")
        void aggiorna_escludeSeStesso() {
            // given
            tipologiaEsiste();
            when(periodoTariffarioRepository.findByIdAndTipologiaCameraId(ID_PERIODO, ID_TIPOLOGIA))
                    .thenReturn(Optional.of(periodoEsistente()));
            when(periodoTariffarioRepository.trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, ID_PERIODO))
                    .thenReturn(List.of());
            salvataggioRiesce();

            // when
            periodoTariffarioService.aggiorna(ID_TIPOLOGIA, ID_PERIODO, richiestaValida());

            // then: l'id passa alla query come "esclusa" — senza, riconfermare a un
            // periodo le proprie date darebbe 409 contro se stesso, e correggere il
            // solo prezzo sarebbe impossibile
            verify(periodoTariffarioRepository).trovaSovrapposti(ID_TIPOLOGIA, INIZIO, FINE, ID_PERIODO);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }

        @Test
        @DisplayName("un periodo di un'altra tipologia solleva NotFound invece di essere modificato")
        void aggiorna_conPeriodoDiAltraTipologia_sollevaNotFound() {
            // given: la tipologia c'e', il periodo no — perche' la query lo cerca
            // dentro di lei
            tipologiaEsiste();
            when(periodoTariffarioRepository.findByIdAndTipologiaCameraId(ID_PERIODO, ID_TIPOLOGIA))
                    .thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> periodoTariffarioService.aggiorna(ID_TIPOLOGIA, ID_PERIODO,
                    richiestaValida()))
                    .isInstanceOf(NotFoundException.class);

            verify(periodoTariffarioRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("cancella il periodo trovato dentro la sua tipologia")
        void elimina_conPeriodoEsistente_cancella() {
            // given
            tipologiaEsiste();
            PeriodoTariffario esistente = periodoEsistente();
            when(periodoTariffarioRepository.findByIdAndTipologiaCameraId(ID_PERIODO, ID_TIPOLOGIA))
                    .thenReturn(Optional.of(esistente));

            // when
            periodoTariffarioService.elimina(ID_TIPOLOGIA, ID_PERIODO);

            // then: 'data' e' null, come per ogni eliminazione del progetto
            verify(periodoTariffarioRepository).delete(esistente);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), isNull());
        }

        @Test
        @DisplayName("su un periodo inesistente solleva NotFound e non cancella niente")
        void elimina_conPeriodoInesistente_sollevaNotFound() {
            // given
            tipologiaEsiste();
            when(periodoTariffarioRepository.findByIdAndTipologiaCameraId(ID_PERIODO, ID_TIPOLOGIA))
                    .thenReturn(Optional.empty());

            // when/then
            assertThatThrownBy(() -> periodoTariffarioService.elimina(ID_TIPOLOGIA, ID_PERIODO))
                    .isInstanceOf(NotFoundException.class);

            verify(periodoTariffarioRepository, never()).delete(any());
        }
    }
}
