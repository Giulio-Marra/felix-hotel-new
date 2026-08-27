package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.dto.TipologiaCameraDotazioniRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Costruisce i dati di partenza dei test.
 *
 * <p>Esiste per una ragione di forma: nessun test deve contenere il rituale di
 * riempimento di un DTO campo per campo. Un test si legge per quello che
 * verifica, non per come si prepara i dati — quindi parte da un oggetto valido
 * e cambia solo il campo che gli interessa
 * ({@code registerRequest().password("corta")}).
 *
 * <p>Se domani un DTO guadagna un campo obbligatorio, si aggiorna questa
 * classe e non trenta test.
 */
public class TestDataFactory {

    /** Password valida di default: sopra il minimo di 8 caratteri dichiarato nello spec. */
    public static final String PASSWORD_VALIDA = "PasswordSicura123";

    /**
     * Contatore per email univoche: statico perche' l'unicita' deve valere per
     * l'intera esecuzione, non per la singola classe di test. Il database e'
     * condiviso e non viene ripulito fra un test e l'altro, quindi due test che
     * usassero la stessa email si romperebbero a vicenda con un 409.
     */
    private static final AtomicInteger CONTATORE = new AtomicInteger();

    /** Email mai usata prima in questa esecuzione. */
    public String emailUnivoca() {
        return "test.utente." + System.currentTimeMillis() + "." + CONTATORE.incrementAndGet() + "@example.com";
    }

    /**
     * Richiesta di registrazione valida in ogni campo. I test la modificano
     * con i setter fluenti dei DTO generati per creare il caso che vogliono
     * verificare.
     */
    public RegisterRequest registerRequest() {
        return new RegisterRequest()
                .nome("Mario")
                .cognome("Rossi")
                .email(emailUnivoca())
                .password(PASSWORD_VALIDA)
                .consensoPrivacy(true);
    }

    /** Credenziali di login corrispondenti a una registrazione andata a buon fine. */
    public LoginRequest loginRequest(String email, String password) {
        return new LoginRequest()
                .email(email)
                .password(password);
    }

    /**
     * Nome di tipologia mai usato prima in questa esecuzione. Stessa ragione
     * delle email: il nome e' unico in database (indice su lower(nome)) e il
     * database non viene ripulito fra un test e l'altro, quindi due test che
     * usassero "Doppia" si romperebbero a vicenda con un 409.
     */
    public String nomeTipologiaUnivoco() {
        return "Doppia " + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet();
    }

    /**
     * Tipologia di camera valida in ogni campo. Come per la registrazione, i
     * test partono da questa e cambiano solo il campo che vogliono verificare
     * ({@code tipologiaCameraRequest().capienzaMax(0)}).
     */
    public TipologiaCameraRequest tipologiaCameraRequest() {
        return new TipologiaCameraRequest()
                .nome(nomeTipologiaUnivoco())
                .descrizione("Camera doppia con vista sul giardino")
                .capienzaMax(2)
                .prezzoNotte(new BigDecimal("120.00"));
    }

    /**
     * Un prezzo per notte che nessun'altra tipologia ha, preso dallo stesso
     * contatore che genera i nomi.
     *
     * <p><b>Serve a isolare i test della ricerca di disponibilita'</b>, che a
     * differenza di tutti gli altri elenchi guarda <b>l'intero catalogo</b>: li'
     * crearsi la propria tipologia non basta a stare per conto proprio, perche'
     * nella risposta finiscono anche quelle degli altri test. Il filtro di
     * prezzo, stretto su un valore che appartiene a una tipologia sola, ritaglia
     * la propria riga — e usa per farlo uno dei filtri che quei test devono
     * comunque esercitare.
     *
     * <p>Parte da 1000 per stare sopra ai prezzi scritti a mano altrove, cosi'
     * un intervallo stretto qui non puo' pescare per sbaglio una tipologia
     * creata da un altro test con il prezzo di default.
     */
    public BigDecimal prezzoUnivoco() {
        return new BigDecimal(1000 + CONTATORE.incrementAndGet()).setScale(2);
    }

    /**
     * Nome di dotazione mai usato prima in questa esecuzione, per la stessa
     * ragione del nome di tipologia: e' unico in database (indice su
     * lower(nome), vedi V3) e il database non viene ripulito fra un test e
     * l'altro.
     */
    public String nomeDotazioneUnivoco() {
        return nomeDotazioneUnivoco("Wi-Fi");
    }

    /**
     * Come sopra, ma con un prefisso scelto da chi chiama. Serve ai test che
     * verificano l'<b>ordinamento</b> delle dotazioni: li' i nomi devono essere
     * univoci — il vincolo vale comunque — e insieme in un ordine alfabetico
     * noto, che con un prefisso solo non sarebbe esprimibile.
     */
    public String nomeDotazioneUnivoco(String prefisso) {
        return prefisso + " " + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet();
    }

    /**
     * Dotazione valida in ogni campo. Come per le altre, i test partono da
     * questa e cambiano solo il campo che vogliono verificare
     * ({@code dotazioneRequest().nome("")}).
     */
    public DotazioneRequest dotazioneRequest() {
        return new DotazioneRequest()
                .nome(nomeDotazioneUnivoco())
                .descrizione("Connessione senza fili gratuita in tutta la struttura");
    }

    /**
     * Richiesta che assegna a una tipologia esattamente le dotazioni indicate.
     * Senza argomenti produce l'insieme vuoto, che e' il modo legittimo di
     * toglierle tutte e non un caso limite da evitare.
     */
    public TipologiaCameraDotazioniRequest dotazioniIdsRequest(Long... ids) {
        return new TipologiaCameraDotazioniRequest()
                .dotazioniIds(new LinkedHashSet<>(Arrays.asList(ids)));
    }

    /**
     * Numero di camera mai usato prima in questa esecuzione. Stessa ragione delle
     * email e degli altri nomi: e' unico in database (indice su lower(numero),
     * vedi V4) e il database non viene ripulito fra un test e l'altro.
     *
     * <p>Comincia per lettera di proposito: cosi' i numeri generati non si
     * confondono con quelli scritti a mano da un test che voglia controllare
     * l'ordinamento.
     */
    public String numeroCameraUnivoco() {
        return "C" + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet();
    }

    /**
     * Camera valida in ogni campo, appartenente alla tipologia indicata — che
     * deve esistere davvero, perche' il service risolve quell'id e risponde 400
     * se non lo trova.
     *
     * <p>Lo stato non e' valorizzato: la camera nasce LIBERA, che e' il caso
     * normale. I test che vogliono altro lo impostano
     * ({@code cameraRequest(id).stato(StatoCamera.MANUTENZIONE)}).
     */
    public CameraRequest cameraRequest(Long tipologiaCameraId) {
        return new CameraRequest()
                .numero(numeroCameraUnivoco())
                .piano(1)
                .tipologiaCameraId(tipologiaCameraId);
    }

    /** Richiesta di cambio stato per una camera. */
    public CameraStatoRequest cameraStatoRequest(StatoCamera stato) {
        return new CameraStatoRequest().stato(stato);
    }

    /**
     * Indirizzo di immagine mai usato prima in questa esecuzione.
     *
     * <p>Univoco per la stessa ragione degli altri: l'url e' unica dentro la
     * tipologia (indice su (tipologia_camera_id, url), vedi V5) e il database non
     * viene ripulito fra un test e l'altro. Qui il rischio sarebbe minore — due
     * test lavorano quasi sempre su tipologie diverse — ma un valore univoco
     * costa niente e toglie di mezzo la domanda.
     *
     * <p>Comincia per {@code https://} perche' lo spec lo pretende: il campo ha
     * un {@code @Pattern} sullo schema, e un url senza finirebbe a 400 prima di
     * arrivare dove il test voleva guardare.
     */
    public String urlMediaUnivoco() {
        return "https://cdn.felixhotel.example/camere/foto-"
                + System.currentTimeMillis() + "-" + CONTATORE.incrementAndGet() + ".jpg";
    }

    /**
     * Foto valida da aggiungere a una galleria. Come le altre richieste, i test
     * partono da questa e cambiano solo il campo che vogliono verificare
     * ({@code mediaCameraRequest().url("ftp://altrove")}).
     */
    public MediaCameraRequest mediaCameraRequest() {
        return new MediaCameraRequest().url(urlMediaUnivoco());
    }

    /**
     * Richiesta di riordino con la sequenza indicata.
     *
     * <p>Prende una {@code List} e non un insieme, al contrario di
     * {@link #dotazioniIdsRequest}: qui l'ordine degli argomenti <b>e'</b> il
     * contenuto del messaggio, e passare per un Set lo perderebbe per strada
     * proprio nei test che devono verificarlo.
     */
    public MediaCameraOrdineRequest mediaOrdineRequest(Long... mediaIds) {
        return new MediaCameraOrdineRequest().mediaIds(Arrays.asList(mediaIds));
    }

    /**
     * Prenotazione valida per la tipologia indicata — che deve esistere e avere
     * almeno una camera libera, perche' il service risolve quell'id e conta le
     * stanze.
     *
     * <p><b>Le date sono nel futuro e restano tali</b>: il service rifiuta un
     * arrivo gia' passato, quindi una data fissa scritta qui dentro farebbe
     * passare la suite fino a quel giorno e poi mai piu'. Sono tre notti, cosi'
     * il totale atteso e' il prezzo moltiplicato per tre e non coincide col
     * prezzo di una notte — un test in cui i due numeri fossero uguali passerebbe
     * anche se la moltiplicazione sparisse.
     *
     * <p>{@code utenteId} e {@code canale} non sono valorizzati: sono i campi
     * riservati a chi registra la prenotazione di un cliente, e da un USER sono
     * un 400. I test del personale li aggiungono
     * ({@code prenotazioneRequest(id).utenteId(7L).canale(TELEFONO)}).
     */
    public PrenotazioneRequest prenotazioneRequest(Long tipologiaCameraId) {
        return new PrenotazioneRequest()
                .tipologiaCameraId(tipologiaCameraId)
                .dataCheckIn(LocalDate.now().plusDays(7))
                .dataCheckOut(LocalDate.now().plusDays(10))
                .numeroOspiti(2);
    }

    /**
     * Corpo dell'annullamento. Il motivo e' facoltativo nello spec: chi vuole
     * provare l'annullamento senza motivo passa direttamente null come corpo,
     * che non e' lo stesso di questo oggetto col campo vuoto.
     */
    public PrenotazioneAnnullamentoRequest annullamentoRequest(String motivo) {
        return new PrenotazioneAnnullamentoRequest().motivo(motivo);
    }
}
