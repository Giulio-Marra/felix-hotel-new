package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.CameraMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.CameraServiceImpl;
import com.felixhotel.backend.support.TestDataFactory;
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
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link CameraServiceImpl}: la classe sotto esame e' vera,
 * repository finti. Il {@link CameraMapper} invece e' <b>reale</b>, costruito a
 * mano — non e' una svista: converte i due enum omonimi passando per il nome, e
 * finto direbbe sempre quello che gli si e' detto di dire, cioe' proprio il
 * pezzo che si vuole verificare.
 *
 * <p>Si verificano le decisioni: quale eccezione sceglie ciascun ramo, cosa il
 * service fa o non fa prima di sollevarla, e come traduce i filtri in una query.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CameraServiceImpl")
class CameraServiceImplTest {

    private static final Long ID = 11L;
    private static final Long ID_TIPOLOGIA = 4L;

    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private CameraServiceImpl cameraService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializza() {
        dati = new TestDataFactory();
        // Mapper veri: la conversione fra i due StatoCamera e la sintesi della
        // tipologia sono logica, e con dei finti non verrebbero esercitate.
        CameraMapper cameraMapper = new CameraMapper(new TipologiaCameraMapper(new DotazioneMapper()));
        cameraService = new CameraServiceImpl(
                cameraRepository, tipologiaCameraRepository, cameraMapper, apiResponseMapper);
    }

    private TipologiaCamera tipologiaEsistente() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia Superior");
        tipologia.setCapienzaMax(2);
        tipologia.setPrezzoNotte(new BigDecimal("120.00"));
        return tipologia;
    }

    private Camera cameraEsistente() {
        Camera camera = new Camera();
        camera.setId(ID);
        camera.setNumero("101");
        camera.setPiano(1);
        camera.setTipologiaCamera(tipologiaEsistente());
        camera.setStato(com.felixhotel.backend.entity.enums.StatoCamera.LIBERA);
        return camera;
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("senza filtri li passa entrambi a null e ordina per numero")
        void elenca_senzaFiltri_passaNullEOrdinaPerNumero() {
            // given: una pagina qualsiasi
            when(cameraRepository.cerca(isNull(), isNull(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(cameraEsistente())));

            // when: nessun filtro — e' come si apre la pagina la prima volta
            cameraService.elenca(1, 5, null, null);

            // then: i null arrivano alla query, che sa gestirli, invece di essere
            // tradotti qui in una catena di if. E la pagina e' ordinata: senza ORDER BY
            // lo stesso elemento comparirebbe in due pagine o in nessuna
            ArgumentCaptor<Pageable> paginaRichiesta = ArgumentCaptor.forClass(Pageable.class);
            verify(cameraRepository).cerca(isNull(), isNull(), paginaRichiesta.capture());

            assertThat(paginaRichiesta.getValue()).isEqualTo(
                    PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "numero")));
        }

        @Test
        @DisplayName("con lo stato filtrato lo traduce nell'enum di dominio")
        void elenca_conStato_traduceEnum() {
            // given: si filtra per camere in manutenzione
            when(cameraRepository.cerca(eq(ID_TIPOLOGIA),
                    eq(com.felixhotel.backend.entity.enums.StatoCamera.MANUTENZIONE), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            // when
            cameraService.elenca(0, 20, ID_TIPOLOGIA, StatoCamera.MANUTENZIONE);

            // then: al repository arriva l'enum di dominio, non quello dello spec. Sono
            // due tipi diversi con gli stessi nomi, e passare il secondo non
            // compilerebbe nemmeno — il test serve a fissare che la traduzione avvenga
            // qui e non venga spostata dentro la query
            verify(cameraRepository).cerca(ID_TIPOLOGIA,
                    com.felixhotel.backend.entity.enums.StatoCamera.MANUTENZIONE,
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "numero")));
        }
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("con dati validi salva, mette LIBERA e risponde 201")
        void crea_conDatiValidi_rispondeCreated() {
            // given: numero libero e tipologia esistente
            CameraRequest richiesta = dati.cameraRequest(ID_TIPOLOGIA);
            when(cameraRepository.existsByNumeroIgnoreCase(richiesta.getNumero())).thenReturn(false);
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(cameraRepository.saveAndFlush(any(Camera.class))).thenReturn(cameraEsistente());

            // when: la richiesta non dice niente sullo stato
            cameraService.crea(richiesta);

            // then: la camera nasce LIBERA. Non e' il DEFAULT della colonna a deciderlo —
            // Hibernate nomina sempre la colonna, quindi quel default non scatta mai:
            // lo decide il service, ed e' questo test a dirlo
            ArgumentCaptor<Camera> salvata = ArgumentCaptor.forClass(Camera.class);
            verify(cameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getNumero()).isEqualTo(richiesta.getNumero());
            assertThat(salvata.getValue().getStato())
                    .isEqualTo(com.felixhotel.backend.entity.enums.StatoCamera.LIBERA);
            assertThat(salvata.getValue().getTipologiaCamera().getId()).isEqualTo(ID_TIPOLOGIA);

            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("con tipologia inesistente solleva BadRequestException e non NotFound")
        void crea_conTipologiaInesistente_sollevaBadRequest() {
            // given: il numero e' libero ma la tipologia indicata non c'e'
            CameraRequest richiesta = dati.cameraRequest(ID_TIPOLOGIA);
            when(cameraRepository.existsByNumeroIgnoreCase(richiesta.getNumero())).thenReturn(false);
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.empty());

            // when/then: 400. Il 404 di questi endpoint significa "questa camera non
            // esiste": usarlo anche per un id dentro il corpo renderebbe indistinguibili
            // due errori che si riparano in modo diverso
            assertThatThrownBy(() -> cameraService.crea(richiesta))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(String.valueOf(ID_TIPOLOGIA))
                    .extracting(ex -> ((BadRequestException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(cameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("con numero gia' in uso solleva ConflictException senza salvare")
        void crea_conNumeroDuplicato_sollevaConflict() {
            // given: il numero appartiene gia' a un'altra camera
            CameraRequest richiesta = dati.cameraRequest(ID_TIPOLOGIA);
            when(cameraRepository.existsByNumeroIgnoreCase(richiesta.getNumero())).thenReturn(true);

            // when/then: 409, e niente e' stato scritto
            assertThatThrownBy(() -> cameraService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(cameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("se il duplicato lo scopre il database risponde comunque 409 e non 500")
        void crea_conDuplicatoRilevatoDalDatabase_sollevaConflict() {
            // given: al controllo il numero risulta libero, ma la scrittura viola
            // l'indice unico — la richiesta gemella arrivata nel frattempo
            CameraRequest richiesta = dati.cameraRequest(ID_TIPOLOGIA);
            when(cameraRepository.existsByNumeroIgnoreCase(richiesta.getNumero())).thenReturn(false);
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(cameraRepository.saveAndFlush(any(Camera.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_camera_numero"));

            // when/then: resta un conflitto anche quando ad accorgersene e' il database
            assertThatThrownBy(() -> cameraService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException prima di controllare il numero")
        void aggiorna_conIdInesistente_sollevaNotFound() {
            // given: nessuna camera con quell'id
            when(cameraRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404
            assertThatThrownBy(() -> cameraService.aggiorna(ID, dati.cameraRequest(ID_TIPOLOGIA)))
                    .isInstanceOf(NotFoundException.class);

            // then: l'ordine conta — cercare un numero duplicato per una camera che non
            // esiste darebbe 409 al posto di 404
            verify(cameraRepository, never()).existsByNumeroIgnoreCaseAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("omettendo lo stato riporta la camera a LIBERA")
        void aggiorna_senzaStato_riportaALibera() {
            // given: una camera in manutenzione
            Camera esistente = cameraEsistente();
            esistente.setStato(com.felixhotel.backend.entity.enums.StatoCamera.MANUTENZIONE);

            CameraRequest richiesta = dati.cameraRequest(ID_TIPOLOGIA).numero(esistente.getNumero());

            when(cameraRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(cameraRepository.existsByNumeroIgnoreCaseAndIdNot(esistente.getNumero(), ID))
                    .thenReturn(false);
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(cameraRepository.saveAndFlush(any(Camera.class))).thenReturn(esistente);

            // when: si aggiorna senza dire niente sullo stato
            cameraService.aggiorna(ID, richiesta);

            // then: torna LIBERA. E' il significato della PUT — un campo omesso non viene
            // lasciato com'era — e va fissato da un test proprio perche' sorprende: e'
            // anche la ragione per cui il lavoro quotidiano passa da impostaStato
            ArgumentCaptor<Camera> salvata = ArgumentCaptor.forClass(Camera.class);
            verify(cameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getStato())
                    .isEqualTo(com.felixhotel.backend.entity.enums.StatoCamera.LIBERA);
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("con prenotazioni che la referenziano solleva ConflictException e non 500")
        void elimina_conPrenotazioniResidue_sollevaConflict() {
            // given: la camera esiste, ma la cancellazione viola una chiave esterna
            when(cameraRepository.findById(ID)).thenReturn(Optional.of(cameraEsistente()));
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("fk prenotazione -> camera"))
                    .when(cameraRepository).flush();

            // when/then: 409 e non 500. Portarsi via lo storico delle prenotazioni per
            // togliere una stanza dall'elenco sarebbe il rimedio peggiore del problema
            assertThatThrownBy(() -> cameraService.elimina(ID))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("impostaStato")
    class ImpostaStato {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException senza salvare")
        void impostaStato_conIdInesistente_sollevaNotFound() {
            // given: nessuna camera con quell'id
            when(cameraRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404
            assertThatThrownBy(() ->
                    cameraService.impostaStato(ID, dati.cameraStatoRequest(StatoCamera.PULIZIA)))
                    .isInstanceOf(NotFoundException.class);

            verify(cameraRepository, never()).save(any());
        }

        @Test
        @DisplayName("cambia solo lo stato e lascia stare il resto")
        void impostaStato_conStatoNuovo_cambiaSoloLoStato() {
            // given: una camera libera al primo piano
            Camera esistente = cameraEsistente();
            when(cameraRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(cameraRepository.save(any(Camera.class))).thenReturn(esistente);

            // when: la si segna da pulire
            cameraService.impostaStato(ID, dati.cameraStatoRequest(StatoCamera.PULIZIA));

            // then: numero, piano e tipologia sono intatti. E' il motivo per cui questo
            // endpoint esiste separato dalla PUT: segnare una stanza da pulire non deve
            // obbligare a rimandare tutto il resto, dove basta una dimenticanza per
            // spostare una camera di piano
            ArgumentCaptor<Camera> salvata = ArgumentCaptor.forClass(Camera.class);
            verify(cameraRepository).save(salvata.capture());

            assertThat(salvata.getValue().getStato())
                    .isEqualTo(com.felixhotel.backend.entity.enums.StatoCamera.PULIZIA);
            assertThat(salvata.getValue().getNumero()).isEqualTo("101");
            assertThat(salvata.getValue().getPiano()).isEqualTo(1);
            assertThat(salvata.getValue().getTipologiaCamera().getId()).isEqualTo(ID_TIPOLOGIA);
        }

        @Test
        @DisplayName("rimandando lo stato che ha gia' risponde 200 e non un conflitto")
        void impostaStato_conStatoInvariato_rispondeOk() {
            // given: una camera gia' LIBERA
            Camera esistente = cameraEsistente();
            when(cameraRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(cameraRepository.save(any(Camera.class))).thenReturn(esistente);

            // when: le si chiede di essere LIBERA
            cameraService.impostaStato(ID, dati.cameraStatoRequest(StatoCamera.LIBERA));

            // then: 200. L'operazione e' idempotente per disegno — chi la ripete perche'
            // non era sicuro che la prima fosse arrivata non sta sbagliando niente, e un
            // 409 lo costringerebbe a leggere lo stato prima di ogni scrittura
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }
    }
}
