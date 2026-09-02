package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.BloccoRequest;
import com.felixhotel.backend.entity.BloccoDisponibilita;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.OrigineBlocco;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.BloccoDisponibilitaMapper;
import com.felixhotel.backend.mapper.CameraMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.repository.BloccoDisponibilitaRepository;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.BloccoDisponibilitaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * I tre controlli che stanno prima di scrivere un blocco.
 *
 * <p><b>Il resto lo prova l'IT, ed e' li' che sta il valore del branch</b>: che il blocco
 * tolga davvero una camera alla disponibilita' e che il check-in non assegni una stanza
 * bloccata sono cose che vivono nel database — una query nativa e un vincolo di
 * esclusione — e con dei finti non le proverebbe niente.
 *
 * <p>Qui restano i rami che il Service decide da solo, e l'ordine in cui li decide: un
 * periodo che non sta in piedi deve fermare tutto <b>prima</b> di andare a cercare la
 * tipologia, altrimenti chi sbaglia le date si sente rispondere che la tipologia non
 * esiste.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BloccoDisponibilitaServiceImpl")
class BloccoDisponibilitaServiceImplTest {

    private static final Long ID_TIPOLOGIA = 3L;
    private static final Long ID_CAMERA = 12L;
    private static final LocalDate INIZIO = LocalDate.of(2026, 9, 10);
    private static final LocalDate FINE = LocalDate.of(2026, 9, 12);

    @Mock
    private BloccoDisponibilitaRepository bloccoRepository;
    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private BloccoDisponibilitaServiceImpl bloccoService;

    @BeforeEach
    void inizializza() {
        // Mapper veri e non finti, come negli altri unitari del progetto: la conversione
        // fra i due OrigineBlocco — quello dell'entita' e quello generato dallo spec — e'
        // logica, e con un finto non la proverebbe niente.
        bloccoService = new BloccoDisponibilitaServiceImpl(bloccoRepository,
                tipologiaCameraRepository, cameraRepository,
                new BloccoDisponibilitaMapper(
                        new TipologiaCameraMapper(new DotazioneMapper()),
                        new CameraMapper(new TipologiaCameraMapper(new DotazioneMapper()))),
                apiResponseMapper);
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("un blocco anonimo nasce MANUALE e senza camera")
        void crea_senzaCamera_nasceManuale() {
            // given
            tipologiaEsiste();
            when(bloccoRepository.saveAndFlush(any(BloccoDisponibilita.class)))
                    .thenAnswer(i -> i.getArgument(0));

            // when
            bloccoService.crea(richiesta());

            // then: l'origine non la sceglie chi chiama, ed e' il punto — lasciargliela
            // scegliere vorrebbe dire permettergli di scrivere un blocco che la sincronia
            // con un canale si sentira' in diritto di cancellare
            ArgumentCaptor<BloccoDisponibilita> salvato =
                    ArgumentCaptor.forClass(BloccoDisponibilita.class);
            verify(bloccoRepository).saveAndFlush(salvato.capture());
            assertThat(salvato.getValue().getOrigine()).isEqualTo(OrigineBlocco.MANUALE);
            assertThat(salvato.getValue().getCamera()).isNull();

            // e non ha nemmeno cercato la sovrapposizione: senza una camera non c'e'
            // niente con cui sovrapporsi, e due blocchi anonimi sono due unita' vendute
            verify(bloccoRepository, never())
                    .esisteSovrapposizioneSuCamera(any(), any(), any(), any());
        }

        @Test
        @DisplayName("un periodo di zero notti e' 400, e si ferma prima di cercare la tipologia")
        void crea_conDateUguali_sollevaBadRequest() {
            // when/then
            assertThatThrownBy(() -> bloccoService.crea(richiesta().dataFine(INIZIO)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("zero notti");

            // e l'ordine conta: chi sbaglia le date non deve sentirsi rispondere che la
            // tipologia non esiste
            verifyNoInteractions(tipologiaCameraRepository, bloccoRepository);
        }

        @Test
        @DisplayName("una fine che precede l'inizio e' 400")
        void crea_conFinePrimaDellInizio_sollevaBadRequest() {
            assertThatThrownBy(() -> bloccoService.crea(richiesta().dataFine(INIZIO.minusDays(1))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("su una tipologia che non esiste e' 404")
        void crea_conTipologiaInesistente_sollevaNotFound() {
            when(tipologiaCameraRepository.findById(ID_TIPOLOGIA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bloccoService.crea(richiesta()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("con una camera che non esiste e' 404")
        void crea_conCameraInesistente_sollevaNotFound() {
            tipologiaEsiste();
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bloccoService.crea(richiesta().cameraId(ID_CAMERA)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("con una camera di un'altra tipologia e' 400, non 404")
        void crea_conCameraDiAltraTipologia_sollevaBadRequest() {
            // given: la camera esiste, la tipologia esiste, e non stanno insieme
            tipologiaEsiste();
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(99L)));

            // when/then: 400 e non 404, e la differenza e' voluta — chi chiama non ha
            // indicato qualcosa che non c'e', ha indicato due cose che esistono e non
            // stanno insieme. E' anche il caso che il database non sa vedere: un CHECK
            // non legge un'altra tabella
            assertThatThrownBy(() -> bloccoService.crea(richiesta().cameraId(ID_CAMERA)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("tipologia");
        }

        @Test
        @DisplayName("su una camera gia' bloccata in quei giorni e' 409")
        void crea_conSovrapposizione_sollevaConflict() {
            // given
            tipologiaEsiste();
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(ID_TIPOLOGIA)));
            when(bloccoRepository.esisteSovrapposizioneSuCamera(ID_CAMERA, INIZIO, FINE, null))
                    .thenReturn(true);

            // when/then: due blocchi sulla stessa stanza toglierebbero due camere mentre
            // la stanza e' una
            assertThatThrownBy(() -> bloccoService.crea(richiesta().cameraId(ID_CAMERA)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("sovrappone");

            verify(bloccoRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("il duplicato che arriva dopo il controllo diventa 409, non 500")
        void crea_conViolazioneDelVincolo_sollevaConflict() {
            // given: il controllo preventivo passa, ma fra quello e la scrittura arriva
            // la richiesta gemella. E' la rete del vincolo di esclusione del V15
            tipologiaEsiste();
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera(ID_TIPOLOGIA)));
            when(bloccoRepository.esisteSovrapposizioneSuCamera(ID_CAMERA, INIZIO, FINE, null))
                    .thenReturn(false);
            when(bloccoRepository.saveAndFlush(any(BloccoDisponibilita.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("exclusion"));

            // when/then
            assertThatThrownBy(() -> bloccoService.crea(richiesta().cameraId(ID_CAMERA)))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("toglie il blocco")
        void elimina_conBloccoEsistente_loCancella() {
            BloccoDisponibilita blocco = new BloccoDisponibilita();
            when(bloccoRepository.findById(7L)).thenReturn(Optional.of(blocco));

            bloccoService.elimina(7L);

            verify(bloccoRepository).delete(blocco);
        }

        @Test
        @DisplayName("su un blocco che non esiste e' 404")
        void elimina_conBloccoInesistente_sollevaNotFound() {
            when(bloccoRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bloccoService.elimina(7L))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ---------------------------------------------------------------- supporto

    private BloccoRequest richiesta() {
        return new BloccoRequest()
                .tipologiaCameraId(ID_TIPOLOGIA)
                .dataInizio(INIZIO)
                .dataFine(FINE);
    }

    private void tipologiaEsiste() {
        when(tipologiaCameraRepository.findById(ID_TIPOLOGIA))
                .thenReturn(Optional.of(tipologia(ID_TIPOLOGIA)));
    }

    private TipologiaCamera tipologia(Long id) {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(id);
        tipologia.setNome("Doppia");
        return tipologia;
    }

    /** Una camera che appartiene alla tipologia indicata. */
    private Camera camera(Long idTipologia) {
        Camera camera = new Camera();
        camera.setId(ID_CAMERA);
        camera.setNumero("12");
        camera.setTipologiaCamera(tipologia(idTipologia));
        return camera;
    }
}
