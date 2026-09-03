package com.felixhotel.backend.service;

import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.TassaSoggiornoOspite;
import com.felixhotel.backend.dto.TassaSoggiornoResponse;
import com.felixhotel.backend.entity.AliquotaTassaSoggiorno;
import com.felixhotel.backend.entity.Ospite;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.Utente;
import com.felixhotel.backend.entity.enums.MotivoEsenzione;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.exception.UnauthorizedException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.mapper.TassaSoggiornoMapper;
import com.felixhotel.backend.repository.AliquotaTassaSoggiornoRepository;
import com.felixhotel.backend.repository.OspiteRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.security.AppUserPrincipal;
import com.felixhotel.backend.security.ChiamanteCorrente;
import com.felixhotel.backend.security.TipoAccount;
import com.felixhotel.backend.service.impl.TassaSoggiornoServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unitari del calcolo della tassa di soggiorno.
 *
 * <p><b>E' qui che sta il peso dei test di questo branch</b>, ed e' l'inverso di
 * quel che vale per il calcolo del prezzo: quello vive in una query nativa e si
 * puo' provare solo contro un Postgres vero, questo vive in Java e ha rami — tre
 * specie di esenzione, il tetto, l'aliquota che manca, il cambio di aliquota a
 * meta' soggiorno — che un unitario esercita in millisecondi e un IT solo con
 * molta fatica.
 *
 * <p>Il mapper e' <b>vero e non finto</b>, come negli altri test di Service del
 * progetto: la conversione fra i due {@code MotivoEsenzione} e la somma del totale
 * dalle righe sono logica, e con un mapper finto non le proverebbe niente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TassaSoggiornoServiceImpl")
class TassaSoggiornoServiceImplTest {

    private static final Long ID_PRENOTAZIONE = 42L;
    private static final Long ID_CLIENTE = 7L;
    private static final Long ID_STAFF = 3L;

    /** L'arrivo di ogni prova: un giorno qualunque, lontano dai confini di mese. */
    private static final LocalDate ARRIVO = LocalDate.of(2027, 6, 10);

    /** Tre notti: 10, 11 e 12 giugno. Il 13 si parte, e la partenza non e' una notte. */
    private static final LocalDate PARTENZA = ARRIVO.plusDays(3);

    private static final BigDecimal DUE_EURO = new BigDecimal("2.00");

    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private OspiteRepository ospiteRepository;
    @Mock
    private AliquotaTassaSoggiornoRepository aliquotaRepository;

    private TassaSoggiornoServiceImpl tassaSoggiornoService;

    @BeforeEach
    void inizializza() {
        tassaSoggiornoService = new TassaSoggiornoServiceImpl(prenotazioneRepository,
                ospiteRepository, aliquotaRepository, new TassaSoggiornoMapper(),
                new ApiResponseMapper(), new ChiamanteCorrente());
    }

    @AfterEach
    void svuotaContesto() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("il conto")
    class Conto {

        @Test
        @DisplayName("un adulto paga tutte le notti del soggiorno")
        void calcola_conUnAdulto_pagaOgniNotte() {
            // given: tre notti, due euro l'una, nessun tetto
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then
            assertThat(conto.getTotale()).isEqualByComparingTo("6.00");
            assertThat(conto.getNottiSoggiorno()).isEqualTo(3);
            assertThat(conto.getNottiNonCoperte()).isZero();
            assertThat(conto.getOspiti()).singleElement()
                    .satisfies(riga -> {
                        assertThat(riga.getNottiTassate()).isEqualTo(3);
                        assertThat(riga.getImporto()).isEqualByComparingTo("6.00");
                        assertThat(riga.getEsenzioneEta()).isFalse();
                        assertThat(riga.getMotivoEsenzione()).isNull();
                    });
        }

        @Test
        @DisplayName("il totale e' la somma delle righe, non un conto a parte")
        void calcola_conPiuOspiti_sommaLeRighe() {
            // given: due adulti
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti(adulto(1L, "Mario"), adulto(2L, "Anna"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: sei euro a testa. Vale la pena provarlo perche' un totale calcolato
            // per conto suo potrebbe non tornare col dettaglio, ed e' il difetto peggiore
            // che un conto possa avere
            assertThat(conto.getTotale()).isEqualByComparingTo("12.00");
            assertThat(conto.getOspiti()).hasSize(2);
        }

        @Test
        @DisplayName("senza ospiti registrati il totale e' zero, non un errore")
        void calcola_senzaOspiti_rispondeZero() {
            // given: una prenotazione su cui non e' stato registrato nessuno, cioe' il
            // caso normale di un carrello ancora IN_ATTESA
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti();

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then
            assertThat(conto.getTotale()).isEqualByComparingTo("0.00");
            assertThat(conto.getOspiti()).isEmpty();
        }
    }

    @Nested
    @DisplayName("esenzione per eta'")
    class Eta {

        @Test
        @DisplayName("un bambino sotto la soglia non paga niente")
        void calcola_conBambino_esente() {
            // given: soglia a 12 anni, il bambino ne ha 6
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, 12));
            ospiti(ospite(1L, "Luca", ARRIVO.minusYears(6), null));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then
            assertThat(conto.getTotale()).isEqualByComparingTo("0.00");
            assertThat(conto.getOspiti().getFirst().getEsenzioneEta()).isTrue();
            assertThat(conto.getOspiti().getFirst().getNottiTassate()).isZero();
        }

        @Test
        @DisplayName("chi compie l'eta' della soglia il giorno dell'arrivo paga")
        void calcola_conCompleannoIlGiornoDellArrivo_paga() {
            // given / when / then: e' il bordo esatto, e cade dalla parte di chi paga —
            // il giorno del compleanno l'eta' ce l'hai gia'
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, 12));
            ospiti(ospite(1L, "Luca", ARRIVO.minusYears(12), null));

            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            assertThat(conto.getTotale()).isEqualByComparingTo("6.00");
            assertThat(conto.getOspiti().getFirst().getEsenzioneEta()).isFalse();
        }

        @Test
        @DisplayName("chi lo compie il giorno dopo l'arrivo e' ancora esente per tutto il soggiorno")
        void calcola_conCompleannoIlGiornoDopo_esentePerTutto() {
            // given: compie 12 anni l'11 giugno, cioe' durante il soggiorno
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, 12));
            ospiti(ospite(1L, "Luca", ARRIVO.minusYears(12).plusDays(1), null));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: l'eta' si valuta all'arrivo, quindi non comincia a pagare a meta'
            // soggiorno. E' la scelta scritta nel contratto, ed e' l'altro lato dello
            // stesso bordo — senza questo test un confronto scritto al contrario
            // passerebbe lo stesso
            assertThat(conto.getTotale()).isEqualByComparingTo("0.00");
            assertThat(conto.getOspiti().getFirst().getEsenzioneEta()).isTrue();
        }

        @Test
        @DisplayName("senza eta' di esenzione sull'aliquota pagano anche i neonati")
        void calcola_senzaEtaEsenzione_paganoTutti() {
            // given: un comune che non esenta nessuno per eta', che esiste
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti(ospite(1L, "Luca", ARRIVO.minusYears(1), null));

            // when / then
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            assertThat(conto.getTotale()).isEqualByComparingTo("6.00");
        }
    }

    @Nested
    @DisplayName("esenzione dichiarata")
    class Dichiarata {

        @Test
        @DisplayName("un residente non paga, e il motivo resta nella risposta")
        void calcola_conResidente_esente() {
            // given
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti(ospite(1L, "Mario", ARRIVO.minusYears(40), MotivoEsenzione.RESIDENTE));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: zero notti tassate, e il perche' e' leggibile — senza il motivo, chi
            // guarda il conto non distinguerebbe un residente da un errore di calcolo
            assertThat(conto.getTotale()).isEqualByComparingTo("0.00");
            TassaSoggiornoOspite riga = conto.getOspiti().getFirst();
            assertThat(riga.getNottiTassate()).isZero();
            assertThat(riga.getMotivoEsenzione())
                    .isEqualTo(com.felixhotel.backend.dto.MotivoEsenzione.RESIDENTE);
            assertThat(riga.getEsenzioneEta()).isFalse();
        }

        @Test
        @DisplayName("il motivo dichiarato esenta anche chi avrebbe pagato, e non guarda le aliquote")
        void calcola_conDichiarataSenzaAliquote_esente() {
            // given: nessuna aliquota configurata *e* un residente. Il residente non deve
            // farsi tassare comunque, ed e' il ramo che si prende per primo
            autenticaStaff();
            prenotazione();
            aliquote();
            ospiti(ospite(1L, "Mario", ARRIVO.minusYears(40), MotivoEsenzione.DISABILE));

            // when / then
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            assertThat(conto.getOspiti().getFirst().getMotivoEsenzione())
                    .isEqualTo(com.felixhotel.backend.dto.MotivoEsenzione.DISABILE);
        }
    }

    @Nested
    @DisplayName("tetto di notti")
    class Tetto {

        @Test
        @DisplayName("oltre il tetto non si paga piu'")
        void calcola_conTettoSuperato_pagaSoloLePrime() {
            // given: cinque notti di soggiorno, tetto a due
            autenticaStaff();
            prenotazione(ARRIVO, ARRIVO.plusDays(5));
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, 2, null));
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: due notti su cinque
            assertThat(conto.getTotale()).isEqualByComparingTo("4.00");
            assertThat(conto.getNottiSoggiorno()).isEqualTo(5);
            assertThat(conto.getOspiti().getFirst().getNottiTassate()).isEqualTo(2);
        }

        @Test
        @DisplayName("un tetto piu' alto delle notti non toglie niente")
        void calcola_conTettoNonRaggiunto_pagaTutto() {
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.minusYears(1), ARRIVO.plusYears(1), DUE_EURO, 10, null));
            ospiti(adulto(1L, "Mario"));

            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            assertThat(conto.getTotale()).isEqualByComparingTo("6.00");
        }

        @Test
        @DisplayName("le notti non coperte non consumano il tetto")
        void calcola_conNottiScoperte_nonConsumanoIlTetto() {
            // given: quattro notti, di cui solo le ultime due coperte da un'aliquota col
            // tetto a due
            autenticaStaff();
            prenotazione(ARRIVO, ARRIVO.plusDays(4));
            aliquote(aliquota(ARRIVO.plusDays(2), ARRIVO.plusYears(1), DUE_EURO, 2, null));
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: due notti pagate e non zero. E' la lettura scelta — il tetto limita
            // quanto si paga, non quante notti passano — ed e' quella che i regolamenti
            // scrivono ("per un massimo di N pernottamenti soggetti a imposta")
            assertThat(conto.getTotale()).isEqualByComparingTo("4.00");
            assertThat(conto.getNottiNonCoperte()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("aliquote mancanti e aliquote che cambiano")
    class Copertura {

        @Test
        @DisplayName("nessuna aliquota configurata: totale zero e tutte le notti scoperte")
        void calcola_senzaAliquote_rispondeZero() {
            // given: e' il caso di un comune senza tassa, o di un'installazione nuova
            autenticaStaff();
            prenotazione();
            aliquote();
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: e' 'nottiNonCoperte' a distinguere questo caso da "sono tutti esenti",
            // che altrimenti darebbe lo stesso zero
            assertThat(conto.getTotale()).isEqualByComparingTo("0.00");
            assertThat(conto.getNottiNonCoperte()).isEqualTo(3);
            assertThat(conto.getOspiti().getFirst().getEsenzioneEta()).isFalse();
        }

        @Test
        @DisplayName("un soggiorno che entra a meta' in un'aliquota paga solo le notti coperte")
        void calcola_conCoperturaParziale_pagaSoloQuelleCoperte() {
            // given: tre notti (10, 11, 12), aliquota dall'11 in poi
            autenticaStaff();
            prenotazione();
            aliquote(aliquota(ARRIVO.plusDays(1), ARRIVO.plusYears(1), DUE_EURO, null, null));
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: due notti su tre
            assertThat(conto.getTotale()).isEqualByComparingTo("4.00");
            assertThat(conto.getNottiNonCoperte()).isEqualTo(1);
        }

        @Test
        @DisplayName("due aliquote che si susseguono si pagano ognuna al suo prezzo")
        void calcola_aCavalloDiDueAliquote_pagaDueTariffe() {
            // given: la prima notte a 2 euro, le altre due a 3. E' il caso per cui il
            // calcolo scorre le notti invece di moltiplicare
            autenticaStaff();
            prenotazione();
            aliquote(
                    aliquota(ARRIVO.minusYears(1), ARRIVO, DUE_EURO, null, null),
                    aliquota(ARRIVO.plusDays(1), ARRIVO.plusYears(1), new BigDecimal("3.00"), null, null));
            ospiti(adulto(1L, "Mario"));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: 2 + 3 + 3. Una moltiplicazione ne avrebbe dette 6 o 9, entrambe
            // sbagliate
            assertThat(conto.getTotale()).isEqualByComparingTo("8.00");
            assertThat(conto.getOspiti().getFirst().getNottiTassate()).isEqualTo(3);
        }

        @Test
        @DisplayName("due aliquote con soglie d'eta' diverse: si e' esenti solo sotto la propria")
        void calcola_conSoglieDiverse_valutaNottePerNotte() {
            // given: un bambino di 10 anni, la prima aliquota esenta sotto i 12 e la
            // seconda sotto i 6
            autenticaStaff();
            prenotazione();
            aliquote(
                    aliquota(ARRIVO.minusYears(1), ARRIVO, DUE_EURO, null, 12),
                    aliquota(ARRIVO.plusDays(1), ARRIVO.plusYears(1), DUE_EURO, null, 6));
            ospiti(ospite(1L, "Luca", ARRIVO.minusYears(10), null));

            // when
            TassaSoggiornoResponse conto = conto(tassaSoggiornoService.calcola(ID_PRENOTAZIONE));

            // then: esente la prima notte, paga le altre due. E' il motivo per cui l'eta'
            // si valuta contro l'aliquota di ogni notte e non una volta sola
            assertThat(conto.getTotale()).isEqualByComparingTo("4.00");
            assertThat(conto.getOspiti().getFirst().getNottiTassate()).isEqualTo(2);
            // resta true perche' almeno una notte l'ha esentato: e' un'informazione, non
            // la promessa che non abbia pagato niente
            assertThat(conto.getOspiti().getFirst().getEsenzioneEta()).isTrue();
        }
    }

    @Nested
    @DisplayName("permesso")
    class Permesso {

        @Test
        @DisplayName("il cliente vede la propria prenotazione")
        void calcola_daProprietario_funziona() {
            autenticaCliente(ID_CLIENTE);
            prenotazione();
            aliquote();
            ospiti();

            assertThat(tassaSoggiornoService.calcola(ID_PRENOTAZIONE).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("la prenotazione di un altro cliente risponde 404, non 403")
        void calcola_daAltroCliente_sollevaNotFound() {
            // given / when / then: un 403 direbbe "esiste, ma non e' tua", cioe'
            // regalerebbe l'informazione che quell'id e' valido
            autenticaCliente(ID_CLIENTE + 1);
            prenotazione();

            assertThatThrownBy(() -> tassaSoggiornoService.calcola(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("non trovata");
        }

        @Test
        @DisplayName("un account del personale col ruolo USER non passa, e prende 401")
        void calcola_conPersonaleSenzaRuolo_sollevaUnauthorized() {
            // given: un account che vive in `staff` ma porta il ruolo USER. Non e'
            // personale (serve anche il ruolo) e non e' un cliente (la sua tabella dice
            // un'altra cosa), quindi non c'e' nessun id con cui confrontare la
            // prenotazione
            autentica(TipoAccount.PERSONALE, "USER", ID_STAFF);
            prenotazione();

            assertThatThrownBy(() -> tassaSoggiornoService.calcola(ID_PRENOTAZIONE))
                    .isInstanceOf(UnauthorizedException.class);
        }

        @Test
        @DisplayName("un cliente col ruolo STAFF resta un cliente: la prenotazione altrui e' 404")
        void calcola_conClienteDalRuoloGonfiato_sollevaNotFound() {
            // given: una riga di `utente` a cui una UPDATE a mano ha dato il ruolo STAFF.
            // E' l'account ibrido contro cui esiste la regola "ruolo *e* tipo": col solo
            // ruolo vedrebbe le prenotazioni di chiunque
            autentica(TipoAccount.CLIENTE, "STAFF", ID_CLIENTE + 1);
            prenotazione();

            // when / then: 404, cioe' esattamente quel che vedrebbe un cliente qualunque.
            // Il ruolo gonfiato non gli ha dato niente
            assertThatThrownBy(() -> tassaSoggiornoService.calcola(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("lo stesso ibrido sulla *propria* prenotazione la vede, ed e' corretto")
        void calcola_conClienteDalRuoloGonfiato_vedeLaPropria() {
            // given / when / then: vale la pena provarlo accanto al test qui sopra, per
            // dire che il 404 di prima non e' "l'ibrido non passa mai" ma "l'ibrido resta
            // il cliente che la sua tabella dice che e'"
            autentica(TipoAccount.CLIENTE, "STAFF", ID_CLIENTE);
            prenotazione();
            aliquote();
            ospiti();

            assertThat(tassaSoggiornoService.calcola(ID_PRENOTAZIONE).getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("su una prenotazione che non esiste risponde 404")
        void calcola_suPrenotazioneInesistente_sollevaNotFound() {
            autenticaStaff();
            when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> tassaSoggiornoService.calcola(ID_PRENOTAZIONE))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("senza nessuna autenticazione nel contesto risponde 401")
        void calcola_senzaAutenticazione_sollevaUnauthorized() {
            prenotazione();

            assertThatThrownBy(() -> tassaSoggiornoService.calcola(ID_PRENOTAZIONE))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    // ---- fabbriche e scorciatoie -------------------------------------------------

    private TassaSoggiornoResponse conto(ApiBaseResponse risposta) {
        return (TassaSoggiornoResponse) risposta.getData();
    }

    private void prenotazione() {
        prenotazione(ARRIVO, PARTENZA);
    }

    /** Mette in bocca al repository una prenotazione del cliente ID_CLIENTE. */
    private void prenotazione(LocalDate arrivo, LocalDate partenza) {
        Utente utente = new Utente();
        utente.setId(ID_CLIENTE);

        Prenotazione prenotazione = new Prenotazione();
        prenotazione.setId(ID_PRENOTAZIONE);
        prenotazione.setUtente(utente);
        prenotazione.setDataCheckIn(arrivo);
        prenotazione.setDataCheckOut(partenza);

        when(prenotazioneRepository.findById(ID_PRENOTAZIONE)).thenReturn(Optional.of(prenotazione));
    }

    /**
     * Le aliquote che la query restituirebbe. {@code lenient} perche' i test del
     * permesso si fermano prima di arrivarci.
     */
    private void aliquote(AliquotaTassaSoggiorno... aliquote) {
        lenient().when(aliquotaRepository.trovaPerSoggiorno(any(), any()))
                .thenReturn(List.of(aliquote));
    }

    private void ospiti(Ospite... ospiti) {
        lenient().when(ospiteRepository.findByPrenotazioneIdOrderByIdAsc(ID_PRENOTAZIONE))
                .thenReturn(List.of(ospiti));
    }

    private AliquotaTassaSoggiorno aliquota(LocalDate inizio, LocalDate fine, BigDecimal importo,
                                            Integer tetto, Integer etaEsenzione) {
        AliquotaTassaSoggiorno aliquota = new AliquotaTassaSoggiorno();
        aliquota.setDataInizio(inizio);
        aliquota.setDataFine(fine);
        aliquota.setImportoPerPersonaNotte(importo);
        aliquota.setNottiMassimeTassate(tetto);
        aliquota.setEtaEsenzione(etaEsenzione);
        return aliquota;
    }

    /** Un adulto senza esenzioni: il caso che paga. */
    private Ospite adulto(Long id, String nome) {
        return ospite(id, nome, ARRIVO.minusYears(40), null);
    }

    private Ospite ospite(Long id, String nome, LocalDate dataNascita, MotivoEsenzione motivo) {
        Ospite ospite = new Ospite();
        ospite.setId(id);
        ospite.setNome(nome);
        ospite.setCognome("Rossi");
        ospite.setDataNascita(dataNascita);
        ospite.setMotivoEsenzione(motivo);
        return ospite;
    }

    private void autenticaStaff() {
        autentica(TipoAccount.PERSONALE, "STAFF", ID_STAFF);
    }

    private void autenticaCliente(Long idCliente) {
        autentica(TipoAccount.CLIENTE, "USER", idCliente);
    }

    private void autentica(TipoAccount tipo, String ruolo, Long userId) {
        AppUserPrincipal principal = new AppUserPrincipal(
                tipo, userId, "mario.rossi@example.com", "hash", "Mario", "Rossi", ruolo, true, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
