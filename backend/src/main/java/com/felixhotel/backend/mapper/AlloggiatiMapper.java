package com.felixhotel.backend.mapper;

import com.felixhotel.backend.dto.SchedineAlloggiatiResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Costruisce la risposta dell'export delle schedine. Scritta a mano per scelta di
 * progetto (niente MapStruct).
 *
 * <p><b>Non converte un'entita'</b>, ed e' l'unico mapper del progetto che non lo
 * faccia: le schedine non sono righe di una tabella, sono una vista del registro
 * degli ospiti. Sta qui lo stesso per la regola 11 — nei Service non si assembla
 * nessun DTO — e perche' l'unica cosa che aggiunge, il nome del file, e' una
 * decisione di presentazione e non di dominio.
 */
@Component
public class AlloggiatiMapper {

    /**
     * Il pezzo variabile del nome del file: solo cifre, senza trattini.
     *
     * <p>Un nome con le barre o i due punti non e' salvabile su tutti i sistemi, e i
     * trattini si tolgono perche' cosi' i file di giorni diversi si ordinano
     * alfabeticamente nell'ordine giusto in qualunque cartella.
     */
    private static final DateTimeFormatter GIORNO = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * @param data       il giorno di arrivo esportato
     * @param schedine   quante righe ha il file; zero e' legittimo
     * @param contenuto  il file per intero, terminatori compresi
     */
    public SchedineAlloggiatiResponse toResponse(LocalDate data, int schedine, String contenuto) {
        return new SchedineAlloggiatiResponse()
                .data(data)
                .numeroSchedine(schedine)
                .nomeFile("schedine_" + data.format(GIORNO) + ".txt")
                .contenuto(contenuto);
    }
}
