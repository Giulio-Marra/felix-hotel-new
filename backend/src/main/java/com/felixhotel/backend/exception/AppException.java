package com.felixhotel.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Radice di tutte le eccezioni applicative del progetto: un errore che il
 * codice solleva di proposito, sapendo gia' com'e' andata (email duplicata,
 * risorsa inesistente, input rifiutato da una regola di business).
 *
 * <p>Porta con se' lo status con cui va risposto, in linea con la
 * convenzione di progetto per cui e' il Service a decidere status e
 * messaggio. Il vantaggio rispetto a {@code ResponseStatusException} di
 * Spring e' che i Service non dipendono piu' da una classe del layer web:
 * lanciano un'eccezione di casa, e' il {@link GlobalExceptionHandler} a
 * tradurla in risposta HTTP con un unico {@code @ExceptionHandler}.
 *
 * <p>Astratta di proposito: non si lancia {@code AppException} direttamente,
 * si sceglie la sottoclasse che descrive il tipo di errore.
 *
 * <p>Nessuno stacktrace: queste eccezioni sono esiti previsti del flusso
 * normale, non guasti, e riempire i log di stacktrace per un login sbagliato
 * costa e non serve. Chi vuole il contesto completo puo' usare il
 * costruttore con {@code cause}, che riattiva stacktrace e soppressione.
 */
@Getter
public abstract class AppException extends RuntimeException {

    /** Status HTTP con cui il GlobalExceptionHandler rispondera'. */
    private final HttpStatus status;

    protected AppException(HttpStatus status, String message) {
        // writableStackTrace = false: vedi nota sopra.
        super(message, null, false, false);
        this.status = status;
    }

    /**
     * Variante da usare quando l'errore nasce da un'altra eccezione che vale
     * la pena conservare nei log (es. una libreria esterna che fallisce).
     */
    protected AppException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
