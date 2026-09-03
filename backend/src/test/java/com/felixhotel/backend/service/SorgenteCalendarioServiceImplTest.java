package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.SincronizzazioneResponse;
import com.felixhotel.backend.dto.SorgenteCalendarioRequest;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.SorgenteCalendario;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.EsitoSincronizzazione;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.SorgenteCalendarioMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.SorgenteCalendarioRepository;
import com.felixhotel.backend.service.impl.IndirizzoConsentito;
import com.felixhotel.backend.service.impl.LettoreFeedRemoto.FeedNonRaggiungibileException;
import com.felixhotel.backend.service.impl.SincronizzatoreSorgente;
import com.felixhotel.backend.service.impl.SincronizzatoreSorgente.EsitoSorgente;
import com.felixhotel.backend.service.impl.SorgenteCalendarioServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * I controlli sulla registrazione di una sorgente, e la regola che tiene in piedi il giro
 * periodico.
 *
 * <p><b>La parte che conta e' la seconda</b>: <i>nessuna sorgente puo' far fallire le
 * altre</i>. E' l'unica cosa che questa classe decida davvero, ed e' anche quella che si
 * romperebbe senza far rumore — un canale in manutenzione e' la cosa piu' normale che
 * possa capitare a un giro periodico, e se interrompesse il giro lascerebbe tutti gli
 * altri fermi senza che nessuno lo sappia.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SorgenteCalendarioServiceImpl")
class SorgenteCalendarioServiceImplTest {

    private static final Long ID_CAMERA = 12L;
    /**
     * <b>Numerico e pubblico, non un nome</b>: dal 2026-09-03 la registrazione risolve il
     * nome per rifiutare gli indirizzi interni, e un {@code .invalid} — che per specifica
     * non si risolve mai — verrebbe rifiutato prima di arrivare a quel che questi test
     * vogliono provare. Nessuno lo interroga: {@code crea} non scarica niente.
     */
    private static final String URL = "https://8.8.8.8/calendario.ics";

    @Mock
    private SorgenteCalendarioRepository sorgenteRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private SincronizzatoreSorgente sincronizzatore;
    @Mock
    private SorgenteCalendarioMapper sorgenteMapper;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private SorgenteCalendarioServiceImpl sorgenteService;

    @BeforeEach
    void setUp() {
        // Chiuso come in produzione: il test dello schema rifiutato non proverebbe niente
        // con la configurazione aperta dei profili dev e test
        sorgenteService = new SorgenteCalendarioServiceImpl(sorgenteRepository, cameraRepository,
                sincronizzatore, sorgenteMapper, apiResponseMapper, new IndirizzoConsentito(false));
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("registra la sorgente sulla camera indicata")
        void crea_registra() {
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera()));
            when(sorgenteRepository.saveAndFlush(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            sorgenteService.crea(richiesta(URL));

            ArgumentCaptor<SorgenteCalendario> catturata =
                    ArgumentCaptor.forClass(SorgenteCalendario.class);
            verify(sorgenteRepository).saveAndFlush(catturata.capture());

            assertThat(catturata.getValue().getCamera().getId()).isEqualTo(ID_CAMERA);
            assertThat(catturata.getValue().getNome()).isEqualTo("Booking");
            assertThat(catturata.getValue().getUrl()).isEqualTo(URL);
            // Nasce senza esito: e' l'unico modo di distinguere "non e' ancora partita"
            // da "e' andata bene"
            assertThat(catturata.getValue().getUltimoEsito()).isNull();
            assertThat(catturata.getValue().getUltimaSincronizzazione()).isNull();
        }

        @Test
        @DisplayName("non scarica niente: l'indirizzo si prova al primo giro")
        void crea_nonScarica() {
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera()));
            when(sorgenteRepository.saveAndFlush(any())).thenAnswer(invocazione -> invocazione.getArgument(0));

            sorgenteService.crea(richiesta(URL));

            // Un canale lento o momentaneamente giu' non deve impedire di salvare una
            // configurazione giusta
            verifyNoInteractions(sincronizzatore);
        }

        @Test
        @DisplayName("rifiuta uno schema che non sia http o https")
        void crea_schemaNonAmmesso_400() {
            // Non e' una formalita' sul formato: quel valore diventa una richiesta che
            // parte dal nostro server, e file: servirebbe solo a fargli leggere qualcosa
            // che non e' il calendario di un canale
            assertThatThrownBy(() -> sorgenteService.crea(richiesta("file:///etc/passwd")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("http o https");

            verifyNoInteractions(cameraRepository);
        }

        @Test
        @DisplayName("rifiuta un indirizzo che non e' scritto in modo valido")
        void crea_indirizzoMalformato_400() {
            assertThatThrownBy(() -> sorgenteService.crea(richiesta("non un indirizzo")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("lo schema si guarda prima della camera")
        void crea_schemaSbagliatoECameraInesistente_diceLoSchema() {
            // L'ordine dei controlli, come per i blocchi: chi ha incollato un indirizzo
            // sbagliato non deve sentirsi rispondere che la camera non esiste
            assertThatThrownBy(() -> sorgenteService.crea(richiesta("ftp://esempio.invalid/x.ics")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("404 se la camera non esiste")
        void crea_cameraInesistente_404() {
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sorgenteService.crea(richiesta(URL)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("409 se quell'indirizzo e' gia' registrato su quella camera")
        void crea_doppione_409() {
            // Leggerlo due volte toglierebbe due unita' mentre la stanza e' una: e' lo
            // stesso danno del vincolo di esclusione dei blocchi, preso un passo prima
            when(cameraRepository.findById(ID_CAMERA)).thenReturn(Optional.of(camera()));
            when(sorgenteRepository.existsByCameraIdAndUrl(ID_CAMERA, URL)).thenReturn(true);

            assertThatThrownBy(() -> sorgenteService.crea(richiesta(URL)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("101");

            verify(sorgenteRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("404 se la sorgente non esiste")
        void elimina_inesistente_404() {
            when(sorgenteRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sorgenteService.elimina(7L))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("sincronizzaTutte")
    class SincronizzaTutte {

        @Test
        @DisplayName("una sorgente in errore non ferma le altre")
        void giro_unaInErrore_leAltreProseguono() {
            // E' la regola che tiene in piedi il giro periodico: un canale in manutenzione
            // e' normale, e se interrompesse il giro lascerebbe tutti gli altri fermi
            when(sorgenteRepository.tuttiGliId()).thenReturn(List.of(1L, 2L, 3L));
            when(sincronizzatore.sincronizza(1L))
                    .thenReturn(new EsitoSorgente(EsitoSincronizzazione.OK, null, 4));
            when(sincronizzatore.sincronizza(2L))
                    .thenThrow(new FeedNonRaggiungibileException("Il canale ha risposto 503"));
            when(sincronizzatore.sincronizza(3L))
                    .thenReturn(new EsitoSorgente(EsitoSincronizzazione.CONFLITTI, "qualcosa", 2));

            sorgenteService.sincronizzaTutte();

            verify(sincronizzatore).sincronizza(3L);
            // Quella che ha fallito viene annotata comunque: altrimenti un canale rotto
            // resterebbe rotto in silenzio
            verify(sincronizzatore).registraEsito(2L, EsitoSincronizzazione.ERRORE,
                    "Il canale ha risposto 503");
        }

        @Test
        @DisplayName("il riepilogo conta le sorgenti, non le occupazioni")
        void giro_riepiloga() {
            when(sorgenteRepository.tuttiGliId()).thenReturn(List.of(1L, 2L, 3L));
            when(sincronizzatore.sincronizza(1L))
                    .thenReturn(new EsitoSorgente(EsitoSincronizzazione.OK, null, 4));
            when(sincronizzatore.sincronizza(2L))
                    .thenThrow(new FeedNonRaggiungibileException("giu'"));
            when(sincronizzatore.sincronizza(3L))
                    .thenReturn(new EsitoSorgente(EsitoSincronizzazione.CONFLITTI, "qualcosa", 2));

            sorgenteService.sincronizzaTutte();

            ArgumentCaptor<Object> dati = ArgumentCaptor.forClass(Object.class);
            verify(apiResponseMapper).toResponse(any(), eq("Sincronizzazione eseguita"), dati.capture());

            assertThat(dati.getValue())
                    .asInstanceOf(type(SincronizzazioneResponse.class))
                    .satisfies(riepilogo -> {
                        assertThat(riepilogo.getSorgenti()).isEqualTo(3);
                        // La somma di quel che hanno scritto le due che hanno funzionato
                        assertThat(riepilogo.getBlocchiScritti()).isEqualTo(6);
                        assertThat(riepilogo.getConConflitti()).isEqualTo(1);
                        assertThat(riepilogo.getInErrore()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("l'esito si annota anche quando e' andato tutto bene")
        void giro_tuttoBene_annotaComunque() {
            when(sorgenteRepository.tuttiGliId()).thenReturn(List.of(1L));
            when(sincronizzatore.sincronizza(1L))
                    .thenReturn(new EsitoSorgente(EsitoSincronizzazione.OK, null, 3));

            sorgenteService.sincronizzaTutte();

            // Senza, l'elenco continuerebbe a dire che quella sorgente non e' mai partita
            verify(sincronizzatore).registraEsito(eq(1L), eq(EsitoSincronizzazione.OK), isNull());
        }

        @Test
        @DisplayName("senza sorgenti configurate il giro non fa niente")
        void giro_senzaSorgenti() {
            when(sorgenteRepository.tuttiGliId()).thenReturn(List.of());

            sorgenteService.sincronizzaTutte();

            verifyNoInteractions(sincronizzatore);
        }
    }

    private static SorgenteCalendarioRequest richiesta(String url) {
        return new SorgenteCalendarioRequest()
                .cameraId(ID_CAMERA)
                .nome("Booking")
                .url(url);
    }

    private static Camera camera() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(3L);
        tipologia.setNome("Doppia");

        Camera camera = new Camera();
        camera.setId(ID_CAMERA);
        camera.setNumero("101");
        camera.setTipologiaCamera(tipologia);
        return camera;
    }
}
