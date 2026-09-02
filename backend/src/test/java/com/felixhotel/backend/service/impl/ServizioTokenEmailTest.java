package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.entity.TipoTokenEmail;
import com.felixhotel.backend.entity.TokenEmail;
import com.felixhotel.backend.exception.BadRequestException;
import com.felixhotel.backend.repository.TokenEmailRepository;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.support.OrologioPilotato;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I token mandati per email: come nascono, come si consumano e quando smettono di valere.
 *
 * <p><b>Sta in {@code service.impl} e non in {@code service}</b> come tutti gli altri
 * test dei Service, e la ragione e' scritta sul costruttore della classe sotto test:
 * quello che accetta un {@code Clock} e' visibile al solo package per non far scattare
 * {@code EI_EXPOSE_REP2}, quindi il test deve stare accanto. E' un prezzo piccolo e
 * dichiarato — l'alternativa era un filtro su SpotBugs, che la regola del progetto
 * riserva a cio' che non si puo' correggere.
 *
 * <p><b>Cosa questa classe prova che nessun altro proverebbe</b>: che il segreto in
 * chiaro non finisca in tabella, che un token di un tipo non valga per un altro, e che
 * la scadenza morda. I primi due sono decisioni di sicurezza, il terzo si potrebbe
 * verificare solo aspettando un'ora — ed e' il motivo per cui l'orologio e' iniettabile.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServizioTokenEmail")
class ServizioTokenEmailTest {

    private static final LocalDateTime ADESSO = LocalDateTime.of(2026, 9, 2, 10, 0);
    private static final Long ID_SOGGETTO = 7L;

    @Mock
    private TokenEmailRepository tokenRepository;

    private ServizioTokenEmail servizio;

    @BeforeEach
    void inizializza() {
        servizio = new ServizioTokenEmail(tokenRepository,
                new OrologioPilotato(ADESSO.toInstant(ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("in tabella finisce l'impronta e non il token")
    void emetti_salvaLImprontaENonIlToken() {
        // when
        String inChiaro = servizio.emetti(
                TipoTokenEmail.RESET_PASSWORD, TipoAccount.CLIENTE, ID_SOGGETTO);

        // then: e' la decisione di sicurezza della tabella. In chiaro, una lettura del
        // database varrebbe il controllo di ogni account con un reset in corso
        ArgumentCaptor<TokenEmail> salvato = ArgumentCaptor.forClass(TokenEmail.class);
        verify(tokenRepository).save(salvato.capture());

        assertThat(inChiaro).isNotBlank();
        assertThat(salvato.getValue().getTokenHash())
                .as("in tabella non deve esserci il segreto")
                .isNotEqualTo(inChiaro)
                // SHA-256 in esadecimale: sessantaquattro caratteri, come la colonna
                .hasSize(64);
    }

    @Test
    @DisplayName("due token dello stesso tipo non sono mai uguali")
    void emetti_dueVolte_produceSegretiDiversi() {
        // when
        String primo = servizio.emetti(TipoTokenEmail.VERIFICA_EMAIL, TipoAccount.CLIENTE, ID_SOGGETTO);
        String secondo = servizio.emetti(TipoTokenEmail.VERIFICA_EMAIL, TipoAccount.CLIENTE, ID_SOGGETTO);

        // then: sembra ovvio e non lo e' — con un Random al posto di SecureRandom sarebbe
        // vero lo stesso, ed e' il motivo per cui questo test non basta da solo. Prova
        // che non ci sia un valore fisso, che e' l'errore che si commette davvero
        assertThat(primo).isNotEqualTo(secondo);
    }

    @Test
    @DisplayName("emettere toglie i token pendenti dello stesso tipo")
    void emetti_invalidaIPrecedenti() {
        // when
        servizio.emetti(TipoTokenEmail.RESET_PASSWORD, TipoAccount.PERSONALE, ID_SOGGETTO);

        // then: chi chiede due volte il reset si aspetta che valga l'ultimo link ricevuto
        verify(tokenRepository).deleteByTipoAndTipoAccountAndSoggettoIdAndUsatoIlIsNull(
                TipoTokenEmail.RESET_PASSWORD, TipoAccount.PERSONALE, ID_SOGGETTO);
    }

    @Test
    @DisplayName("la scadenza e' quella del tipo, non una qualunque")
    void emetti_scadenzaSecondoIlTipo() {
        // when
        servizio.emetti(TipoTokenEmail.INVITO_STAFF, TipoAccount.PERSONALE, ID_SOGGETTO);

        // then: sette giorni per un invito. Le tre durate stanno sull'enum apposta,
        // perche' due punti del codice non emettano lo stesso token con scadenze diverse
        ArgumentCaptor<TokenEmail> salvato = ArgumentCaptor.forClass(TokenEmail.class);
        verify(tokenRepository).save(salvato.capture());
        assertThat(salvato.getValue().getScadenza()).isEqualTo(ADESSO.plusDays(7));
    }

    @Test
    @DisplayName("un token valido si consuma e resta segnato come usato")
    void consuma_conTokenValido_loSegna() {
        // given
        String inChiaro = emettiERestituisci(TipoTokenEmail.VERIFICA_EMAIL, ADESSO.plusHours(24));

        // when
        TokenEmail consumato = servizio.consuma(TipoTokenEmail.VERIFICA_EMAIL, inChiaro);

        // then: si consuma e non si cancella, cosi' un secondo clic puo' rispondere
        // "gia' usato" invece di "non esiste"
        assertThat(consumato.getUsatoIl()).isEqualTo(ADESSO);
    }

    @Test
    @DisplayName("un token gia' usato non vale piu'")
    void consuma_conTokenGiaUsato_sollevaBadRequest() {
        // given: i client di posta pre-caricano i link, quindi questo caso capita davvero
        String inChiaro = emettiERestituisci(TipoTokenEmail.VERIFICA_EMAIL, ADESSO.plusHours(24));
        servizio.consuma(TipoTokenEmail.VERIFICA_EMAIL, inChiaro);

        // when/then
        assertThatThrownBy(() -> servizio.consuma(TipoTokenEmail.VERIFICA_EMAIL, inChiaro))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("un token scaduto non vale")
    void consuma_conTokenScaduto_sollevaBadRequest() {
        // given: scaduto un minuto fa. E' il caso che senza un orologio pilotabile si
        // potrebbe provare solo aspettando
        String inChiaro = emettiERestituisci(TipoTokenEmail.RESET_PASSWORD, ADESSO.minusMinutes(1));

        // when/then
        assertThatThrownBy(() -> servizio.consuma(TipoTokenEmail.RESET_PASSWORD, inChiaro))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("un token di verifica non vale per un reset")
    void consuma_conTipoSbagliato_sollevaBadRequest() {
        // given: e' il controllo che conta di piu' di tutti. Un link di conferma e' il
        // piu' facile da ottenere — basta registrarsi — e senza questo si potrebbe
        // presentarlo all'endpoint del reset
        String inChiaro = emettiERestituisci(TipoTokenEmail.VERIFICA_EMAIL, ADESSO.plusHours(24));

        // when/then
        assertThatThrownBy(() -> servizio.consuma(TipoTokenEmail.RESET_PASSWORD, inChiaro))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("un token inventato non vale")
    void consuma_conTokenInesistente_sollevaBadRequest() {
        // given
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        // when/then: stesso messaggio degli altri due casi, di proposito — chi legge deve
        // fare la stessa cosa, e distinguerli direbbe a chi prova token a caso quali esistono
        assertThatThrownBy(() -> servizio.consuma(TipoTokenEmail.VERIFICA_EMAIL, "inventato"))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * Emette un token davvero e insegna al repository a ritrovarlo, con la scadenza
     * indicata. Serve perche' l'impronta la sa calcolare solo la classe sotto test: un
     * test che se la calcolasse da solo starebbe riscrivendo il codice che verifica.
     */
    private String emettiERestituisci(TipoTokenEmail tipo, LocalDateTime scadenza) {
        String inChiaro = servizio.emetti(tipo, TipoAccount.CLIENTE, ID_SOGGETTO);

        ArgumentCaptor<TokenEmail> salvato = ArgumentCaptor.forClass(TokenEmail.class);
        verify(tokenRepository).save(salvato.capture());
        TokenEmail token = salvato.getValue();
        token.setScadenza(scadenza);

        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));
        // lenient: i test che verificano un rifiuto (scaduto, tipo sbagliato) si fermano
        // prima di riscrivere il token, quindi per loro questo stub non serve
        lenient().when(tokenRepository.save(token)).thenReturn(token);
        return inChiaro;
    }
}
