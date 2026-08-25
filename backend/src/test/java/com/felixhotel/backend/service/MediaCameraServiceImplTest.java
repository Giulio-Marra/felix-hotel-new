package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.dto.MediaCameraResponse;
import com.felixhotel.backend.entity.MediaCamera;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.exception.ConflictException;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.MediaCameraMapper;
import com.felixhotel.backend.repository.MediaCameraRepository;
import com.felixhotel.backend.repository.TipologiaCameraRepository;
import com.felixhotel.backend.service.impl.MediaCameraServiceImpl;
import com.felixhotel.backend.support.TestDataFactory;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.felixhotel.backend.service.impl.MediaCameraServiceImpl.MASSIMO_FOTO_PER_TIPOLOGIA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitari di {@link MediaCameraServiceImpl}: la classe sotto esame e' vera,
 * repository finti. Il {@link MediaCameraMapper} e' <b>reale</b>, come negli
 * altri test di service del progetto — e' lui a decidere che l'ordine della
 * lista in ingresso venga conservato, che e' una delle cose da verificare.
 *
 * <p>Quasi tutto quel che segue gira intorno a una domanda che nessun altro
 * service del progetto si era ancora posto: <b>chi decide la posizione di una
 * cosa dentro una lista</b>. L'aggiunta la deduce, il riordino la impone, e
 * l'eliminazione non la tocca — sono tre risposte diverse, ed e' qui che si
 * fissano.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaCameraServiceImpl")
class MediaCameraServiceImplTest {

    private static final Long ID_TIPOLOGIA = 4L;

    @Mock
    private MediaCameraRepository mediaCameraRepository;
    @Mock
    private TipologiaCameraRepository tipologiaCameraRepository;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    private MediaCameraServiceImpl mediaCameraService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializza() {
        dati = new TestDataFactory();
        // Mapper vero: la conservazione dell'ordine e l'esclusione del campo 'ordine'
        // dalla risposta sono decisioni, e con un finto non verrebbero esercitate.
        mediaCameraService = new MediaCameraServiceImpl(
                mediaCameraRepository, tipologiaCameraRepository, new MediaCameraMapper(), apiResponseMapper);
    }

    private TipologiaCamera tipologiaEsistente() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia Superior");
        return tipologia;
    }

    /** Una foto gia' in galleria, alla posizione indicata. */
    private MediaCamera media(long id, int ordine) {
        MediaCamera foto = new MediaCamera();
        foto.setId(id);
        foto.setTipologiaCamera(tipologiaEsistente());
        foto.setUrl("https://cdn.felixhotel.example/camere/foto-" + id + ".jpg");
        foto.setOrdine(ordine);
        return foto;
    }

    /** La tipologia del percorso esiste: e' il presupposto di ogni metodo. */
    private void tipologiaEsiste() {
        when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(true);
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("con tipologia inesistente solleva NotFoundException e non legge la galleria")
        void elenca_conTipologiaInesistente_sollevaNotFound() {
            // given: l'id del percorso non corrisponde a nessuna tipologia
            when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(false);

            // when/then: 404 e non una lista vuota. E' la distinzione che chi legge il
            // catalogo deve poter fare: "questa scheda non ha foto" e "questa scheda non
            // esiste" sono due risposte diverse
            assertThatThrownBy(() -> mediaCameraService.elenca(ID_TIPOLOGIA))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(ex -> ((NotFoundException) ex).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(mediaCameraRepository, never()).findByTipologiaCameraIdOrderByOrdineAscIdAsc(anyLong());
        }

        @Test
        @DisplayName("restituisce la galleria nell'ordine dato dalla query, senza il campo ordine")
        void elenca_conFoto_conservaOrdineENonEsponeIlCampo() {
            // given: due foto, gia' ordinate dalla query
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(List.of(media(7L, 0), media(3L, 1)));

            // when
            mediaCameraService.elenca(ID_TIPOLOGIA);

            // then: la lista esce nello stesso ordine — il service non riordina niente,
            // l'ORDER BY della query e' l'unica autorita' — e la posizione non compare
            // fra i campi: e' come la sequenza viene conservata, non un dato del client
            ArgumentCaptor<Object> corpo = ArgumentCaptor.forClass(Object.class);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), corpo.capture());

            assertThat(corpo.getValue())
                    .asInstanceOf(InstanceOfAssertFactories.list(MediaCameraResponse.class))
                    .extracting(MediaCameraResponse::getId)
                    .containsExactly(7L, 3L);
        }

        @Test
        @DisplayName("con una tipologia senza foto risponde con la lista vuota")
        void elenca_senzaFoto_rispondeListaVuota() {
            // given: la tipologia c'e' ma non ha immagini
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(List.of());

            // when
            mediaCameraService.elenca(ID_TIPOLOGIA);

            // then: 200 con una lista vuota, che e' una risposta legittima e non un caso
            // limite da evitare
            ArgumentCaptor<Object> corpo = ArgumentCaptor.forClass(Object.class);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), corpo.capture());

            assertThat((List<?>) corpo.getValue()).isEmpty();
        }
    }

    @Nested
    @DisplayName("aggiungi")
    class Aggiungi {

        @Test
        @DisplayName("mette la foto in coda, al massimo gia' occupato piu' uno")
        void aggiungi_conGalleriaNonVuota_mettaInCoda() {
            // given: la galleria arriva fino alla posizione 4
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(mediaCameraRepository.existsByTipologiaCameraIdAndUrl(ID_TIPOLOGIA, richiesta.getUrl()))
                    .thenReturn(false);
            when(mediaCameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(5L);
            when(mediaCameraRepository.massimoOrdine(ID_TIPOLOGIA)).thenReturn(4);
            when(mediaCameraRepository.saveAndFlush(any(MediaCamera.class))).thenReturn(media(9L, 5));

            // when
            mediaCameraService.aggiungi(ID_TIPOLOGIA, richiesta);

            // then: posizione 5. Il massimo e non il conteggio: con dei buchi lasciati da
            // un'eliminazione il conteggio darebbe una posizione gia' occupata, e la
            // foto finirebbe a pari merito con una che c'e' gia' invece che in fondo
            ArgumentCaptor<MediaCamera> salvata = ArgumentCaptor.forClass(MediaCamera.class);
            verify(mediaCameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getOrdine()).isEqualTo(5);
            assertThat(salvata.getValue().getUrl()).isEqualTo(richiesta.getUrl());
            assertThat(salvata.getValue().getTipologiaCamera().getId()).isEqualTo(ID_TIPOLOGIA);

            verify(apiResponseMapper).toResponse(eq(HttpStatus.CREATED), anyString(), any());
        }

        @Test
        @DisplayName("sulla galleria vuota la prima foto nasce in posizione zero")
        void aggiungi_suGalleriaVuota_partaDaZero() {
            // given: nessuna foto, quindi il massimo e' il -1 del coalesce
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(mediaCameraRepository.existsByTipologiaCameraIdAndUrl(ID_TIPOLOGIA, richiesta.getUrl()))
                    .thenReturn(false);
            when(mediaCameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(0L);
            when(mediaCameraRepository.massimoOrdine(ID_TIPOLOGIA)).thenReturn(-1);
            when(mediaCameraRepository.saveAndFlush(any(MediaCamera.class))).thenReturn(media(9L, 0));

            // when
            mediaCameraService.aggiungi(ID_TIPOLOGIA, richiesta);

            // then: zero. E' il motivo per cui la query fa coalesce(max, -1) e non
            // coalesce(max, 0): con lo zero come default, la prima foto partirebbe da uno
            ArgumentCaptor<MediaCamera> salvata = ArgumentCaptor.forClass(MediaCamera.class);
            verify(mediaCameraRepository).saveAndFlush(salvata.capture());

            assertThat(salvata.getValue().getOrdine()).isZero();
        }

        @Test
        @DisplayName("con la stessa url gia' in galleria solleva ConflictException")
        void aggiungi_conUrlDuplicata_sollevaConflict() {
            // given: quell'immagine c'e' gia' in questa tipologia
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(mediaCameraRepository.existsByTipologiaCameraIdAndUrl(ID_TIPOLOGIA, richiesta.getUrl()))
                    .thenReturn(true);

            // when/then: 409, e senza nemmeno contare le foto. Il duplicato si controlla
            // prima del tetto di proposito: a chi sta ricaricando un'immagine che c'e'
            // gia', "galleria piena" direbbe di cancellarne un'altra per far posto a una
            // che non serve
            assertThatThrownBy(() -> mediaCameraService.aggiungi(ID_TIPOLOGIA, richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            verify(mediaCameraRepository, never()).countByTipologiaCameraId(anyLong());
            verify(mediaCameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("con la galleria al massimo solleva ConflictException")
        void aggiungi_conGalleriaPiena_sollevaConflict() {
            // given: la tipologia ha gia' il numero massimo di foto
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(mediaCameraRepository.existsByTipologiaCameraIdAndUrl(ID_TIPOLOGIA, richiesta.getUrl()))
                    .thenReturn(false);
            when(mediaCameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA))
                    .thenReturn((long) MASSIMO_FOTO_PER_TIPOLOGIA);

            // when/then: 409. Il tetto non e' un'opinione sul buon gusto: e' quello che
            // rende l'elenco non paginato una lista di dimensione nota
            assertThatThrownBy(() -> mediaCameraService.aggiungi(ID_TIPOLOGIA, richiesta))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(String.valueOf(MASSIMO_FOTO_PER_TIPOLOGIA));

            verify(mediaCameraRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("se il vincolo scatta al salvataggio traduce comunque in ConflictException")
        void aggiungi_conViolazioneAlSalvataggio_sollevaConflict() {
            // given: il controllo preventivo non trova niente, ma fra quel momento e la
            // scrittura una richiesta gemella ha inserito la stessa url
            MediaCameraRequest richiesta = dati.mediaCameraRequest();
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA))
                    .thenReturn(Optional.of(tipologiaEsistente()));
            when(mediaCameraRepository.existsByTipologiaCameraIdAndUrl(ID_TIPOLOGIA, richiesta.getUrl()))
                    .thenReturn(false);
            when(mediaCameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(0L);
            when(mediaCameraRepository.massimoOrdine(ID_TIPOLOGIA)).thenReturn(-1);
            when(mediaCameraRepository.saveAndFlush(any(MediaCamera.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_media_camera_tipologia_url"));

            // when/then: 409 e non il 500 che l'eccezione darebbe se uscisse cosi' com'e'.
            // E' il motivo per cui si usa saveAndFlush e non save: al commit la violazione
            // arriverebbe fuori da questo metodo, dove non c'e' piu' nessuno a tradurla
            assertThatThrownBy(() -> mediaCameraService.aggiungi(ID_TIPOLOGIA, richiesta))
                    .isInstanceOf(ConflictException.class)
                    .extracting(ex -> ((ConflictException) ex).getStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("con tipologia inesistente solleva NotFoundException")
        void aggiungi_conTipologiaInesistente_sollevaNotFound() {
            // given: l'id del percorso non corrisponde a nessuna tipologia
            when(tipologiaCameraRepository.trovaSenzaCollezioni(ID_TIPOLOGIA)).thenReturn(Optional.empty());

            // when/then: 404 e non 400, al contrario di quel che fa la camera con la sua
            // tipologia: li' l'id sta nel corpo della richiesta, qui nel percorso — ed e'
            // il percorso a dire quale risorsa si sta cercando
            assertThatThrownBy(() -> mediaCameraService.aggiungi(ID_TIPOLOGIA, dati.mediaCameraRequest()))
                    .isInstanceOf(NotFoundException.class);

            verify(mediaCameraRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("elimina")
    class Elimina {

        @Test
        @DisplayName("cancella la foto senza rinumerare le altre")
        void elimina_conFotoEsistente_nonRinumera() {
            // given: una galleria di tre foto, si toglie quella in mezzo
            tipologiaEsiste();
            MediaCamera daTogliere = media(3L, 1);
            when(mediaCameraRepository.findByIdAndTipologiaCameraId(3L, ID_TIPOLOGIA))
                    .thenReturn(Optional.of(daTogliere));

            // when
            mediaCameraService.elimina(ID_TIPOLOGIA, 3L);

            // then: si cancella e basta. Nessun saveAll dopo: le altre restano a 0 e 2, e
            // l'ordine in cui si leggono e' esattamente quello di prima — riscrivere due
            // righe per ricompattare numeri che non escono da nessuna risposta sarebbe
            // lavoro per un'estetica invisibile
            verify(mediaCameraRepository).delete(daTogliere);
            verify(mediaCameraRepository, never()).saveAll(any());
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), eq(null));
        }

        @Test
        @DisplayName("con una foto di un'altra tipologia solleva NotFoundException")
        void elimina_conFotoDiAltraTipologia_sollevaNotFound() {
            // given: l'id esiste, ma non in questa galleria — la query lo esclude gia'
            tipologiaEsiste();
            when(mediaCameraRepository.findByIdAndTipologiaCameraId(3L, ID_TIPOLOGIA))
                    .thenReturn(Optional.empty());

            // when/then: 404 come se non ci fosse. E' il motivo per cui il repository non
            // ha nessun findById per solo id: cosi' la foto di un'altra galleria non e'
            // qualcosa che il service debba ricordarsi di rifiutare, e' qualcosa che non
            // trova
            assertThatThrownBy(() -> mediaCameraService.elimina(ID_TIPOLOGIA, 3L))
                    .isInstanceOf(NotFoundException.class);

            verify(mediaCameraRepository, never()).delete(any());
        }

        @Test
        @DisplayName("con tipologia inesistente solleva NotFoundException prima di cercare la foto")
        void elimina_conTipologiaInesistente_sollevaNotFound() {
            // given: l'id del percorso non corrisponde a nessuna tipologia
            when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(false);

            // when/then: 404 con il messaggio della tipologia. Il controllo c'e' anche se
            // la ricerca della foto fallirebbe comunque: senza, un id di tipologia
            // sbagliato direbbe "foto non trovata" a chi ha in mano una foto valida,
            // mandandolo a cercare il problema dalla parte sbagliata
            assertThatThrownBy(() -> mediaCameraService.elimina(ID_TIPOLOGIA, 3L))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Tipologia");

            verify(mediaCameraRepository, never()).findByIdAndTipologiaCameraId(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("riordina")
    class Riordina {

        /** Tre foto in galleria, nelle posizioni 0, 1, 2. */
        private List<MediaCamera> galleriaDiTre() {
            return new ArrayList<>(List.of(media(7L, 0), media(3L, 1), media(5L, 2)));
        }

        @Test
        @DisplayName("riassegna le posizioni secondo l'indice nell'elenco ricevuto")
        void riordina_conSequenzaCompleta_riassegnaLePosizioni() {
            // given: la galleria e' 7, 3, 5 e si chiede 5, 7, 3
            tipologiaEsiste();
            List<MediaCamera> galleria = galleriaDiTre();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleria);

            // when
            mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(5L, 7L, 3L));

            // then: la posizione di ognuna e' il suo indice nella richiesta, e riparte da
            // zero — il che ricompatta anche eventuali buchi, che pero' e' un effetto
            // collaterale e non un servizio: nessuno puo' osservare la differenza
            assertThat(galleria).extracting(MediaCamera::getId, MediaCamera::getOrdine)
                    .containsExactlyInAnyOrder(
                            Tuple.tuple(5L, 0),
                            Tuple.tuple(7L, 1),
                            Tuple.tuple(3L, 2));

            verify(mediaCameraRepository).saveAll(any());
        }

        @Test
        @DisplayName("risponde con la galleria nell'ordine richiesto, senza rileggerla")
        void riordina_rispondeNellOrdineRichiesto() {
            // given
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleriaDiTre());

            // when
            mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(5L, 7L, 3L));

            // then: la risposta e' nell'ordine appena imposto. Non si rilegge dal
            // database: darebbe la stessa lista al prezzo di una query in piu', e sarebbe
            // l'unico punto in cui il client potrebbe vedere qualcosa di diverso da quel
            // che ha chiesto
            ArgumentCaptor<Object> corpo = ArgumentCaptor.forClass(Object.class);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), corpo.capture());

            assertThat(corpo.getValue())
                    .asInstanceOf(InstanceOfAssertFactories.list(MediaCameraResponse.class))
                    .extracting(MediaCameraResponse::getId)
                    .containsExactly(5L, 7L, 3L);
        }

        @Test
        @DisplayName("con la sequenza gia' in vigore non cambia niente e risponde 200")
        void riordina_conSequenzaIdentica_eIdempotente() {
            // given: si rimanda esattamente l'ordine attuale
            tipologiaEsiste();
            List<MediaCamera> galleria = galleriaDiTre();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleria);

            // when
            mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(7L, 3L, 5L));

            // then: 200 e posizioni invariate. Chi ripete la chiamata perche' non era
            // sicuro che la prima fosse arrivata non sta sbagliando niente
            assertThat(galleria).extracting(MediaCamera::getOrdine).containsExactly(0, 1, 2);
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }

        @Test
        @DisplayName("con un id ripetuto solleva BadRequestException")
        void riordina_conIdRipetuto_sollevaBadRequest() {
            // given: la stessa foto compare due volte
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleriaDiTre());

            // when/then: 400. E' il contrario di quel che fa l'endpoint delle dotazioni,
            // dove un id ripetuto viene assorbito: la' l'ordine non significava niente e
            // il duplicato era solo ridondante, qui una foto in due posizioni non e'
            // un'istruzione eseguibile
            assertThatThrownBy(() ->
                    mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(7L, 3L, 7L)))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(ex -> ((BadRequestException) ex).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            verify(mediaCameraRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("con un id mancante solleva BadRequestException e lo nomina")
        void riordina_conElencoParziale_sollevaBadRequest() {
            // given: si mandano solo due delle tre foto — e' il caso in cui qualcuno ne
            // ha aggiunta una mentre l'ordine veniva deciso
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleriaDiTre());

            // when/then: 400, e il messaggio dice quale manca. Applicarlo per la parte che
            // combacia metterebbe la terza foto dove capita: rifiutare e' l'unica risposta
            // che il client possa rimediare, rileggendo la galleria
            assertThatThrownBy(() ->
                    mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(3L, 7L)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("5");

            verify(mediaCameraRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("con un id estraneo alla galleria solleva BadRequestException")
        void riordina_conIdEstraneo_sollevaBadRequest() {
            // given: nella sequenza compare la foto di un'altra tipologia
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(galleriaDiTre());

            // when/then: 400 e non un'esclusione silenziosa. Ignorarlo vorrebbe dire
            // accettare come valida una richiesta che parla di una galleria diversa da
            // questa
            assertThatThrownBy(() ->
                    mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(7L, 3L, 5L, 99L)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("99");

            verify(mediaCameraRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("l'elenco vuoto e' valido solo se la galleria e' vuota")
        void riordina_conElencoVuoto_valeSoloSuGalleriaVuota() {
            // given: nessuna foto in galleria
            tipologiaEsiste();
            when(mediaCameraRepository.findByTipologiaCameraIdOrderByOrdineAscIdAsc(ID_TIPOLOGIA))
                    .thenReturn(List.of());

            // when: si manda un elenco vuoto
            mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest());

            // then: 200. Non e' un'eccezione alla regola dell'elenco esatto, e' il suo
            // caso limite — l'insieme vuoto coincide con l'insieme vuoto
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }

        @Test
        @DisplayName("con tipologia inesistente solleva NotFoundException")
        void riordina_conTipologiaInesistente_sollevaNotFound() {
            // given: l'id del percorso non corrisponde a nessuna tipologia
            when(tipologiaCameraRepository.existsById(ID_TIPOLOGIA)).thenReturn(false);

            // when/then: 404 prima ancora di guardare il corpo della richiesta
            assertThatThrownBy(() ->
                    mediaCameraService.riordina(ID_TIPOLOGIA, dati.mediaOrdineRequest(7L)))
                    .isInstanceOf(NotFoundException.class);

            verify(mediaCameraRepository, never()).findByTipologiaCameraIdOrderByOrdineAscIdAsc(anyLong());
        }
    }
}
