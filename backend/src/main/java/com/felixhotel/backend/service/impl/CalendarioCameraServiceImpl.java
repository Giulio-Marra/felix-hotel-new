package com.felixhotel.backend.service.impl;

import com.felixhotel.backend.config.EmailProperties;
import com.felixhotel.backend.dto.ApiBaseResponse;
import com.felixhotel.backend.dto.CalendarioCameraResponse;
import com.felixhotel.backend.entity.BloccoDisponibilita;
import com.felixhotel.backend.entity.Camera;
import com.felixhotel.backend.entity.Prenotazione;
import com.felixhotel.backend.entity.enums.StatoPrenotazione;
import com.felixhotel.backend.exception.NotFoundException;
import com.felixhotel.backend.mapper.ApiResponseMapper;
import com.felixhotel.backend.repository.BloccoDisponibilitaRepository;
import com.felixhotel.backend.repository.CameraRepository;
import com.felixhotel.backend.repository.PrenotazioneRepository;
import com.felixhotel.backend.service.CalendarioCameraService;
import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.Periodo;
import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.UnitaOccupata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Il calendario iCal di una camera, e l'indirizzo da cui si scarica.
 *
 * <p><b>Due operazioni molto diverse fra loro.</b> {@link #feed} e' <b>pubblica</b> e la
 * chiama Booking senza autenticarsi — a difenderla c'e' solo il token, che ha 256 bit;
 * {@link #generaIndirizzo} e' degli ADMIN ed e' quella che decide di pubblicare una
 * camera. Stanno insieme perche' parlano della stessa cosa, non perche' si somiglino.
 *
 * <p><b>Il calcolo vero non e' qui</b>: sta in {@link DistribuzioneOccupazione}, che
 * decide <i>quale</i> camera risulti occupata quando si sa solo <i>quante</i>, e in
 * {@link CalendarioIcs}, che scrive il formato. Questo Service raccoglie i dati e li
 * mette in fila — ed e' la stessa divisione gia' fatta per le schedine alloggiati, dove
 * il tracciato e' una classe sua proprio per poterlo provare senza database.
 */
@Service
public class CalendarioCameraServiceImpl implements CalendarioCameraService {

    /**
     * Quanto avanti guarda il calendario.
     *
     * <p>Dodici mesi: e' l'orizzonte su cui un albergo vende davvero, ed e' anche quello
     * che i canali si aspettano. Piu' avanti sarebbe un file piu' grosso con dentro
     * occupazioni che quasi non esistono; meno avanti vorrebbe dire che una prenotazione
     * per l'estate prossima non arriva al canale, cioe' un doppio venduto.
     *
     * <p><b>Non e' configurabile</b> (regola 24): non e' qualcosa che due alberghi
     * vorrebbero diverso.
     */
    private static final int MESI_DI_ORIZZONTE = 12;

    /** Come per i token delle email: 256 bit, cioe' un indirizzo che non si indovina. */
    private static final int BYTE_DEL_TOKEN = 32;

    private final SecureRandom casuale = new SecureRandom();

    private final CameraRepository cameraRepository;
    private final PrenotazioneRepository prenotazioneRepository;
    private final BloccoDisponibilitaRepository bloccoRepository;
    private final ApiResponseMapper apiResponseMapper;

    /**
     * Serve solo a comporre l'indirizzo pubblico del feed. E' la stessa proprieta' che
     * costruisce i link delle email: l'indirizzo da cui l'applicazione si raggiunge da
     * fuori e' uno solo, e sta in un posto solo.
     */
    private final EmailProperties proprieta;

    private final Clock clock;

    @Autowired
    public CalendarioCameraServiceImpl(CameraRepository cameraRepository,
                                       PrenotazioneRepository prenotazioneRepository,
                                       BloccoDisponibilitaRepository bloccoRepository,
                                       ApiResponseMapper apiResponseMapper,
                                       EmailProperties proprieta) {
        this(cameraRepository, prenotazioneRepository, bloccoRepository, apiResponseMapper,
                proprieta, Clock.systemDefaultZone());
    }

    /** Costruttore per i test, che passano un {@code OrologioPilotato}. */
    CalendarioCameraServiceImpl(CameraRepository cameraRepository,
                                PrenotazioneRepository prenotazioneRepository,
                                BloccoDisponibilitaRepository bloccoRepository,
                                ApiResponseMapper apiResponseMapper,
                                EmailProperties proprieta,
                                Clock clock) {
        this.cameraRepository = cameraRepository;
        this.prenotazioneRepository = prenotazioneRepository;
        this.bloccoRepository = bloccoRepository;
        this.apiResponseMapper = apiResponseMapper;
        this.proprieta = proprieta;
        this.clock = clock;
    }

    /**
     * Il calendario, come testo iCal.
     *
     * <p><b>Un token che non esiste e' 404</b>, non 401 o 403: chi chiama non e' un
     * utente che ha sbagliato permessi, e' un indirizzo che non corrisponde a niente. E'
     * anche l'unica risposta che non dica se quel token sia mai esistito.
     */
    @Override
    @Transactional(readOnly = true)
    public String feed(String token) {
        Camera camera = cameraRepository.findByTokenCalendario(token)
                .orElseThrow(() -> new NotFoundException("Calendario non trovato"));

        LocalDate da = LocalDate.now(clock);
        LocalDate a = da.plusMonths(MESI_DI_ORIZZONTE);

        Long tipologia = camera.getTipologiaCamera().getId();

        List<Long> camere = cameraRepository.findByTipologiaCameraIdOrderByIdAsc(tipologia)
                .stream().map(Camera::getId).toList();

        List<UnitaOccupata> occupazioni = new ArrayList<>();
        for (Prenotazione p : prenotazioneRepository.occupazioniNellOrizzonte(
                tipologia, StatoPrenotazione.statiCheOccupano(), da, a)) {
            // La camera c'e' solo dopo il check-in: prima di allora questa prenotazione
            // occupa "una doppia qualsiasi", ed e' quel che la distribuzione risolve.
            occupazioni.add(new UnitaOccupata(p.getDataCheckIn(), p.getDataCheckOut(),
                    p.getCamera() == null ? null : p.getCamera().getId()));
        }
        for (BloccoDisponibilita b : bloccoRepository.occupazioniNellOrizzonte(tipologia, da, a)) {
            occupazioni.add(new UnitaOccupata(b.getDataInizio(), b.getDataFine(),
                    b.getCamera() == null ? null : b.getCamera().getId()));
        }

        Map<Long, List<Periodo>> perCamera =
                DistribuzioneOccupazione.distribuisci(camere, occupazioni, da, a);

        return CalendarioIcs.calendario("Camera " + camera.getNumero(),
                perCamera.getOrDefault(camera.getId(), List.of()), da);
    }

    /**
     * Genera — o rigenera — l'indirizzo del calendario di una camera.
     *
     * <p><b>Rigenerarlo invalida il precedente</b>, ed e' tutto il motivo per cui questa
     * operazione esiste invece di un token derivato dall'id: se un indirizzo finisce dove
     * non doveva, si cambia. Il canale che lo stava leggendo smette di ricevere
     * aggiornamenti e va riconfigurato — che e' esattamente quel che si vuole quando si
     * revoca un accesso, ma va saputo prima di premere il pulsante.
     */
    @Override
    @Transactional
    public ApiBaseResponse generaIndirizzo(Long cameraId) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new NotFoundException("Camera non trovata"));

        byte[] byteCasuali = new byte[BYTE_DEL_TOKEN];
        casuale.nextBytes(byteCasuali);
        // URL-safe e senza riempimento: questo valore finisce dentro un indirizzo, e un
        // '+' o un '=' in un percorso sono due modi diversi di rovinarlo.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(byteCasuali);

        camera.setTokenCalendario(token);
        cameraRepository.save(camera);

        return apiResponseMapper.toResponse(HttpStatus.OK, "Indirizzo del calendario generato",
                new CalendarioCameraResponse()
                        .cameraId(camera.getId())
                        .numero(camera.getNumero())
                        .url(proprieta.baseUrl() + "/api/calendario/" + token + ".ics"));
    }
}
