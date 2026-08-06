package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.TipologiaCameraRequest;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TipologiaCameraMapper;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.TipologiaCameraServiceImpl;
import com.felixhotel.backend.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link TipologiaCameraServiceImpl}: la classe sotto esame e'
 * vera, repository e mapper sono finti. Niente Spring, niente database.
 *
 * <p>Si verificano le <b>decisioni</b>: quale eccezione (quindi quale status)
 * sceglie ciascun ramo, e cosa il service fa o non fa prima di sollevarla. Che
 * poi quelle eccezioni diventino risposte HTTP con la busta giusta lo verifica
 * {@code TipologiaCameraApiIT} — con i repository finti non si potrebbe.
 *
 * <p>E' il primo service del progetto con rami di dominio veri, quindi qui c'e'
 * la parte di suite che finora mancava: fino al catalogo gli unitari coprivano
 * solo i contatori antibrute force.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TipologiaCameraServiceImpl")
class TipologiaCameraServiceImplTest {

    private static final Long ID = 7L;

    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private TipologiaCameraMapper tipologiaCameraMapper;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    @InjectMocks
    private TipologiaCameraServiceImpl tipologiaCameraService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
    }

    /** Entity gia' esistente a database, con l'id che i test usano per cercarla. */
    private TipologiaCamera tipologiaEsistente() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID);
        tipologia.setNome("Doppia Superior");
        tipologia.setDescrizione("Camera doppia con vista sul giardino");
        tipologia.setCapienzaMax(2);
        tipologia.setPrezzoNotte(new BigDecimal("120.00"));
        return tipologia;
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("chiede al repository una pagina ordinata per nome")
        void elenca_sempre_ordinaPerNome() {
            // given: una pagina qualsiasi di risultati
            when(tipologiaCameraRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(tipologiaEsistente())));

            // when: si chiede la seconda pagina da 5 elementi
            tipologiaCameraService.elenca(1, 5);

            // then: la pagina richiesta e' quella giusta ed e' ordinata per nome.
            // L'ordinamento non e' un vezzo: senza ORDER BY il database puo' restituire
            // le righe in ordine diverso a ogni query, e lo stesso elemento comparirebbe
            // in due pagine o in nessuna. E' un difetto che si vede solo quando i dati
            // superano la prima pagina, cioe' tardi.
            ArgumentCaptor<Pageable> paginaRichiesta = ArgumentCaptor.forClass(Pageable.class);
            verify(tipologiaCameraRepository).findAll(paginaRichiesta.capture());

            assertThat(paginaRichiesta.getValue()).isEqualTo(
                    PageRequest.of(1, 5, Sort.by(Sort.Direction.ASC, "nome")));
        }
    }

    @Nested
    @DisplayName("dettaglio")
    class Dettaglio {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException")
        void dettaglio_conIdInesistente_sollevaNotFound() {
            // given: nessuna tipologia con quell'id
            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404, non una busta vuota con 200
            assertThatThrownBy(() -> tipologiaCameraService.dettaglio(ID))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(ex -> ((NotFoundException) ex).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("crea")
    class Crea {

        @Test
        @DisplayName("con dati validi salva e risponde 201")
        void crea_conDatiValidi_rispondeCreated() {
            // given: il nome e' libero
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();
            when(tipologiaCameraRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(false);
            when(tipologiaCameraRepository.saveAndFlush(any(TipologiaCamera.class)))
                    .thenReturn(tipologiaEsistente());

            // when: si crea la tipologia
            tipologiaCameraService.crea(richiesta);

            // then: l'entity salvata porta i campi della richiesta...
            ArgumentCaptor<TipologiaCamera> salvata = ArgumentCaptor.forClass(TipologiaCamera.class);
            verify(tipologiaCameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getNome()).isEqualTo(richiesta.getNome());
            assertThat(salvata.getValue().getCapienzaMax()).isEqualTo(richiesta.getCapienzaMax());
            assertThat(salvata.getValue().getPrezzoNotte()).isEqualByComparingTo(richiesta.getPrezzoNotte());

            // ...e la busta dichiara 201, non 200: e' una risorsa nuova
            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("con nome gia' in uso solleva ConflictException senza salvare")
        void crea_conNomeDuplicato_sollevaConflict() {
            // given: esiste gia' una tipologia con quel nome (confronto che ignora le maiuscole)
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();
            when(tipologiaCameraRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(true);

            // when/then: 409 — la richiesta e' valida in se', confligge con i dati esistenti
            assertThatThrownBy(() -> tipologiaCameraService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            // then: niente e' stato scritto
            verify(tipologiaCameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("se il duplicato lo scopre il database risponde comunque 409 e non 500")
        void crea_conDuplicatoRilevatoDalDatabase_sollevaConflict() {
            // given: al momento del controllo il nome risulta libero, ma la scrittura viola
            // l'indice unico — e' il caso della richiesta gemella arrivata nel frattempo,
            // che nessun controllo preventivo puo' vedere
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();
            when(tipologiaCameraRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(false);
            when(tipologiaCameraRepository.saveAndFlush(any(TipologiaCamera.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_tipologia_camera_nome"));

            // when/then: il conflitto resta un conflitto anche quando ad accorgersene e' il
            // database. Senza questa traduzione sarebbe un 500, cioe' "colpa nostra" al
            // posto di "riprova con un altro nome"
            assertThatThrownBy(() -> tipologiaCameraService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException prima di controllare il nome")
        void aggiorna_conIdInesistente_sollevaNotFound() {
            // given: nessuna tipologia con quell'id
            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404
            assertThatThrownBy(() -> tipologiaCameraService.aggiorna(ID, dati.tipologiaCameraRequest()))
                    .isInstanceOf(NotFoundException.class);

            // then: l'ordine conta — cercare un nome duplicato per una risorsa che non
            // esiste darebbe 409 al posto di 404, cioe' la risposta sbagliata
            verify(tipologiaCameraRepository, never()).existsByNomeIgnoreCaseAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("con il nome di un'altra tipologia solleva ConflictException")
        void aggiorna_conNomeDiUnAltra_sollevaConflict() {
            // given: la tipologia esiste, ma il nome richiesto appartiene a un'altra
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest();
            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.of(tipologiaEsistente()));
            when(tipologiaCameraRepository.existsByNomeIgnoreCaseAndIdNot(richiesta.getNome(), ID))
                    .thenReturn(true);

            // when/then: 409, e niente viene scritto
            assertThatThrownBy(() -> tipologiaCameraService.aggiorna(ID, richiesta))
                    .isInstanceOf(ConflictException.class);

            verify(tipologiaCameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("lasciando invariato il proprio nome aggiorna senza conflitto")
        void aggiorna_conProprioNomeInvariato_aggiorna() {
            // given: si risalva la tipologia cambiando il prezzo ma non il nome. E' il caso
            // che il controllo di unicita' deve lasciar passare: senza l'esclusione di se
            // stessa (IdNot), una tipologia darebbe 409 contro il proprio nome e non
            // sarebbe piu' modificabile
            TipologiaCamera esistente = tipologiaEsistente();
            TipologiaCameraRequest richiesta = dati.tipologiaCameraRequest()
                    .nome(esistente.getNome())
                    .prezzoNotte(new BigDecimal("145.00"));

            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(tipologiaCameraRepository.existsByNomeIgnoreCaseAndIdNot(esistente.getNome(), ID))
                    .thenReturn(false);
            when(tipologiaCameraRepository.saveAndFlush(any(TipologiaCamera.class))).thenReturn(esistente);

            // when: si aggiorna
            tipologiaCameraService.aggiorna(ID, richiesta);

            // then: il nuovo prezzo e' finito nell'entity, e la busta dichiara 200
            ArgumentCaptor<TipologiaCamera> salvata = ArgumentCaptor.forClass(TipologiaCamera.class);
            verify(tipologiaCameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getPrezzoNotte()).isEqualByComparingTo(new BigDecimal("145.00"));
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException")
        void elimina_conIdInesistente_sollevaNotFound() {
            // given: nessuna tipologia con quell'id
            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404, e niente viene cancellato
            assertThatThrownBy(() -> tipologiaCameraService.elimina(ID))
                    .isInstanceOf(NotFoundException.class);

            verify(tipologiaCameraRepository, never()).delete(any());
        }

        @Test
        @DisplayName("se qualcosa la referenzia ancora solleva ConflictException e non 500")
        void elimina_conRiferimentiResidui_sollevaConflict() {
            // given: la tipologia esiste, ma la cancellazione viola una chiave esterna
            // (ci sono camere o prenotazioni che la usano)
            when(tipologiaCameraRepository.findById(ID)).thenReturn(Optional.of(tipologiaEsistente()));
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("fk camera -> tipologia_camera"))
                    .when(tipologiaCameraRepository).flush();

            // when/then: 409 e non 500. La distinzione conta per chi chiama: 409 vuol dire
            // "prima togli i riferimenti", 500 vuol dire "e' rotto, non farci niente"
            assertThatThrownBy(() -> tipologiaCameraService.elimina(ID))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
