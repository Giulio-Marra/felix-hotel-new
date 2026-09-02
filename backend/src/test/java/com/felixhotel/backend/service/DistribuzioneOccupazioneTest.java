package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.DistribuzioneOccupazione;
import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.Periodo;
import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.UnitaOccupata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chi finisce occupato quando si sa <b>quante</b> camere ma non <b>quali</b>.
 *
 * <p><b>E' la classe piu' densa di questo branch</b>, e il motivo e' che il difetto qui
 * non si vede: un calendario sbagliato non fa fallire niente: fa <b>vendere due volte una
 * camera</b>, oppure la fa risultare occupata quando e' libera. Nessuno se ne accorge
 * finche' non arriva la telefonata.
 *
 * <p>Il calcolo e' puro — niente entita', niente database, niente orologio — ed e' tutta
 * la ragione per cui si puo' provare a fondo invece che di sfuggita.
 */
@DisplayName("DistribuzioneOccupazione")
class DistribuzioneOccupazioneTest {

    private static final LocalDate DA = LocalDate.of(2026, 9, 1);
    private static final LocalDate A = LocalDate.of(2026, 9, 30);

    /** Tre camere della stessa tipologia, in ordine stabile. */
    private static final List<Long> CAMERE = List.of(1L, 2L, 3L);

    @Nested
    @DisplayName("distribuzione")
    class Distribuzione {

        @Test
        @DisplayName("una occupazione senza camera va sulla prima libera")
        void unaSola_vaSullaPrima() {
            // when
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", null));

            // then: l'ordine e' la scelta, e la prima e' la prima
            assertThat(perCamera).containsOnlyKeys(1L);
            assertThat(perCamera.get(1L))
                    .containsExactly(periodo("2026-09-10", "2026-09-13"));
        }

        @Test
        @DisplayName("due occupazioni sovrapposte vanno su due camere diverse")
        void dueSovrapposte_vannoSuDueCamere() {
            // when
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", null),
                    unita("2026-09-11", "2026-09-14", null));

            // then: e' il punto di tutta la classe — il totale che il canale vede deve
            // essere giusto, quindi due unita' vendute sono due camere occupate
            assertThat(perCamera).containsOnlyKeys(1L, 2L);
        }

        @Test
        @DisplayName("due occupazioni che non si toccano stanno sulla stessa camera")
        void dueConsecutive_stannoSullaStessa() {
            // when: la prima finisce il 13, la seconda comincia il 15
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", null),
                    unita("2026-09-15", "2026-09-17", null));

            // then: una camera sola, con due periodi. Distribuirle su due camere sarebbe
            // stato ugualmente "corretto" come conteggio, ma avrebbe fatto sembrare
            // occupate due stanze in giorni diversi senza motivo — e un feed che si
            // muove senza motivo e' un feed che un canale non sa piu' seguire
            assertThat(perCamera).containsOnlyKeys(1L);
            assertThat(perCamera.get(1L)).containsExactly(
                    periodo("2026-09-10", "2026-09-13"),
                    periodo("2026-09-15", "2026-09-17"));
        }

        @Test
        @DisplayName("una camera gia' fissata resta dov'e', e le altre si spostano")
        void conCameraFissata_leAltreSiSpostano() {
            // given: un blocco nominale sulla camera 1 — il bagno rotto — e una
            // prenotazione senza camera negli stessi giorni
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", 1L),
                    unita("2026-09-10", "2026-09-13", null));

            // then: la fissata non si muove e l'altra va sulla 2. Senza questa regola la
            // distribuzione avrebbe messo l'occupazione anonima sulla 1 e contato una
            // camera sola, lasciando credere che ce ne fosse una libera in piu'
            assertThat(perCamera).containsOnlyKeys(1L, 2L);
        }

        @Test
        @DisplayName("una camera fissata fuori dall'ordine non fa perdere il conto")
        void conCameraFissataInFondo_ilTotaleResta() {
            // given: la fissata e' l'ultima della lista, non la prima
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", 3L),
                    unita("2026-09-10", "2026-09-13", null),
                    unita("2026-09-10", "2026-09-13", null));

            // then: tre unita' occupate, tre camere. E' il caso che una distribuzione
            // scritta male sbaglia — assegnando alle prime due e poi "riassegnando" la
            // terza a una gia' presa, cioe' contandone due invece di tre
            assertThat(perCamera).containsOnlyKeys(1L, 2L, 3L);
        }

        @Test
        @DisplayName("in overbooking risultano occupate tutte, e non si solleva niente")
        void conPiuOccupazioniCheCamere_leOccupaTutte() {
            // given: quattro unita' vendute e tre camere
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-12", null),
                    unita("2026-09-10", "2026-09-12", null),
                    unita("2026-09-10", "2026-09-12", null),
                    unita("2026-09-10", "2026-09-12", null));

            // then: non e' un dato incoerente da rifiutare — e' un albergo che ha venduto
            // piu' di quanto ha — e la risposta giusta al canale e' "non c'e' piu' niente"
            assertThat(perCamera).containsOnlyKeys(1L, 2L, 3L);
        }

        @Test
        @DisplayName("senza occupazioni non esce nessuna camera")
        void senzaOccupazioni_nessunaCamera() {
            assertThat(distribuisci()).isEmpty();
        }
    }

    @Nested
    @DisplayName("confini del periodo")
    class Confini {

        @Test
        @DisplayName("la data di fine e' esclusa: dal 10 al 13 sono tre notti")
        void finaEsclusa() {
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-10", "2026-09-13", null));

            // then: il periodo che esce e' identico a quello che entra. Se le notti
            // fossero contate con la fine inclusa, il periodo uscirebbe di un giorno piu'
            // lungo — e la camera risulterebbe occupata una notte che e' libera
            assertThat(perCamera.get(1L))
                    .containsExactly(periodo("2026-09-10", "2026-09-13"));
        }

        @Test
        @DisplayName("un'occupazione che comincia prima dell'orizzonte viene tagliata")
        void primaDellOrizzonte_vieneTagliata() {
            // given: comincia ad agosto e finisce il 3 settembre
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-08-20", "2026-09-03", null));

            // then: il feed comincia dal primo giorno dell'orizzonte, non dal passato
            assertThat(perCamera.get(1L))
                    .containsExactly(periodo("2026-09-01", "2026-09-03"));
        }

        @Test
        @DisplayName("un'occupazione che sfora l'orizzonte viene tagliata alla fine")
        void oltreLOrizzonte_vieneTagliata() {
            Map<Long, List<Periodo>> perCamera = distribuisci(
                    unita("2026-09-28", "2026-10-05", null));

            // then: si ferma al 30, che e' il giorno escluso dell'orizzonte
            assertThat(perCamera.get(1L))
                    .containsExactly(periodo("2026-09-28", "2026-09-30"));
        }

        @Test
        @DisplayName("un'occupazione tutta fuori dall'orizzonte non compare")
        void fuoriDallOrizzonte_nonCompare() {
            assertThat(distribuisci(unita("2026-10-10", "2026-10-15", null))).isEmpty();
        }
    }

    // ---------------------------------------------------------------- supporto

    private Map<Long, List<Periodo>> distribuisci(UnitaOccupata... occupazioni) {
        return DistribuzioneOccupazione.distribuisci(CAMERE, List.of(occupazioni), DA, A);
    }

    private UnitaOccupata unita(String inizio, String fine, Long cameraFissata) {
        return new UnitaOccupata(LocalDate.parse(inizio), LocalDate.parse(fine), cameraFissata);
    }

    private Periodo periodo(String inizio, String fine) {
        return new Periodo(LocalDate.parse(inizio), LocalDate.parse(fine));
    }
}
