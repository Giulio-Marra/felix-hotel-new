package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.TipoCodifica;
import com.felixhotel.backend.entity.VoceCodifica;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.VoceCodificaMapper;
import com.felixhotel.backend.repository.VoceCodificaRepository;
import com.felixhotel.backend.service.impl.VoceCodificaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitari delle tabelle di codifica.
 *
 * <p>Pochi e mirati, perche' qui di dominio non ce n'e': questi dati non li
 * produce l'applicazione, li ripete. I rami veri sono tre — il filtro vuoto, i
 * codici doppi, e l'ordine fra cancellazione e scrittura — e sono tutti e tre casi
 * in cui il difetto sarebbe silenzioso.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoceCodificaServiceImpl")
class VoceCodificaServiceImplTest {

    @Mock
    private VoceCodificaRepository voceCodificaRepository;

    private VoceCodificaServiceImpl voceCodificaService;

    @BeforeEach
    void inizializza() {
        voceCodificaService = new VoceCodificaServiceImpl(voceCodificaRepository,
                new VoceCodificaMapper(), new ApiResponseMapper());
    }

    @Nested
    @DisplayName("elenca")
    class Elenca {

        @Test
        @DisplayName("passa il filtro alla query e restituisce le voci convertite")
        void elenca_conFiltro_loPassaAllaQuery() {
            // given
            when(voceCodificaRepository.cerca(eq(com.felixhotel.backend.entity.TipoCodifica.COMUNE),
                    eq("reggio"), any(Pageable.class)))
                    .thenReturn(pagina(voce("035033", "REGGIO NELL'EMILIA", "RE")));

            // when
            voceCodificaService.elenca(TipoCodifica.COMUNE, "reggio", 0, 20);

            // then
            verify(voceCodificaRepository).cerca(
                    eq(com.felixhotel.backend.entity.TipoCodifica.COMUNE), eq("reggio"),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("un filtro di soli spazi vale come nessun filtro")
        void elenca_conFiltroDiSoliSpazi_cercaSenzaFiltro() {
            // given
            when(voceCodificaRepository.cerca(any(), isNull(), any(Pageable.class)))
                    .thenReturn(pagina());

            // when
            voceCodificaService.elenca(TipoCodifica.COMUNE, "   ", 0, 20);

            // then: senza questa normalizzazione una tendina che manda quel che l'utente
            // sta digitando — spazio compreso — darebbe zero risultati invece
            // dell'elenco intero, e sembrerebbe un difetto
            verify(voceCodificaRepository).cerca(any(), isNull(), any(Pageable.class));
        }

        @Test
        @DisplayName("il filtro viene ripulito dagli spazi ai bordi")
        void elenca_conSpaziAiBordi_liToglie() {
            when(voceCodificaRepository.cerca(any(), eq("roma"), any(Pageable.class)))
                    .thenReturn(pagina());

            voceCodificaService.elenca(TipoCodifica.COMUNE, "  roma  ", 0, 20);

            verify(voceCodificaRepository).cerca(any(), eq("roma"), any(Pageable.class));
        }

        @Test
        @DisplayName("una famiglia vuota e' una pagina vuota, non un errore")
        void elenca_suFamigliaVuota_rispondePaginaVuota() {
            // given / when / then: e' lo stato normale di un'installazione appena fatta,
            // dove nessuno ha ancora importato niente
            when(voceCodificaRepository.cerca(any(), any(), any(Pageable.class)))
                    .thenReturn(pagina());

            assertThat(voceCodificaService.elenca(TipoCodifica.STATO, null, 0, 20).getStatus())
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("importa")
    class Importa {

        @Test
        @DisplayName("cancella la famiglia e riscrive l'elenco, in quest'ordine")
        void importa_conElencoValido_cancellaPoiScrive() {
            // given
            when(voceCodificaRepository.cancellaPerTipo(
                    com.felixhotel.backend.entity.TipoCodifica.STATO)).thenReturn(3);

            // when
            voceCodificaService.importa(TipoCodifica.STATO,
                    List.of(dto("100", "FRANCIA"), dto("200", "GERMANIA")));

            // then: l'ordine e' la cosa da provare. Se gli insert partissero prima della
            // delete, l'indice unico scatterebbe su codici che stiamo proprio
            // sostituendo — ed e' il motivo per cui in mezzo c'e' un flush esplicito
            var ordine = inOrder(voceCodificaRepository);
            ordine.verify(voceCodificaRepository)
                    .cancellaPerTipo(com.felixhotel.backend.entity.TipoCodifica.STATO);
            ordine.verify(voceCodificaRepository).flush();
            ordine.verify(voceCodificaRepository).saveAll(any());
        }

        @Test
        @DisplayName("scrive il tipo su ogni riga, che nel corpo non c'era")
        void importa_scriveIlTipoSuOgniRiga() {
            // given
            when(voceCodificaRepository.cancellaPerTipo(any())).thenReturn(0);

            // when
            voceCodificaService.importa(TipoCodifica.TIPO_ALLOGGIATO,
                    List.of(dto("16", "Ospite singolo"), dto("17", "Capofamiglia")));

            // then: il tipo sta nel percorso e non nelle righe, perche' ripeterlo in
            // ottomila elementi vorrebbe dire spedire ottomila volte una cosa che chi
            // chiama ha appena scritto nell'URL
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VoceCodifica>> salvate = ArgumentCaptor.forClass(List.class);
            verify(voceCodificaRepository).saveAll(salvate.capture());
            assertThat(salvate.getValue()).allSatisfy(voce ->
                    assertThat(voce.getTipo())
                            .isEqualTo(com.felixhotel.backend.entity.TipoCodifica.TIPO_ALLOGGIATO));
        }

        @Test
        @DisplayName("lo stesso codice due volte nell'elenco e' 400, e dice quale")
        void importa_conCodiciDoppi_sollevaBadRequest() {
            // given / when / then: lo prenderebbe anche l'indice unico, ma con un
            // messaggio che non dice quale codice — e cercarlo a mano fra ottomila righe
            // non e' un lavoro che si possa chiedere a nessuno
            assertThatThrownBy(() -> voceCodificaService.importa(TipoCodifica.COMUNE,
                    List.of(dto("001", "ROMA"), dto("002", "MILANO"), dto("001", "ROMA"))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("001");
        }

        @Test
        @DisplayName("con i codici doppi non cancella niente")
        void importa_conCodiciDoppi_nonCancellaNiente() {
            // given / when / then: il controllo viene prima della delete, quindi un file
            // rotto non lascia la famiglia vuota — che sarebbe il modo peggiore di
            // fallire, perche' toglie anche quel che funzionava
            assertThatThrownBy(() -> voceCodificaService.importa(TipoCodifica.COMUNE,
                    List.of(dto("001", "ROMA"), dto("001", "ROMA"))))
                    .isInstanceOf(BadRequestException.class);

            verify(voceCodificaRepository, never()).cancellaPerTipo(any());
        }

        @Test
        @DisplayName("un elenco vuoto svuota la famiglia, e non e' un errore")
        void importa_conElencoVuoto_svuota() {
            // given
            when(voceCodificaRepository.cancellaPerTipo(any())).thenReturn(7904);

            // when: e' il modo di annullare un import sbagliato
            var risposta = voceCodificaService.importa(TipoCodifica.COMUNE, List.of());

            // then
            assertThat(risposta.getStatus()).isEqualTo(200);
            assertThat(risposta.getMessage()).contains("0 voci").contains("7904");
            verify(voceCodificaRepository).cancellaPerTipo(any());
        }

        @Test
        @DisplayName("il messaggio dice quante voci scritte e quante sostituite")
        void importa_scriveIConteggiNelMessaggio() {
            // given
            when(voceCodificaRepository.cancellaPerTipo(any())).thenReturn(2);

            // when
            var risposta = voceCodificaService.importa(TipoCodifica.STATO,
                    List.of(dto("100", "FRANCIA")));

            // then: chi importa ottomila comuni vuole sapere se ne ha scritti ottomila o
            // zero, e un "fatto" senza numeri non glielo direbbe
            assertThat(risposta.getMessage()).contains("STATO").contains("1 voci").contains("2");
        }
    }

    // ---- fabbriche e scorciatoie -------------------------------------------------

    private com.felixhotel.backend.dto.VoceCodifica dto(String codice, String descrizione) {
        return new com.felixhotel.backend.dto.VoceCodifica()
                .codice(codice)
                .descrizione(descrizione);
    }

    private VoceCodifica voce(String codice, String descrizione, String provincia) {
        VoceCodifica voce = new VoceCodifica();
        voce.setTipo(com.felixhotel.backend.entity.TipoCodifica.COMUNE);
        voce.setCodice(codice);
        voce.setDescrizione(descrizione);
        voce.setProvincia(provincia);
        return voce;
    }

    private Page<VoceCodifica> pagina(VoceCodifica... voci) {
        return new PageImpl<>(List.of(voci), PageRequest.of(0, 20), voci.length);
    }

}
