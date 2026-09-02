package com.felixhotel.backend.service;

import com.felixhotel.backend.service.impl.CalendarioIcs;
import com.felixhotel.backend.service.impl.DistribuzioneOccupazione.Periodo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il formato iCalendar, nella sola forma che questo progetto produce.
 *
 * <p>Stessa ragione per cui il tracciato delle schedine ha un test suo: un formato che
 * qualcun altro deve leggere si rompe in silenzio. La differenza e' che qui il
 * destinatario non lo si puo' interrogare — Booking non ha un endpoint che dica "questo
 * file va bene" — quindi l'unica rete e' guardare il testo prodotto.
 */
@DisplayName("CalendarioIcs")
class CalendarioIcsTest {

    private static final LocalDate GENERATO = LocalDate.of(2026, 9, 1);

    @Test
    @DisplayName("ha l'involucro che la specifica pretende")
    void calendario_haInvolucroValido() {
        String ics = CalendarioIcs.calendario("Camera 12", List.of(), GENERATO);

        assertThat(ics)
                .startsWith("BEGIN:VCALENDAR\r\n")
                .contains("VERSION:2.0")
                .contains("PRODID:")
                .endsWith("END:VCALENDAR\r\n");
    }

    @Test
    @DisplayName("un periodo diventa un evento di sole date, con la fine esclusa")
    void calendario_conUnPeriodo_scriveUnEvento() {
        String ics = CalendarioIcs.calendario("Camera 12",
                List.of(new Periodo(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13))),
                GENERATO);

        // VALUE=DATE e non un orario: un soggiorno occupa notti, e con gli orari
        // avremmo dovuto scegliere un fuso che ogni consumatore avrebbe interpretato
        // a modo suo
        assertThat(ics)
                .contains("DTSTART;VALUE=DATE:20260910")
                .contains("DTEND;VALUE=DATE:20260913")
                .contains("SUMMARY:Camera 12")
                // OPAQUE e' il campo su cui un canale decide se la camera sia vendibile:
                // ometterlo lascerebbe la decisione ai valori predefiniti di chi legge
                .contains("TRANSP:OPAQUE");
    }

    @Test
    @DisplayName("gli identificativi degli eventi non cambiano fra due generazioni")
    void calendario_generatoDueVolte_stessiIdentificativi() {
        List<Periodo> periodi = List.of(
                new Periodo(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)));

        String primo = CalendarioIcs.calendario("Camera 12", periodi, GENERATO);
        String secondo = CalendarioIcs.calendario("Camera 12", periodi, GENERATO.plusDays(5));

        // then: gli UID si costruiscono dalle date e non dal momento in cui si genera.
        // Con un UID casuale ogni lettura sembrerebbe un evento nuovo, e un canale
        // continuerebbe a cancellare e ricreare la stessa occupazione
        String uidPrimo = primo.lines().filter(r -> r.startsWith("UID:")).findFirst().orElseThrow();
        String uidSecondo = secondo.lines().filter(r -> r.startsWith("UID:")).findFirst().orElseThrow();
        assertThat(uidPrimo).isEqualTo(uidSecondo);
    }

    @Test
    @DisplayName("i caratteri speciali del titolo non spezzano il file")
    void calendario_conTitoloSpeciale_lofugge() {
        String ics = CalendarioIcs.calendario("Camera 12; suite, lato mare", List.of(
                new Periodo(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))), GENERATO);

        // then: in iCal il punto e virgola separa i parametri e la virgola separa i
        // valori. Un numero di camera cosi' e' improbabile e non impossibile, e senza
        // la fuga produrrebbe un file che nessuno riesce piu' a leggere
        assertThat(ics).contains("SUMMARY:Camera 12\\; suite\\, lato mare");
    }

    @Test
    @DisplayName("piu' periodi diventano piu' eventi")
    void calendario_conPiuPeriodi_scrivePiuEventi() {
        String ics = CalendarioIcs.calendario("Camera 12", List.of(
                new Periodo(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)),
                new Periodo(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22))), GENERATO);

        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(3);
    }

    @Test
    @DisplayName("ogni riga finisce con CRLF, come vuole la specifica")
    void calendario_terminaLeRigheConCrlf() {
        String ics = CalendarioIcs.calendario("Camera 12", List.of(
                new Periodo(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))), GENERATO);

        // then: nessun a capo isolato. Scritto col separatore di sistema, il file
        // sarebbe diverso a seconda di dove gira il backend
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }
}
