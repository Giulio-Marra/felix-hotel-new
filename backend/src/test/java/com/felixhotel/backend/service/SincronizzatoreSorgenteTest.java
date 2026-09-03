package com.felixhotel.backend.service;

import com.felixhotel.backend.entity.BloccoDisponibilita;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.SorgenteCalendario;
import com.felixhotel.backend.entity.TipologiaCamera;
import com.felixhotel.backend.entity.enums.EsitoSincronizzazione;
import com.felixhotel.backend.entity.enums.OrigineBlocco;
import com.felixhotel.backend.repository.BloccoDisponibilitaRepository;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.repository.SorgenteCalendarioRepository;
import com.felixhotel.backend.service.impl.LettoreFeedRemoto;
import com.felixhotel.backend.service.impl.SincronizzatoreSorgente;
import com.felixhotel.backend.service.impl.SincronizzatoreSorgente.EsitoSorgente;
import com.felixhotel.backend.support.OrologioPilotato;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le decisioni che il giro di sincronizzazione prende da solo.
 *
 * <p><b>Il database e' finto e va bene cosi'</b>, al contrario dei blocchi dove il valore
 * stava nell'IT: quel che si prova qui non e' se una riga entri, ma <i>quali</i>
 * occupazioni diventino blocchi e quali no — l'eco del nostro stesso feed, le notti
 * passate, la sovrapposizione, la sovravendita. Sono tutte scelte che vivono in questa
 * classe, e con un Postgres vero costerebbero venti volte tanto senza provare niente di
 * piu'. L'{@code SorgenteCalendarioApiIT} fa il giro vero, da un server HTTP fino ai
 * blocchi.
 *
 * <p><b>L'orologio e' pilotato</b> perche' il filtro sulle notti passate e' l'unica cosa
 * qui dentro che dipenda da che giorno sia: con l'orologio di sistema il test funzionerebbe
 * fino alla data scritta nei calendari e poi comincerebbe a fallire da solo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SincronizzatoreSorgente")
class SincronizzatoreSorgenteTest {

    private static final Long ID_SORGENTE = 7L;
    private static final Long ID_CAMERA = 12L;
    private static final Long ID_TIPOLOGIA = 3L;

    /** Il "adesso" di ogni test: tutte le date dei calendari sono relative a questo. */
    private static final LocalDate OGGI = LocalDate.of(2026, 9, 1);

    @Mock
    private SorgenteCalendarioRepository sorgenteRepository;
    @Mock
    private BloccoDisponibilitaRepository bloccoRepository;
    @Mock
    private PrenotazioneRepository prenotazioneRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private LettoreFeedRemoto lettore;

    private SincronizzatoreSorgente sincronizzatore;

    @BeforeEach
    void setUp() {
        OrologioPilotato orologio =
                new OrologioPilotato(OGGI.atStartOfDay().toInstant(ZoneOffset.UTC));

        sincronizzatore = new SincronizzatoreSorgente(sorgenteRepository, bloccoRepository,
                prenotazioneRepository, cameraRepository, lettore, orologio);

        when(sorgenteRepository.trovaConCamera(ID_SORGENTE)).thenReturn(Optional.of(sorgente()));
        // Due camere nella tipologia: sotto questa soglia si e' in sovravendita
        when(cameraRepository.countByTipologiaCameraId(ID_TIPOLOGIA)).thenReturn(2L);
    }

    @Test
    @DisplayName("un'occupazione del canale diventa un blocco che nomina la camera")
    void sincronizza_scriveIlBlocco() {
        feed(evento("booking-1", "20260910", "20260913"));

        EsitoSorgente esito = sincronizzatore.sincronizza(ID_SORGENTE);

        assertThat(esito.esito()).isEqualTo(EsitoSincronizzazione.OK);
        assertThat(esito.messaggio()).isNull();
        assertThat(esito.blocchiScritti()).isEqualTo(1);

        BloccoDisponibilita scritto = bloccoScritto();
        assertThat(scritto.getCamera().getId()).isEqualTo(ID_CAMERA);
        assertThat(scritto.getTipologiaCamera().getId()).isEqualTo(ID_TIPOLOGIA);
        assertThat(scritto.getDataInizio()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(scritto.getDataFine()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(scritto.getOrigine()).isEqualTo(OrigineBlocco.CANALE_ESTERNO);
        assertThat(scritto.getRiferimentoEsterno()).isEqualTo("booking-1");
        assertThat(scritto.getSorgenteCalendario().getId()).isEqualTo(ID_SORGENTE);
    }

    @Test
    @DisplayName("prima di riscrivere cancella i propri blocchi, e solo i propri")
    void sincronizza_cancellaSoloIProprii() {
        feed(evento("booking-1", "20260910", "20260913"));

        sincronizzatore.sincronizza(ID_SORGENTE);

        // Filtrato per sorgente e non per origine: con Booking e Airbnb sulla stessa
        // camera, un filtro sull'origine farebbe portare via a ognuno i blocchi dell'altro
        verify(bloccoRepository).cancellaDellaSorgente(ID_SORGENTE);
    }

    @Test
    @DisplayName("un evento che il canale ci ha rimandato indietro dal nostro feed si ignora")
    void sincronizza_ecoDelNostroFeed_ignorato() {
        // E' il guasto peggiore che questo branch potesse avere: diversi canali
        // ripubblicano le occupazioni lette dal nostro calendario conservandone l'UID.
        // Senza riconoscerle, una nostra prenotazione tornerebbe indietro come blocco e
        // la camera risulterebbe occupata due volte — con tanto di overbooking inventato
        feed(evento("20260910-20260913-1@felix-hotel", "20260910", "20260913"));

        EsitoSorgente esito = sincronizzatore.sincronizza(ID_SORGENTE);

        assertThat(esito.blocchiScritti()).isZero();
        assertThat(esito.esito()).isEqualTo(EsitoSincronizzazione.OK);
        verify(bloccoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("le notti gia' passate non diventano blocchi")
    void sincronizza_nottiPassate_ignorate() {
        // Non si possono vendere comunque, e tenerle vorrebbe dire una tabella che cresce
        // per sempre
        feed(evento("vecchio", "20260810", "20260815"));

        assertThat(sincronizzatore.sincronizza(ID_SORGENTE).blocchiScritti()).isZero();
    }

    @Test
    @DisplayName("un soggiorno cominciato ieri e non finito resta intero")
    void sincronizza_periodoACavallo_restaIntero() {
        // Accorciarlo non servirebbe a niente e renderebbe il blocco diverso da quel che
        // il canale dice
        feed(evento("in-corso", "20260830", "20260904"));

        sincronizzatore.sincronizza(ID_SORGENTE);

        assertThat(bloccoScritto().getDataInizio()).isEqualTo(LocalDate.of(2026, 8, 30));
    }

    @Test
    @DisplayName("se la camera ha gia' un altro blocco su quelle notti, si salta e si segnala")
    void sincronizza_sovrapposizione_segnalata() {
        feed(evento("booking-1", "20260910", "20260913"));
        when(bloccoRepository.esisteSovrapposizioneSuCamera(eq(ID_CAMERA), any(), any(), eq(null)))
                .thenReturn(true);

        EsitoSorgente esito = sincronizzatore.sincronizza(ID_SORGENTE);

        // Si salta e non si insiste: il vincolo di esclusione del V15 rifiuterebbe la
        // riga comunque, e un'eccezione qui fermerebbe le occupazioni successive
        assertThat(esito.esito()).isEqualTo(EsitoSincronizzazione.CONFLITTI);
        assertThat(esito.blocchiScritti()).isZero();
        assertThat(esito.messaggio()).contains("101").contains("ha gia' un altro blocco");
        verify(bloccoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("il blocco si scrive anche quando porta l'albergo in sovravendita, e lo dice")
    void sincronizza_sovravendita_scriveESegnala() {
        // E' la decisione presa aprendo il branch: quando un canale ci dice di aver
        // venduto, l'overbooking e' gia' avvenuto. Rifiutare il blocco non lo annulla,
        // nasconde solo che sia successo — e lascia l'albergo a rivendere quella camera
        feed(evento("booking-1", "20260910", "20260913"));
        when(prenotazioneRepository.occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(), any(), any(), eq(null)))
                .thenReturn(3L);

        EsitoSorgente esito = sincronizzatore.sincronizza(ID_SORGENTE);

        assertThat(esito.blocchiScritti()).isEqualTo(1);
        assertThat(esito.esito()).isEqualTo(EsitoSincronizzazione.CONFLITTI);
        assertThat(esito.messaggio()).contains("3 unita' su 2");
    }

    @Test
    @DisplayName("un albergo pieno esatto non e' una sovravendita")
    void sincronizza_pienoEsatto_nessunConflitto() {
        // Il confine, ed e' il test che conta piu' del suo gemello: con un > scritto come
        // >= ogni albergo al completo segnalerebbe un overbooking che non c'e', e in poche
        // settimane nessuno guarderebbe piu' quella colonna
        feed(evento("booking-1", "20260910", "20260913"));
        when(prenotazioneRepository.occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(), any(), any(), eq(null)))
                .thenReturn(2L);

        assertThat(sincronizzatore.sincronizza(ID_SORGENTE).esito())
                .isEqualTo(EsitoSincronizzazione.OK);
    }

    @Test
    @DisplayName("il messaggio si ferma ai primi conflitti e dice quanti ne restano")
    void sincronizza_moltiConflitti_messaggioLimitato() {
        feed(evento("b1", "20260910", "20260912"),
                evento("b2", "20260915", "20260917"),
                evento("b3", "20260920", "20260922"),
                evento("b4", "20260925", "20260927"),
                evento("b5", "20260928", "20260930"));
        when(prenotazioneRepository.occupazioneMassimaDi(eq(ID_TIPOLOGIA), any(), any(), any(), eq(null)))
                .thenReturn(9L);

        String messaggio = sincronizzatore.sincronizza(ID_SORGENTE).messaggio();

        // La colonna ne tiene mille caratteri: senza un tetto, un albergo con trenta
        // conflitti scriverebbe una riga che il database tronca o rifiuta
        assertThat(messaggio).endsWith("; e altri 2").hasSizeLessThan(1000);
    }

    @Test
    @DisplayName("un identificativo piu' lungo della colonna si taglia, non fa fallire il blocco")
    void sincronizza_uidLunghissimo_taglia() {
        // Un UID e' lungo quanto vuole il canale. Senza tetto sarebbe una violazione di
        // vincolo, e con essa un blocco non scritto — cioe' una camera venduta che torna
        // in vendita. L'UID serve solo a ritrovare la riga del canale: tagliarlo costa la
        // sua coda, non scriverlo costa un overbooking
        feed(evento("x".repeat(400) + "@booking.com", "20260910", "20260913"));

        sincronizzatore.sincronizza(ID_SORGENTE);

        assertThat(bloccoScritto().getRiferimentoEsterno()).hasSize(255);
    }

    @Test
    @DisplayName("un messaggio piu' lungo della colonna si taglia")
    void registraEsito_messaggioLunghissimo_taglia() {
        // Vale soprattutto per il ramo ERRORE, dove il messaggio arriva da un'eccezione di
        // libreria e non ha nessun limite: una scrittura che fallisse qui farebbe saltare
        // l'annotazione, e un canale rotto resterebbe rotto in silenzio
        SorgenteCalendario sorgente = sorgente();
        when(sorgenteRepository.findById(ID_SORGENTE)).thenReturn(Optional.of(sorgente));

        sincronizzatore.registraEsito(ID_SORGENTE, EsitoSincronizzazione.ERRORE, "e".repeat(5000));

        assertThat(sorgente.getUltimoMessaggio()).hasSize(1000);
    }

    @Test
    @DisplayName("l'esito si annota anche quando non c'e' niente da dire")
    void registraEsito_scriveSempre() {
        SorgenteCalendario sorgente = sorgente();
        when(sorgenteRepository.findById(ID_SORGENTE)).thenReturn(Optional.of(sorgente));

        sincronizzatore.registraEsito(ID_SORGENTE, EsitoSincronizzazione.OK, null);

        assertThat(sorgente.getUltimoEsito()).isEqualTo(EsitoSincronizzazione.OK);
        assertThat(sorgente.getUltimoMessaggio()).isNull();
        // Null vuol dire "mai sincronizzata": dopo un giro non puo' restare tale, o
        // l'elenco continuerebbe a dire che quella sorgente non e' mai partita
        assertThat(sorgente.getUltimaSincronizzazione()).isEqualTo(OGGI.atStartOfDay());
    }

    @Test
    @DisplayName("una sorgente sparita fra il giro e l'annotazione non fa rumore")
    void registraEsito_sorgenteSparita_nonSolleva() {
        // Puo' succedere davvero: un ADMIN che toglie la sorgente mentre il giro periodico
        // la sta leggendo. L'annotazione non ha piu' dove andare, e non e' un errore
        when(sorgenteRepository.findById(ID_SORGENTE)).thenReturn(Optional.empty());

        sincronizzatore.registraEsito(ID_SORGENTE, EsitoSincronizzazione.ERRORE, "qualcosa");

        verify(sorgenteRepository, never()).save(any());
    }

    /** Quel che il canale risponde: un calendario con dentro gli eventi indicati. */
    private void feed(String... eventi) {
        String corpo = ("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Un canale qualsiasi//IT
                """ + String.join("", eventi) + "END:VCALENDAR\n").replace("\n", "\r\n");

        when(lettore.scarica(any())).thenReturn(corpo);
    }

    private static String evento(String uid, String inizio, String fine) {
        return "BEGIN:VEVENT\nUID:" + uid
                + "\nDTSTART;VALUE=DATE:" + inizio
                + "\nDTEND;VALUE=DATE:" + fine
                + "\nEND:VEVENT\n";
    }

    private BloccoDisponibilita bloccoScritto() {
        ArgumentCaptor<BloccoDisponibilita> catturato =
                ArgumentCaptor.forClass(BloccoDisponibilita.class);
        verify(bloccoRepository).saveAndFlush(catturato.capture());
        return catturato.getValue();
    }

    private static SorgenteCalendario sorgente() {
        TipologiaCamera tipologia = new TipologiaCamera();
        tipologia.setId(ID_TIPOLOGIA);
        tipologia.setNome("Doppia");

        Camera camera = new Camera();
        camera.setId(ID_CAMERA);
        camera.setNumero("101");
        camera.setTipologiaCamera(tipologia);

        SorgenteCalendario sorgente = new SorgenteCalendario();
        sorgente.setId(ID_SORGENTE);
        sorgente.setCamera(camera);
        sorgente.setNome("Booking");
        sorgente.setUrl("https://esempio.invalid/calendario.ics");
        return sorgente;
    }
}
