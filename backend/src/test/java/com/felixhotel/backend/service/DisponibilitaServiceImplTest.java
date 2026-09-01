package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.DisponibilitaTipologia;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DisponibilitaMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.ConteggioCamere;
import com.felixhotel.backend.repository.OccupazioneTipologia;
import com.felixhotel.backend.repository.PeriodoTariffarioRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.PreventivoTipologia;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.DisponibilitaServiceImpl;
import com.felixhotel.backend.service.impl.DurataSoggiorno;
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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link DisponibilitaServiceImpl}: la classe sotto esame e'
 * vera, repository finti.
 *
 * <p>I mapper sono <b>reali</b>, come in {@code PrenotazioneServiceImplTest}:
 * costa niente e toglie di mezzo un finto che direbbe sempre quel che gli si e'
 * detto di dire.
 *
 * <p><b>Cosa vede questa classe e cosa no.</b> Il calcolo della notte peggiore e
 * il calcolo del prezzo li fa il database, quindi qui sono numeri finti: a
 * provarli davvero e' {@code DisponibilitaApiIT}. Quello che si prova qui e' il
 * resto — la sottrazione, i valori che mancano dalle mappe, la pagina vuota che
 * non deve arrivare alle query, i filtri che arrivano interi — cioe' i rami che
 * l'integrazione attraversa senza distinguerli.
 *
 * <p><b>Dal 2026-09-01 a impaginare e' il preventivo</b> e non piu' l'elenco
 * delle tipologie, perche' il filtro di prezzo guarda quanto costano davvero
 * quelle date. Si vede nella forma dei finti: la pagina la decide
 * {@code PeriodoTariffarioRepository.preventivi}, e le tipologie si rileggono
 * dopo per gli id che ne sono usciti.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisponibilitaServiceImpl")
class DisponibilitaServiceImplTest {

    private static final Long ID_TIPOLOGIA = 4L;
    private static final LocalDate ARRIVO = LocalDate.of(2026, 9, 10);
    private static final LocalDate PARTENZA = LocalDate.of(2026, 9, 13);
    private static final BigDecimal PREZZO = new BigDecimal("120.00");

    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private PeriodoTariffarioRepository periodoTariffarioRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private DisponibilitaServiceImpl disponibilitaService;

    @BeforeEach
    void inizializza() {
        DisponibilitaMapper disponibilitaMapper = new DisponibilitaMapper(
                new TipologiaCameraMapper(new DotazioneMapper()));

        disponibilitaService = new DisponibilitaServiceImpl(tipologiaCameraRepository, cameraRepository,
                prenotazioneRepository, periodoTariffarioRepository, disponibilitaMapper, apiResponseMapper);
    }

    private TipologiaCamera tipologia() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia Superior");
        tipologia.setCapienzaMax(2);
        tipologia.setPrezzoNotte(PREZZO);
        return tipologia;
    }

    /** Quanto la query delle tariffe dice che costa il soggiorno, e il minimo che impone. */
    private PreventivoTipologia preventivo(Long idTipologia, String importo, int soggiornoMinimo) {
        return new PreventivoTipologia() {
            @Override
            public Long getTipologiaCameraId() {
                return idTipologia;
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

    /**
     * Dice ai finti che la pagina contiene quella tipologia, col preventivo dato.
     *
     * <p>Sono due finti e non uno perche' i due passi sono due: il preventivo
     * decide chi entra nella pagina, e le entita' si rileggono per quegli id.
     */
    private void paginaCon(TipologiaCamera tipologia, PreventivoTipologia preventivo) {
        when(periodoTariffarioRepository.preventivi(isNull(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(preventivo)));
        when(tipologiaCameraRepository.findAllById(anyCollection())).thenReturn(List.of(tipologia));
    }

    /** La pagina in cui nessuna tipologia passa i filtri. */
    private void paginaVuota() {
        when(periodoTariffarioRepository.preventivi(isNull(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    /** Quante camere fisiche il conteggio raggruppato attribuisce a una tipologia. */
    private ConteggioCamere conteggio(Long idTipologia, long totale) {
        return new ConteggioCamere() {
            @Override
            public Long getTipologiaCameraId() {
                return idTipologia;
            }

            @Override
            public long getTotale() {
                return totale;
            }
        };
    }

    /** Quante ne risultano impegnate nella notte peggiore. */
    private OccupazioneTipologia occupazione(Long idTipologia, long occupate) {
        return new OccupazioneTipologia() {
            @Override
            public Long getTipologiaCameraId() {
                return idTipologia;
            }

            @Override
            public long getOccupate() {
                return occupate;
            }
        };
    }

    /** Le righe che il service ha costruito, prese dal mapper della busta. */
    @SuppressWarnings("unchecked")
    private List<DisponibilitaTipologia> righeProdotte() {
        ArgumentCaptor<List<DisponibilitaTipologia>> righe = ArgumentCaptor.forClass(List.class);
        verify(apiResponseMapper).toPaginatedResponse(eq(HttpStatus.OK), anyString(), righe.capture(), any());
        return righe.getValue();
    }

    private void cerca() {
        disponibilitaService.cerca(ARRIVO, PARTENZA, null, null, null, 0, 20);
    }

    @Nested
    @DisplayName("cerca")
    class Cerca {

        @Test
        @DisplayName("sottrae alle camere gli occupati della notte peggiore")
        void cerca_conCamereLibere_calcolaLaDifferenza() {
            // given: tre camere, una impegnata nella notte piu' affollata
            paginaCon(tipologia(), preventivo(ID_TIPOLOGIA, "360.00", 1));
            when(cameraRepository.contaPerTipologia(anyCollection()))
                    .thenReturn(List.of(conteggio(ID_TIPOLOGIA, 3)));
            when(prenotazioneRepository.occupazioneMassima(anyCollection(), eq(ARRIVO), eq(PARTENZA),
                    anyCollection(), isNull())).thenReturn(List.of(occupazione(ID_TIPOLOGIA, 1)));

            // when
            cerca();

            // then: due libere, e l'importo e' quello che la query delle tariffe ha
            // calcolato — questa classe non lo ricalcola, e non deve
            assertThat(righeProdotte()).singleElement().satisfies(riga -> {
                assertThat(riga.getCamereDisponibili()).isEqualTo(2);
                assertThat(riga.getImportoTotale()).isEqualByComparingTo("360.00");
                assertThat(riga.getTipologia().getId()).isEqualTo(ID_TIPOLOGIA);
            });
        }

        @Test
        @DisplayName("il soggiorno minimo del preventivo finisce nella risposta")
        void cerca_conSoggiornoMinimo_loRiporta() {
            // given: in questo periodo la tipologia vuole tre notti
            paginaCon(tipologia(), preventivo(ID_TIPOLOGIA, "540.00", 3));
            when(cameraRepository.contaPerTipologia(anyCollection()))
                    .thenReturn(List.of(conteggio(ID_TIPOLOGIA, 1)));
            when(prenotazioneRepository.occupazioneMassima(anyCollection(), any(), any(),
                    anyCollection(), isNull())).thenReturn(List.of());

            // when
            cerca();

            // then: la riga resta e porta il minimo con se'. Toglierla sarebbe peggio
            // che mostrarla: chi cerca deve poter capire che basta allungare di una
            // notte, invece di credere che non ci sia posto
            assertThat(righeProdotte()).singleElement()
                    .extracting(DisponibilitaTipologia::getSoggiornoMinimo)
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("una tipologia assente dal conteggio delle camere vale zero, non sparisce")
        void cerca_conTipologiaSenzaCamere_daZero() {
            // given: il group by non restituisce nessuna riga per questa tipologia,
            // perche' di camere non ne ha nessuna
            paginaCon(tipologia(), preventivo(ID_TIPOLOGIA, "360.00", 1));
            when(cameraRepository.contaPerTipologia(anyCollection())).thenReturn(List.of());
            when(prenotazioneRepository.occupazioneMassima(anyCollection(), any(), any(),
                    anyCollection(), isNull())).thenReturn(List.of());

            // when
            cerca();

            // then: la riga resta, a zero. Farla sparire direbbe al cliente che quella
            // tipologia non esiste, che e' un'altra cosa
            assertThat(righeProdotte()).singleElement()
                    .extracting(DisponibilitaTipologia::getCamereDisponibili)
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("non scende sotto zero se gli occupati superano le camere")
        void cerca_conOccupatiOltreLeCamere_siFermaAZero() {
            // given: due camere e tre impegnate. E' uno stato che oggi non si puo'
            // produrre dagli endpoint — la conferma non lo permetterebbe — ma i due
            // numeri arrivano da due query diverse, e il giorno che non venissero dalla
            // stessa transazione un negativo finirebbe dritto nella risposta
            paginaCon(tipologia(), preventivo(ID_TIPOLOGIA, "360.00", 1));
            when(cameraRepository.contaPerTipologia(anyCollection()))
                    .thenReturn(List.of(conteggio(ID_TIPOLOGIA, 2)));
            when(prenotazioneRepository.occupazioneMassima(anyCollection(), any(), any(),
                    anyCollection(), isNull())).thenReturn(List.of(occupazione(ID_TIPOLOGIA, 3)));

            // when
            cerca();

            // then: zero, non meno uno
            assertThat(righeProdotte()).singleElement()
                    .extracting(DisponibilitaTipologia::getCamereDisponibili)
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("con la pagina vuota non interroga camere, prenotazioni ne' tipologie")
        void cerca_conPaginaVuota_nonInterrogaNiente() {
            // given: nessuna tipologia passa i filtri — l'ultima pagina di un elenco, o
            // una fascia di prezzo in cui non c'e' niente
            paginaVuota();

            // when
            cerca();

            // then: le tre query non partono, e non e' un risparmio: due di loro
            // filtrano con un "in (:ids)", e un "in ()" non e' SQL valido
            verifyNoInteractions(cameraRepository);
            verify(tipologiaCameraRepository, never()).findAllById(anyCollection());
            verify(prenotazioneRepository, never()).occupazioneMassima(anyCollection(), any(), any(),
                    anyCollection(), any());
            assertThat(righeProdotte()).isEmpty();
        }

        @Test
        @DisplayName("i filtri e le date arrivano alla query dei preventivi")
        void cerca_conFiltri_liPassaAlRepository() {
            // given
            paginaVuota();

            // when: si cerca per tre persone in una fascia di prezzo
            disponibilitaService.cerca(ARRIVO, PARTENZA, 3,
                    new BigDecimal("80.00"), new BigDecimal("200.00"), 1, 5);

            // then: i filtri sono quelli ricevuti, e ci vanno insieme alle date — perche'
            // dal 2026-09-01 la fascia di prezzo si applica a quanto costano davvero
            // quelle notti, non al listino della tipologia.
            // Il Pageable non porta nessun Sort: l'ordine e' scritto dentro la query,
            // che e' un group by e non lascia niente da scegliere a chi chiama
            verify(periodoTariffarioRepository).preventivi(isNull(), eq(3),
                    eq(new BigDecimal("80.00")), eq(new BigDecimal("200.00")),
                    eq(ARRIVO), eq(PARTENZA), eq(PageRequest.of(1, 5)));
        }

        @Test
        @DisplayName("con la partenza prima dell'arrivo solleva BadRequest senza toccare il database")
        void cerca_conDateInvertite_sollevaBadRequest() {
            // when/then: stesso giorno, cioe' zero notti
            assertThatThrownBy(() -> disponibilitaService.cerca(ARRIVO, ARRIVO, null, null, null, 0, 20))
                    .isInstanceOf(BadRequestException.class);

            // then: il controllo viene prima di ogni lettura, perche' una richiesta che
            // non vuol dire niente non merita nemmeno una query
            verifyNoInteractions(tipologiaCameraRepository, cameraRepository, prenotazioneRepository,
                    periodoTariffarioRepository);
        }

        @Test
        @DisplayName("un soggiorno oltre il tetto di notti solleva BadRequest, come la creazione")
        void cerca_oltreIlTettoDiNotti_sollevaBadRequest() {
            // given: una notte piu' del massimo
            LocalDate partenzaTroppoLontana = ARRIVO.plusDays(DurataSoggiorno.MASSIMO_NOTTI + 1);

            // when/then: lo stesso rifiuto che darebbe la creazione. Prima del
            // 2026-09-01 qui non c'era nessun tetto, e la conseguenza era che la ricerca
            // mostrava il preventivo di un soggiorno che la creazione poi rifiutava —
            // due endpoint che dicevano cose diverse sulla stessa richiesta
            assertThatThrownBy(() -> disponibilitaService.cerca(ARRIVO, partenzaTroppoLontana,
                    null, null, null, 0, 20))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(String.valueOf(DurataSoggiorno.MASSIMO_NOTTI));

            verifyNoInteractions(periodoTariffarioRepository);
        }

        @Test
        @DisplayName("il tetto di notti esatto passa: e' un massimo, non un limite superato")
        void cerca_conIlTettoEsatto_nonSolleva() {
            // given: esattamente il massimo di notti
            paginaVuota();

            // when/then: nessuna eccezione. Il confronto e' "piu' di", non "da"
            disponibilitaService.cerca(ARRIVO, ARRIVO.plusDays(DurataSoggiorno.MASSIMO_NOTTI),
                    null, null, null, 0, 20);

            verify(periodoTariffarioRepository).preventivi(isNull(), isNull(), isNull(), isNull(),
                    any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("un arrivo nel passato non e' un errore: si sta guardando, non prenotando")
        void cerca_conArrivoPassato_nonSollevaNiente() {
            // given: un periodo interamente passato
            paginaVuota();

            // when/then: nessuna eccezione, al contrario della creazione di una
            // prenotazione. Chi sta al banco deve poter controllare la settimana scorsa
            disponibilitaService.cerca(LocalDate.of(2020, 1, 10), LocalDate.of(2020, 1, 12),
                    null, null, null, 0, 20);

            verify(periodoTariffarioRepository).preventivi(isNull(), isNull(), isNull(), isNull(),
                    any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("gli stati che occupano arrivano alla query come nomi")
        void cerca_passaINomiDegliStati() {
            // given
            paginaCon(tipologia(), preventivo(ID_TIPOLOGIA, "360.00", 1));
            when(cameraRepository.contaPerTipologia(anyCollection()))
                    .thenReturn(List.of(conteggio(ID_TIPOLOGIA, 1)));
            when(prenotazioneRepository.occupazioneMassima(anyCollection(), any(), any(),
                    anyCollection(), isNull())).thenReturn(List.of());

            // when
            cerca();

            // then: la query e' nativa, quindi non puo' ricevere enum — e i nomi sono
            // quelli derivati da occupaCamera(), non un elenco riscritto qui
            ArgumentCaptor<Collection<String>> stati = ArgumentCaptor.forClass(Collection.class);
            verify(prenotazioneRepository).occupazioneMassima(anyCollection(), eq(ARRIVO), eq(PARTENZA),
                    stati.capture(), isNull());
            assertThat(stati.getValue()).containsExactlyInAnyOrder("CONFERMATA", "CHECK_IN", "CHECK_OUT");
        }
    }
}
