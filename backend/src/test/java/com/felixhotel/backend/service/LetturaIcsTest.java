package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.LetturaIcs;
import com.felixhotel.backend.service.impl.LetturaIcs.IcsIlleggibileException;
import com.felixhotel.backend.service.impl.LetturaIcs.OccupazioneEsterna;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La lettura dei calendari altrui.
 *
 * <p><b>E' il test piu' importante del branch</b>, e la ragione sta in una asimmetria: un
 * evento letto male <i>in meno</i> e' una camera che si rivende — un cliente che arriva e
 * non ha dove dormire — mentre uno letto male <i>in piu'</i> e' solo una camera che resta
 * invenduta. I due errori non si equivalgono, quindi ogni caso qui sotto prova soprattutto
 * la prima direzione.
 *
 * <p><b>I file sono scritti a mano e non generati da {@code CalendarioIcs}</b>, che pure
 * ci sarebbe. Sarebbe il modo piu' comodo e il meno utile: proverebbe che sappiamo
 * rileggere quel che scriviamo noi, mentre il problema e' rileggere quel che scrive
 * Booking. Per questo qui dentro ci sono righe piegate, valori protetti e orari con il
 * fuso — cose che il nostro scrittore non produce mai.
 */
@DisplayName("LetturaIcs")
class LetturaIcsTest {

    @Test
    @DisplayName("un evento di sole date diventa un'occupazione, con la fine esclusa")
    void occupazioni_eventoDiSoleDate() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:abc-123@booking.com
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260913
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione -> {
            assertThat(occupazione.uid()).isEqualTo("abc-123@booking.com");
            assertThat(occupazione.inizio()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(occupazione.fine()).isEqualTo(LocalDate.of(2026, 9, 13));
        });
    }

    @Test
    @DisplayName("di un orario si prende il giorno scritto, non l'istante")
    void occupazioni_conOrario_prendeIlGiornoScritto() {
        // Le 23 UTC del 3 diventerebbero il 4 in mezza Europa se questo valore passasse
        // per un fuso: la notte occupata cambierebbe giorno a seconda di dove gira il
        // backend, ed e' esattamente il difetto che getRawComponents() evita
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:orario@booking.com
                DTSTART:20260903T230000Z
                DTEND:20260905T100000Z
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione -> {
            assertThat(occupazione.inizio()).isEqualTo(LocalDate.of(2026, 9, 3));
            assertThat(occupazione.fine()).isEqualTo(LocalDate.of(2026, 9, 5));
        });
    }

    @Test
    @DisplayName("legge le righe piegate e i valori protetti")
    void occupazioni_righePiegate() {
        // Una riga piegata secondo la specifica: a capo, poi uno spazio. E' la forma
        // normale di qualunque calendario vero, e un lettore scritto a mano e' proprio
        // qui che si rompe
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:un-identificativo-molto-lungo-che-il-canale-piega-a-settantacinque-ca
                 ratteri@booking.com
                SUMMARY:Prenotazione\\; camera doppia\\, non rimborsabile
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260912
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione ->
                assertThat(occupazione.uid())
                        .isEqualTo("un-identificativo-molto-lungo-che-il-canale-piega-a-"
                                + "settantacinque-caratteri@booking.com"));
    }

    @Test
    @DisplayName("senza data di fine dura un giorno, come vuole la specifica")
    void occupazioni_senzaFine_unGiorno() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:senza-fine@booking.com
                DTSTART;VALUE=DATE:20260910
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione ->
                assertThat(occupazione.fine()).isEqualTo(LocalDate.of(2026, 9, 11)));
    }

    @Test
    @DisplayName("con una durata al posto della fine conta le notti")
    void occupazioni_conDurata() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:durata@booking.com
                DTSTART;VALUE=DATE:20260910
                DURATION:P1W2D
                END:VEVENT"""));

        // Una settimana e due giorni: nove notti
        assertThat(occupazioni).singleElement().satisfies(occupazione ->
                assertThat(occupazione.fine()).isEqualTo(LocalDate.of(2026, 9, 19)));
    }

    @Test
    @DisplayName("una durata di sole ore vale comunque una notte")
    void occupazioni_durataDiSoleOre_unaNotte() {
        // Sotto il giorno non si scende: la tabella conta notti, e una camera occupata
        // tre ore quella notte non e' comunque vendibile
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:tre-ore@booking.com
                DTSTART:20260910T140000Z
                DURATION:PT3H
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione ->
                assertThat(occupazione.fine()).isEqualTo(LocalDate.of(2026, 9, 11)));
    }

    @Test
    @DisplayName("un evento annullato non occupa niente")
    void occupazioni_annullato_scartato() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:disdetta@booking.com
                STATUS:CANCELLED
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260912
                END:VEVENT"""));

        assertThat(occupazioni).isEmpty();
    }

    @Test
    @DisplayName("un evento trasparente non occupa niente")
    void occupazioni_trasparente_scartato() {
        // TRANSP:TRANSPARENT vuol dire "questo tempo resta libero": e' cosi' che alcuni
        // canali pubblicano i promemoria dentro lo stesso calendario delle vendite
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:promemoria@booking.com
                TRANSP:TRANSPARENT
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260912
                END:VEVENT"""));

        assertThat(occupazioni).isEmpty();
    }

    @Test
    @DisplayName("un periodo di zero notti non rende invendibile niente")
    void occupazioni_zeroNotti_scartato() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:vuoto@booking.com
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260910
                END:VEVENT"""));

        assertThat(occupazioni).isEmpty();
    }

    @Test
    @DisplayName("un evento senza data di inizio e' una riga rotta, non un'occupazione")
    void occupazioni_senzaInizio_scartato() {
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                UID:rotto@booking.com
                DTEND;VALUE=DATE:20260912
                END:VEVENT"""));

        assertThat(occupazioni).isEmpty();
    }

    @Test
    @DisplayName("senza identificativo l'occupazione entra lo stesso")
    void occupazioni_senzaUid_entrano() {
        // Nessuna decisione dipende dall'UID — la sincronizzazione riscrive i propri
        // blocchi da capo — quindi scartare l'occupazione vorrebbe dire perdere una
        // camera venduta per un campo che serve solo a rileggere il passato
        List<OccupazioneEsterna> occupazioni = LetturaIcs.occupazioni(calendario("""
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260912
                END:VEVENT"""));

        assertThat(occupazioni).singleElement().satisfies(occupazione ->
                assertThat(occupazione.uid()).isNull());
    }

    @Test
    @DisplayName("legge tutti i calendari del file, non solo il primo")
    void occupazioni_piuCalendari_liLeggeTutti() {
        // Fermarsi al primo VCALENDAR vorrebbe dire perdere in silenzio le occupazioni
        // degli altri: e' cosi' che si rivende una camera senza nemmeno un errore
        String due = calendario("""
                BEGIN:VEVENT
                UID:primo@booking.com
                DTSTART;VALUE=DATE:20260910
                DTEND;VALUE=DATE:20260912
                END:VEVENT""")
                + calendario("""
                BEGIN:VEVENT
                UID:secondo@airbnb.com
                DTSTART;VALUE=DATE:20261001
                DTEND;VALUE=DATE:20261003
                END:VEVENT""");

        assertThat(LetturaIcs.occupazioni(due))
                .extracting(OccupazioneEsterna::uid)
                .containsExactly("primo@booking.com", "secondo@airbnb.com");
    }

    @Test
    @DisplayName("un calendario senza eventi e' vuoto, non un errore")
    void occupazioni_calendarioVuoto() {
        // Un albergo che quel mese non ha venduto niente e' un caso normale, e va
        // distinto da un indirizzo che non risponde: qui i blocchi vanno tolti davvero
        assertThat(LetturaIcs.occupazioni(calendario(""))).isEmpty();
    }

    @Test
    @DisplayName("una risposta che non e' un calendario si rifiuta invece di leggerla vuota")
    void occupazioni_nonUnCalendario_solleva() {
        // E' il caso pericoloso: biweekly davanti a una pagina HTML non solleva niente,
        // restituisce zero calendari. Letto come "nessuna occupazione", quel silenzio
        // rimetterebbe in vendita tutte le camere che il canale aveva venduto
        assertThatThrownBy(() -> LetturaIcs.occupazioni("<html><body>404 Not Found</body></html>"))
                .isInstanceOf(IcsIlleggibileException.class)
                .hasMessageContaining("non contiene nessun calendario");
    }

    /** L'involucro attorno a uno o piu' eventi, con le fini riga che vuole la specifica. */
    private static String calendario(String eventi) {
        String corpo = eventi.isBlank() ? "" : eventi + "\n";
        return ("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Un canale qualsiasi//IT
                """ + corpo + "END:VCALENDAR\n").replace("\n", "\r\n");
    }
}
