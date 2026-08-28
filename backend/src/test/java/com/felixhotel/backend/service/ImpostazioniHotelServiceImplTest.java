package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ImpostazioniHotelPubblicheResponse;
import com.felixhotel.backend.dto.ImpostazioniHotelRequest;
import com.felixhotel.backend.dto.ImpostazioniHotelResponse;
import com.felixhotel.backend.entity.ImpostazioniHotel;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.ImpostazioniHotelMapper;
import com.felixhotel.backend.repository.ImpostazioniHotelRepository;
import com.felixhotel.backend.service.impl.ImpostazioniHotelServiceImpl;
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
import org.springframework.http.HttpStatus;

import java.time.LocalTime;
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
 * Test unitari di {@link ImpostazioniHotelServiceImpl}: la classe sotto esame e'
 * vera, repository e mapper sono finti. Niente Spring, niente database.
 *
 * <p><b>Ci sono meno rami che altrove, e quelli che ci sono contano di piu'.</b>
 * Un CRUD normale porta con se' il proprio ciclo di vita — non trovata,
 * duplicata, cancellabile o no — e sono quelli i rami che i suoi unitari
 * verificano. Qui il ciclo di vita non c'e': la riga esiste dalla migration e
 * non muore. Restano tre cose da provare, e sono le tre decisioni di questa
 * risorsa:
 * <ul>
 *   <li>che la lettura pubblica passi dal mapper <b>pubblico</b> — cioe' che i
 *       dati fiscali non escano dalla rotta aperta. E' l'unica regola di
 *       sicurezza di questo Service, e sta tutta nella scelta del metodo;</li>
 *   <li>che la PUT sia una PUT: i campi omessi si azzerano, non restano com'erano;</li>
 *   <li>che la riga mancante sia un guasto nostro (500) e non un 404 dato al
 *       client, che ha chiesto una cosa perfettamente sensata.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImpostazioniHotelServiceImpl")
class ImpostazioniHotelServiceImplTest {

    @Mock
    private ImpostazioniHotelRepository impostazioniHotelRepository;
    @Mock
    private ImpostazioniHotelMapper impostazioniHotelMapper;
    @Mock
    private ApiResponseMapper apiResponseMapper;

    @InjectMocks
    private ImpostazioniHotelServiceImpl impostazioniHotelService;

    private TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
    }

    /** La riga seminata dalla migration, gia' compilata in ogni campo. */
    private ImpostazioniHotel impostazioniEsistenti() {
        ImpostazioniHotel impostazioni = new ImpostazioniHotel();
        impostazioni.setId(ImpostazioniHotel.ID_RIGA_UNICA);
        impostazioni.setNome("Felix Hotel");
        impostazioni.setIndirizzo("Via Roma 1");
        impostazioni.setTelefono("+39 0541 123456");
        impostazioni.setEmail("info@felixhotel.it");
        impostazioni.setOrarioCheckInDefault(LocalTime.of(14, 0));
        impostazioni.setOrarioCheckOutDefault(LocalTime.of(10, 0));
        impostazioni.setRagioneSociale("Felix Hotel S.r.l.");
        impostazioni.setPartitaIva("01234567890");
        impostazioni.setCodiceFiscale("01234567890");
        impostazioni.setCin("IT099014B4XYZW1234");
        impostazioni.setComune("Rimini");
        impostazioni.setCodiceIstatComune("099014");
        impostazioni.setCodiceStrutturaAlloggiati("RN012345");
        return impostazioni;
    }

    /** Fa trovare al repository la riga unica, all'id che il Service deve chiedere. */
    private ImpostazioniHotel rigaPresente() {
        ImpostazioniHotel impostazioni = impostazioniEsistenti();
        when(impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA))
                .thenReturn(Optional.of(impostazioni));
        return impostazioni;
    }

    @Nested
    @DisplayName("leggi")
    class Leggi {

        @Test
        @DisplayName("converte con il mapper completo e risponde 200")
        void leggi_conRigaPresente_usaIlMapperCompleto() {
            // given: la riga c'e', come sempre dopo la migration
            ImpostazioniHotel impostazioni = rigaPresente();
            when(impostazioniHotelMapper.toResponse(impostazioni)).thenReturn(new ImpostazioniHotelResponse());

            // when
            impostazioniHotelService.leggi();

            // then: la vista completa, e nessun passaggio da quella pubblica
            verify(impostazioniHotelMapper).toResponse(impostazioni);
            verify(impostazioniHotelMapper, never()).toPubblicheResponse(any());
            verify(apiResponseMapper).toResponse(eq(HttpStatus.OK), anyString(), any());
        }
    }

    @Nested
    @DisplayName("leggiPubbliche")
    class LeggiPubbliche {

        @Test
        @DisplayName("converte con il mapper pubblico, mai con quello completo")
        void leggiPubbliche_conRigaPresente_usaIlMapperPubblico() {
            // given
            ImpostazioniHotel impostazioni = rigaPresente();
            when(impostazioniHotelMapper.toPubblicheResponse(impostazioni))
                    .thenReturn(new ImpostazioniHotelPubblicheResponse());

            // when: e' la rotta che chiunque puo' chiamare, senza autenticarsi
            impostazioniHotelService.leggiPubbliche();

            // then: la conversione passa dal DTO che i campi fiscali non ce li ha
            // proprio. E' l'unico punto in cui questa risorsa decide qualcosa di
            // sicurezza: il DTO completo e quello pubblico partono dalla stessa
            // entity, e a separarli e' solo quale dei due metodi viene chiamato qui
            verify(impostazioniHotelMapper).toPubblicheResponse(impostazioni);
            verify(impostazioniHotelMapper, never()).toResponse(any());
        }
    }

    @Nested
    @DisplayName("aggiorna")
    class Aggiorna {

        @Test
        @DisplayName("scrive nell'entity tutti i campi della richiesta")
        void aggiorna_conRichiestaCompleta_scriveOgniCampo() {
            // given
            ImpostazioniHotel impostazioni = rigaPresente();
            ImpostazioniHotelRequest richiesta = dati.impostazioniHotelRequest();
            when(impostazioniHotelRepository.save(any())).thenReturn(impostazioni);
            when(impostazioniHotelMapper.toResponse(any())).thenReturn(new ImpostazioniHotelResponse());

            // when
            impostazioniHotelService.aggiorna(richiesta);

            // then: l'entity salvata porta i valori della richiesta, tutti
            ArgumentCaptor<ImpostazioniHotel> salvata = ArgumentCaptor.forClass(ImpostazioniHotel.class);
            verify(impostazioniHotelRepository).save(salvata.capture());
            assertThat(salvata.getValue())
                    .satisfies(i -> {
                        assertThat(i.getNome()).isEqualTo(richiesta.getNome());
                        assertThat(i.getIndirizzo()).isEqualTo(richiesta.getIndirizzo());
                        assertThat(i.getTelefono()).isEqualTo(richiesta.getTelefono());
                        assertThat(i.getEmail()).isEqualTo(richiesta.getEmail());
                        assertThat(i.getOrarioCheckInDefault()).isEqualTo(richiesta.getOrarioCheckInDefault());
                        assertThat(i.getOrarioCheckOutDefault()).isEqualTo(richiesta.getOrarioCheckOutDefault());
                        assertThat(i.getRagioneSociale()).isEqualTo(richiesta.getRagioneSociale());
                        assertThat(i.getPartitaIva()).isEqualTo(richiesta.getPartitaIva());
                        assertThat(i.getCodiceFiscale()).isEqualTo(richiesta.getCodiceFiscale());
                        assertThat(i.getCin()).isEqualTo(richiesta.getCin());
                        assertThat(i.getComune()).isEqualTo(richiesta.getComune());
                        assertThat(i.getCodiceIstatComune()).isEqualTo(richiesta.getCodiceIstatComune());
                        assertThat(i.getCodiceStrutturaAlloggiati())
                                .isEqualTo(richiesta.getCodiceStrutturaAlloggiati());
                    });
        }

        @Test
        @DisplayName("azzera i campi facoltativi omessi dalla richiesta")
        void aggiorna_conFacoltativiOmessi_liAzzera() {
            // given: la riga esistente li ha tutti valorizzati
            ImpostazioniHotel impostazioni = rigaPresente();
            assertThat(impostazioni.getPartitaIva()).isNotNull();

            // e una richiesta con i soli tre campi obbligatori
            ImpostazioniHotelRequest richiesta = new ImpostazioniHotelRequest()
                    .nome("Felix Hotel")
                    .orarioCheckInDefault(LocalTime.of(15, 0))
                    .orarioCheckOutDefault(LocalTime.of(11, 0));
            when(impostazioniHotelRepository.save(any())).thenReturn(impostazioni);
            when(impostazioniHotelMapper.toResponse(any())).thenReturn(new ImpostazioniHotelResponse());

            // when
            impostazioniHotelService.aggiorna(richiesta);

            // then: i facoltativi omessi sono spariti, non rimasti com'erano. E' una
            // PUT e non una PATCH, e questo test e' cio' che rende quella frase
            // qualcosa di piu' di una riga nella descrizione dello spec: senza,
            // "sostituisce l'intera risorsa" resterebbe un'intenzione
            ArgumentCaptor<ImpostazioniHotel> salvata = ArgumentCaptor.forClass(ImpostazioniHotel.class);
            verify(impostazioniHotelRepository).save(salvata.capture());
            assertThat(salvata.getValue())
                    .satisfies(i -> {
                        assertThat(i.getIndirizzo()).isNull();
                        assertThat(i.getTelefono()).isNull();
                        assertThat(i.getEmail()).isNull();
                        assertThat(i.getRagioneSociale()).isNull();
                        assertThat(i.getPartitaIva()).isNull();
                        assertThat(i.getCodiceFiscale()).isNull();
                        assertThat(i.getCin()).isNull();
                        assertThat(i.getComune()).isNull();
                        assertThat(i.getCodiceIstatComune()).isNull();
                        assertThat(i.getCodiceStrutturaAlloggiati()).isNull();
                    });
        }

        @Test
        @DisplayName("risponde con la vista completa e non con quella pubblica")
        void aggiorna_aSalvataggioRiuscito_rispondeConLaVistaCompleta() {
            // given
            ImpostazioniHotel impostazioni = rigaPresente();
            when(impostazioniHotelRepository.save(any())).thenReturn(impostazioni);
            when(impostazioniHotelMapper.toResponse(impostazioni)).thenReturn(new ImpostazioniHotelResponse());

            // when
            impostazioniHotelService.aggiorna(dati.impostazioniHotelRequest());

            // then: a scrivere e' un ADMIN, e ricevere indietro meno di quello che si
            // e' appena mandato lascerebbe il dubbio che il resto non sia stato salvato
            verify(impostazioniHotelMapper).toResponse(impostazioni);
            verify(impostazioniHotelMapper, never()).toPubblicheResponse(any());
        }
    }

    @Nested
    @DisplayName("quando la riga unica non c'e'")
    class RigaMancante {

        @Test
        @DisplayName("la lettura completa fallisce come guasto del server, non come 404")
        void leggi_senzaRiga_sollevaIllegalState() {
            // given: qualcuno ha cancellato a mano la riga scritta dalla migration
            when(impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA))
                    .thenReturn(Optional.empty());

            // when / then: IllegalStateException e non NotFoundException. La differenza
            // e' di chi sia la colpa: chi chiama ha chiesto una cosa che deve esistere,
            // quindi il 404 gli direbbe di aver sbagliato lui. Il catch-all del
            // GlobalExceptionHandler lo traduce in 500 con un messaggio generico
            assertThatThrownBy(() -> impostazioniHotelService.leggi())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("la lettura pubblica fallisce allo stesso modo")
        void leggiPubbliche_senzaRiga_sollevaIllegalState() {
            // given
            when(impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> impostazioniHotelService.leggiPubbliche())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("l'aggiornamento non scrive niente")
        void aggiorna_senzaRiga_nonSalva() {
            // given
            when(impostazioniHotelRepository.findById(ImpostazioniHotel.ID_RIGA_UNICA))
                    .thenReturn(Optional.empty());
            ImpostazioniHotelRequest richiesta = dati.impostazioniHotelRequest();

            // when / then: la lettura viene prima della scrittura, quindi non si crea
            // per sbaglio una riga nuova — che il CHECK del database rifiuterebbe
            // comunque, ma con un errore molto meno leggibile
            assertThatThrownBy(() -> impostazioniHotelService.aggiorna(richiesta))
                    .isInstanceOf(IllegalStateException.class);
            verify(impostazioniHotelRepository, never()).save(any());
        }
    }
}
