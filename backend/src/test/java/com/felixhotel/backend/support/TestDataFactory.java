package com.felixhotel.backend.support;

import com.felixhotel.backend.dto.AliquotaTassaSoggiornoRequest;
import com.felixhotel.backend.dto.CameraRequest;
import com.felixhotel.backend.dto.CameraStatoRequest;
import com.felixhotel.backend.dto.DotazioneRequest;
import com.felixhotel.backend.dto.ImpostazioniHotelRequest;
import com.felixhotel.backend.dto.LoginRequest;
import com.felixhotel.backend.dto.MediaCameraOrdineRequest;
import com.felixhotel.backend.dto.MediaCameraRequest;
import com.felixhotel.backend.dto.OspiteRequest;
import com.felixhotel.backend.dto.PeriodoTariffarioRequest;
import com.felixhotel.backend.dto.PrenotazioneAnnullamentoRequest;
import com.felixhotel.backend.dto.PrenotazioneRequest;
import com.felixhotel.backend.dto.RegisterRequest;
import com.felixhotel.backend.dto.RuoloStaff;
import com.felixhotel.backend.dto.StaffAggiornamentoRequest;
import com.felixhotel.backend.dto.StaffAttivazioneRequest;
import com.felixhotel.backend.dto.StaffPasswordRequest;
import com.felixhotel.backend.dto.StaffRequest;
import com.felixhotel.backend.dto.StatoCamera;
import com.felixhotel.backend.dto.TipoDocumento;
import com.felixhotel.backend.dto.TipologiaCameraDotazioniRequest;
import com.felixhotel.backend.dto.TipologiaCameraRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
     * Periodo tariffario valido in ogni campo, sulle date date. Come per le
     * altre richieste, i test partono da questo e cambiano il solo campo che
     * vogliono verificare ({@code periodoTariffarioRequest(a, b).soggiornoMinimo(3)}).
     *
     * <p>Il nome non e' univoco e non deve esserlo: al contrario di tipologie,
     * dotazioni e camere, due periodi possono chiamarsi uguale — a distinguerli
     * sono le date, che invece non possono accavallarsi.
     */
    public PeriodoTariffarioRequest periodoTariffarioRequest(LocalDate dataInizio, LocalDate dataFine) {
        return new PeriodoTariffarioRequest()
                .nome("Alta stagione")
                .dataInizio(dataInizio)
                .dataFine(dataFine)
                .prezzoNotte(new BigDecimal("180.00"))
                .soggiornoMinimo(1);
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
        return nomeUnivoco(prefisso);
    }

    /**
     * Un nome che nessun'altra chiamata di questa esecuzione produrra'. E' il
     * meccanismo dietro {@link #nomeDotazioneUnivoco(String)} e dietro il nome
     * della struttura, che non ha nessun vincolo di unicita' da rispettare ma ha
     * lo stesso bisogno: distinguere quello che il test ha appena scritto da
     * quello che c'era prima.
     */
    public String nomeUnivoco(String prefisso) {
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
        // Una lettura sola dell'orologio per tutte e due le date: con due chiamate
        // separate, una richiesta costruita a cavallo della mezzanotte otterrebbe un
        // soggiorno di quattro notti invece di tre.
        LocalDate arrivo = dataArrivoDefault();
        return new PrenotazioneRequest()
                .tipologiaCameraId(tipologiaCameraId)
                .dataCheckIn(arrivo)
                .dataCheckOut(arrivo.plusDays(3))
                .numeroOspiti(2);
    }

    /**
     * Il giorno di arrivo della prenotazione di default.
     *
     * <p>Un metodo e non una costante, perche' dipende da {@code now()} e una
     * costante statica la fisserebbe al caricamento della classe — una differenza
     * invisibile per tutta la giornata e poi una suite rossa a mezzanotte.
     *
     * <p><b>Esiste dal V10</b>, che ha reso questa data una cosa che i test devono
     * poter nominare: e' su di essa che si decide se un ospite debba avere un
     * documento, quindi chi costruisce un minorenne deve partire da qui e non da una
     * data scritta a mano che domani potrebbe non combaciare.
     */
    public LocalDate dataArrivoDefault() {
        return LocalDate.now().plusDays(7);
    }

    /**
     * Un ospite valido da registrare su una prenotazione.
     *
     * <p><b>Il numero di documento va quasi sempre sovrascritto</b> da chi
     * chiama: l'indice unico del V7 e' su (prenotazione, tipo, numero), quindi
     * due ospiti sulla stessa prenotazione con questo valore di partenza
     * sarebbero un 409 — che e' giusto, ed e' il motivo per cui il valore qui e'
     * uno e non una sequenza. Una fabbrica che generasse numeri diversi ad ogni
     * chiamata nasconderebbe quel vincolo proprio ai test che devono vederlo.
     *
     * <p><b>E' un adulto</b>, ed e' la scelta che conta dal V10: nato nel 1985,
     * cioe' maggiorenne a qualunque data di arrivo un test possa usare, quindi il
     * documento e' obbligatorio e c'e'. Il caso opposto — il minorenne senza
     * documento — ha la sua fabbrica in {@link #ospiteMinorenneRequest()}, che lo
     * dice nel nome: un test che registra un bambino deve leggersi come tale senza
     * andare a contare gli anni.
     */
    public OspiteRequest ospiteRequest() {
        return new OspiteRequest()
                .nome("Mario")
                .cognome("Rossi")
                .tipoDocumento(TipoDocumento.CARTA_IDENTITA)
                .numeroDocumento("CA12345AB")
                .dataNascita(LocalDate.of(1985, 4, 17));
    }

    /**
     * Un minorenne da registrare senza documento proprio, che dal V10 e' il caso
     * che questa risorsa sa rappresentare e prima no.
     *
     * <p>La data di nascita si calcola da {@link #dataArrivoDefault()} e non e' una
     * costante, ed e' il punto: il documento e' obbligatorio per chi e' maggiorenne
     * <b>alla data di arrivo</b>, quindi una data fissa renderebbe il test giusto
     * oggi e sbagliato fra vent'anni. Qui il bambino ha dieci anni il giorno in cui
     * arriva, sempre. Chi sposta l'arrivo della prenotazione — c'e' chi lo mette a
     * oggi per poter fare il check-in — resta comunque coperto: dieci anni prima di
     * una settimana fa e' minorenne allo stesso modo.
     *
     * <p>Niente {@code tipoDocumento} e niente {@code numeroDocumento}: e'
     * esattamente cio' che si sta verificando, e metterceli renderebbe questa
     * fabbrica indistinguibile da quella di sopra.
     */
    public OspiteRequest ospiteMinorenneRequest() {
        return new OspiteRequest()
                .nome("Luca")
                .cognome("Rossi")
                .dataNascita(dataArrivoDefault().minusYears(10));
    }

    /**
     * Un'aliquota della tassa di soggiorno valida in ogni campo.
     *
     * <p><b>Copre un anno intero attorno all'arrivo di default</b> invece di date
     * fisse, e per la stessa ragione per cui il minorenne si costruisce dall'arrivo:
     * un'aliquota scritta con anni costanti smetterebbe di coprire i soggiorni dei
     * test il giorno in cui quell'anno passa. Cosi' copre sempre.
     *
     * <p><b>Due aliquote di questa fabbrica si sovrappongono</b>, ed e' voluto: la
     * sovrapposizione e' il vincolo centrale di questa risorsa, e una fabbrica che
     * generasse date sempre diverse lo nasconderebbe proprio ai test che devono
     * vederlo. Chi ne vuole due che convivono sposta le date a mano.
     *
     * <p>Tetto ed eta' di esenzione ci sono tutti e due, coi valori che ricorrono
     * nei regolamenti veri: chi vuole provarne l'assenza li mette a null
     * ({@code aliquotaRequest().etaEsenzione(null)}).
     */
    public AliquotaTassaSoggiornoRequest aliquotaRequest() {
        LocalDate arrivo = dataArrivoDefault();
        return new AliquotaTassaSoggiornoRequest()
                .dataInizio(arrivo.minusMonths(6))
                .dataFine(arrivo.plusMonths(6))
                .importoPerPersonaNotte(new BigDecimal("2.00"))
                .nottiMassimeTassate(5)
                .etaEsenzione(12);
    }

    /**
     * Corpo dell'annullamento. Il motivo e' facoltativo nello spec: chi vuole
     * provare l'annullamento senza motivo passa direttamente null come corpo,
     * che non e' lo stesso di questo oggetto col campo vuoto.
     */
    public PrenotazioneAnnullamentoRequest annullamentoRequest(String motivo) {
        return new PrenotazioneAnnullamentoRequest().motivo(motivo);
    }

    /**
     * Account del personale valido in ogni campo, con ruolo STAFF.
     *
     * <p>Il ruolo di default e' quello che non ha privilegi speciali: i test che
     * vogliono un amministratore lo dicono
     * ({@code staffRequest().ruolo(RuoloStaff.ADMIN)}), e cosi' un ADMIN in piu'
     * in database compare solo dove qualcuno l'ha voluto — cosa che conta,
     * perche' il conteggio degli amministratori attivi e' una regola di questa
     * risorsa.
     *
     * <p>L'email e' univoca per la solita ragione, con in piu' una sua: qui
     * l'unicita' vale sull'insieme di clienti e personale, quindi un indirizzo
     * fisso si scontrerebbe anche con i test della registrazione.
     */
    public StaffRequest staffRequest() {
        return new StaffRequest()
                .nome("Anna")
                .cognome("Bianchi")
                .email(emailUnivoca())
                .password(PASSWORD_VALIDA)
                .telefono("+39 333 1234567")
                .dataAssunzione(LocalDate.of(2024, 3, 1))
                .ruolo(RuoloStaff.STAFF);
    }

    /**
     * Aggiornamento valido di un account del personale: gli stessi campi della
     * creazione tranne la password, che ha il suo endpoint.
     *
     * <p>Prende l'email invece di generarla, al contrario di
     * {@link #staffRequest()}: e' una PUT, e il caso normale e' riconfermare
     * quella che l'account ha gia' — che e' anche il caso in cui il controllo di
     * unicita' non deve scattare contro se stesso.
     */
    public StaffAggiornamentoRequest staffAggiornamentoRequest(String email, RuoloStaff ruolo) {
        return new StaffAggiornamentoRequest()
                .nome("Anna")
                .cognome("Bianchi")
                .email(email)
                .telefono("+39 333 1234567")
                .dataAssunzione(LocalDate.of(2024, 3, 1))
                .ruolo(ruolo);
    }

    /** Corpo di attivazione o disattivazione di un account del personale. */
    public StaffAttivazioneRequest staffAttivazioneRequest(boolean attivo) {
        return new StaffAttivazioneRequest().attivo(attivo);
    }

    /** Corpo del cambio password di un account del personale. */
    public StaffPasswordRequest staffPasswordRequest(String password) {
        return new StaffPasswordRequest().password(password);
    }

    /**
     * Impostazioni della struttura valide in ogni campo, facoltativi compresi.
     *
     * <p><b>Il nome e' univoco anche se nessun vincolo lo pretende</b>, al
     * contrario delle altre richieste dove serve a non violare un indice. Qui
     * la riga e' una sola e ogni test la sovrascrive, quindi senza un valore
     * diverso ad ogni chiamata un test che verifica di aver salvato passerebbe
     * anche trovando quello che c'era prima.
     */
    public ImpostazioniHotelRequest impostazioniHotelRequest() {
        return new ImpostazioniHotelRequest()
                .nome(nomeUnivoco("Felix Hotel"))
                .indirizzo("Via Roma 1, 47921 Rimini (RN)")
                .telefono("+39 0541 123456")
                .email("info@felixhotel.it")
                .orarioCheckInDefault(LocalTime.of(14, 0))
                .orarioCheckOutDefault(LocalTime.of(10, 0))
                .ragioneSociale("Felix Hotel S.r.l.")
                .partitaIva("01234567890")
                .codiceFiscale("01234567890")
                .cin("IT099014B4XYZW1234")
                .comune("Rimini")
                .codiceIstatComune("099014")
                .codiceStrutturaAlloggiati("RN012345");
    }
}
