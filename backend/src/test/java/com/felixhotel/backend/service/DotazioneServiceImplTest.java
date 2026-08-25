package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.entity.Dotazione;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.DotazioneMapper;
import com.felixhotel.backend.repository.DotazioneRepository;
import com.felixhotel.backend.service.impl.DotazioneServiceImpl;
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
 * Test unitari di {@link DotazioneServiceImpl}: la classe sotto esame e' vera,
 * repository e mapper sono finti. Niente Spring, niente database.
 *
 * <p>Si verificano le <b>decisioni</b>: quale eccezione (quindi quale status)
 * sceglie ciascun ramo, e cosa il service fa o non fa prima di sollevarla. Che
 * poi quelle eccezioni diventino risposte HTTP con la busta giusta lo verifica
 * {@code DotazioneApiIT}.
 *
 * <p>La differenza rispetto al catalogo delle tipologie sta tutta
 * nell'eliminazione: qui non c'e' nessun 409 da verificare, perche' cancellare
 * una dotazione assegnata e' un'operazione legittima — vedi
 * {@link Elimina#elimina_conDotazioneEsistente_cancellaSenzaAltriControlli()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DotazioneServiceImpl")
class DotazioneServiceImplTest {

    private static final Long ID = 3L;

    @Mock
    private DotazioneRepository dotazioneRepository;
    @Mock
    private DotazioneMapper dotazioneMapper;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    @InjectMocks
    private DotazioneServiceImpl dotazioneService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
    }

    /** Entity gia' esistente a database, con l'id che i test usano per cercarla. */
    private Dotazione dotazioneEsistente() {
        Dotazione dotazione = new Dotazione();
        dotazione.setId(ID);
        dotazione.setNome("Wi-Fi");
        dotazione.setDescrizione("Connessione senza fili gratuita in tutta la struttura");
        return dotazione;
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("chiede al repository una pagina ordinata per nome")
        void elenca_sempre_ordinaPerNome() {
            // given: una pagina qualsiasi di risultati
            when(dotazioneRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(dotazioneEsistente())));

            // when: si chiede la seconda pagina da 5 elementi
            dotazioneService.elenca(1, 5);

            // then: la pagina richiesta e' quella giusta ed e' ordinata per nome. Senza
            // ORDER BY il database puo' restituire le righe in ordine diverso a ogni
            // query, e lo stesso elemento comparirebbe in due pagine o in nessuna
            ArgumentCaptor<Pageable> paginaRichiesta = ArgumentCaptor.forClass(Pageable.class);
            verify(dotazioneRepository).findAll(paginaRichiesta.capture());

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
            // given: nessuna dotazione con quell'id
            when(dotazioneRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404, non una busta vuota con 200
            assertThatThrownBy(() -> dotazioneService.dettaglio(ID))
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
            DotazioneRequest richiesta = dati.dotazioneRequest();
            when(dotazioneRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(false);
            when(dotazioneRepository.saveAndFlush(any(Dotazione.class))).thenReturn(dotazioneEsistente());

            // when: si crea la dotazione
            dotazioneService.crea(richiesta);

            // then: l'entity salvata porta i campi della richiesta...
            ArgumentCaptor<Dotazione> salvata = ArgumentCaptor.forClass(Dotazione.class);
            verify(dotazioneRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getNome()).isEqualTo(richiesta.getNome());
            assertThat(salvata.getValue().getDescrizione()).isEqualTo(richiesta.getDescrizione());

            // ...e la busta dichiara 201, non 200: e' una risorsa nuova
            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("con nome gia' in uso solleva ConflictException senza salvare")
        void crea_conNomeDuplicato_sollevaConflict() {
            // given: esiste gia' una dotazione con quel nome (confronto che ignora le maiuscole)
            DotazioneRequest richiesta = dati.dotazioneRequest();
            when(dotazioneRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(true);

            // when/then: 409 — la richiesta e' valida in se', confligge con i dati esistenti
            assertThatThrownBy(() -> dotazioneService.crea(richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            // then: niente e' stato scritto
            verify(dotazioneRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("se il duplicato lo scopre il database risponde comunque 409 e non 500")
        void crea_conDuplicatoRilevatoDalDatabase_sollevaConflict() {
            // given: al momento del controllo il nome risulta libero, ma la scrittura viola
            // l'indice unico — e' il caso della richiesta gemella arrivata nel frattempo,
            // che nessun controllo preventivo puo' vedere
            DotazioneRequest richiesta = dati.dotazioneRequest();
            when(dotazioneRepository.existsByNomeIgnoreCase(richiesta.getNome())).thenReturn(false);
            when(dotazioneRepository.saveAndFlush(any(Dotazione.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_dotazione_nome"));

            // when/then: il conflitto resta un conflitto anche quando ad accorgersene e' il
            // database. Senza questa traduzione sarebbe un 500, cioe' "colpa nostra" al
            // posto di "riprova con un altro nome"
            assertThatThrownBy(() -> dotazioneService.crea(richiesta))
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
            // given: nessuna dotazione con quell'id
            when(dotazioneRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404
            assertThatThrownBy(() -> dotazioneService.aggiorna(ID, dati.dotazioneRequest()))
                    .isInstanceOf(NotFoundException.class);

            // then: l'ordine conta — cercare un nome duplicato per una risorsa che non
            // esiste darebbe 409 al posto di 404, cioe' la risposta sbagliata
            verify(dotazioneRepository, never()).existsByNomeIgnoreCaseAndIdNot(anyString(), any());
        }

        @Test
        @DisplayName("con il nome di un'altra dotazione solleva ConflictException")
        void aggiorna_conNomeDiUnAltra_sollevaConflict() {
            // given: la dotazione esiste, ma il nome richiesto appartiene a un'altra
            DotazioneRequest richiesta = dati.dotazioneRequest();
            when(dotazioneRepository.findById(ID)).thenReturn(Optional.of(dotazioneEsistente()));
            when(dotazioneRepository.existsByNomeIgnoreCaseAndIdNot(richiesta.getNome(), ID)).thenReturn(true);

            // when/then: 409, e niente viene scritto
            assertThatThrownBy(() -> dotazioneService.aggiorna(ID, richiesta))
                    .isInstanceOf(ConflictException.class);

            verify(dotazioneRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("lasciando invariato il proprio nome aggiorna senza conflitto")
        void aggiorna_conProprioNomeInvariato_aggiorna() {
            // given: si risalva la dotazione cambiando la descrizione ma non il nome. E' il
            // caso che il controllo di unicita' deve lasciar passare: senza l'esclusione di
            // se stessa (IdNot), una dotazione darebbe 409 contro il proprio nome e non
            // sarebbe piu' modificabile
            Dotazione esistente = dotazioneEsistente();
            DotazioneRequest richiesta = dati.dotazioneRequest()
                    .nome(esistente.getNome())
                    .descrizione("Connessione in fibra, gratuita");

            when(dotazioneRepository.findById(ID)).thenReturn(Optional.of(esistente));
            when(dotazioneRepository.existsByNomeIgnoreCaseAndIdNot(esistente.getNome(), ID)).thenReturn(false);
            when(dotazioneRepository.saveAndFlush(any(Dotazione.class))).thenReturn(esistente);

            // when: si aggiorna
            dotazioneService.aggiorna(ID, richiesta);

            // then: la nuova descrizione e' finita nell'entity, e la busta dichiara 200
            ArgumentCaptor<Dotazione> salvata = ArgumentCaptor.forClass(Dotazione.class);
            verify(dotazioneRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getDescrizione()).isEqualTo("Connessione in fibra, gratuita");
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("con id inesistente solleva NotFoundException")
        void elimina_conIdInesistente_sollevaNotFound() {
            // given: nessuna dotazione con quell'id
            when(dotazioneRepository.findById(ID)).thenReturn(Optional.empty());

            // when/then: 404, e niente viene cancellato
            assertThatThrownBy(() -> dotazioneService.elimina(ID))
                    .isInstanceOf(NotFoundException.class);

            verify(dotazioneRepository, never()).delete(any());
        }

        @Test
        @DisplayName("con dotazione esistente cancella senza cercare riferimenti residui")
        void elimina_conDotazioneEsistente_cancellaSenzaAltriControlli() {
            // given: la dotazione esiste ed e' assegnata a chissa' quante tipologie
            Dotazione esistente = dotazioneEsistente();
            when(dotazioneRepository.findById(ID)).thenReturn(Optional.of(esistente));

            // when: si elimina
            dotazioneService.elimina(ID);

            // then: si cancella e basta, e la busta dichiara 200. Qui non c'e' il 409 che
            // protegge le tipologie di camera, ed e' una scelta: la chiave esterna della
            // tabella di legame ha ON DELETE CASCADE, quindi i riferimenti se ne vanno con
            // lei. Una tipologia usata si porterebbe via lo storico delle prenotazioni, una
            // dotazione tolta dall'elenco e' solo una voce che non si offre piu'
            verify(dotazioneRepository).delete(esistente);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), eq(null));
        }
    }
}
