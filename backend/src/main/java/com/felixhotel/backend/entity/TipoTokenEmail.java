package com.felixhotel.backend.entity;

import java.time.Duration;

/**
 * A cosa serve un {@link TokenEmail}.
 *
 * <p><b>Tre usi della stessa cosa</b>, ed e' il motivo per cui la tabella e' una sola:
 * un segreto casuale mandato a un indirizzo, che scade e che vale una volta sola. Il
 * perche' esteso, e la condizione che li farebbe spacchettare, stanno nel commento del
 * V14__token_email.sql.
 *
 * <p><b>Ogni valore si porta dietro la propria durata</b>, e non e' una comodita': le
 * tre durate sono diverse e la ragione di ognuna e' scritta accanto al valore. Tenerle
 * qui invece che in chi crea il token e' cio' che impedisce a due punti del codice di
 * emettere lo stesso tipo di token con due scadenze diverse.
 *
 * <p><b>Non sono configurabili</b> (regola 24): quanto debba durare un link di conferma
 * non e' qualcosa che due alberghi vorrebbero diverso, e un albergatore che potesse
 * allungare la validita' di un reset a un mese si starebbe solo aprendo una finestra
 * piu' larga senza guadagnarci niente.
 */
public enum TipoTokenEmail {

    /**
     * Conferma che l'indirizzo dato in registrazione esiste ed e' di chi si e'
     * registrato. Finche' non viene consumato, l'account non si autentica.
     *
     * <p><b>Ventiquattro ore</b>: e' lungo abbastanza da coprire chi si registra la
     * sera e apre la posta il giorno dopo, e corto abbastanza che un indirizzo
     * sbagliato non lasci in giro un link valido per settimane. Chi lo lascia scadere
     * non e' bloccato: se ne fa mandare un altro.
     */
    VERIFICA_EMAIL(Duration.ofHours(24)),

    /**
     * L'invito con cui un membro del personale sceglie la propria password la prima
     * volta. Prende il posto della password che fino al 2026-09-02 sceglieva l'ADMIN e
     * comunicava a voce.
     *
     * <p><b>Sette giorni</b>, che e' la durata piu' lunga delle tre, e il motivo e' che
     * qui non si sta confermando un indirizzo ma **facendo entrare una persona al
     * lavoro**: un invito mandato il venerdi' a chi comincia il lunedi' deve essere
     * ancora valido, e chi va in ferie fra l'assunzione e il primo giorno non deve
     * dover chiedere niente a nessuno.
     */
    INVITO_STAFF(Duration.ofDays(7)),

    /**
     * Il reset della password per chi non riesce piu' a entrare.
     *
     * <p><b>Un'ora</b>, la piu' corta delle tre, ed e' l'unica di queste durate che sia
     * davvero una scelta di sicurezza: e' il token che fa piu' danno se qualcuno lo
     * intercetta, perche' cambia una credenziale invece di confermarne una. Chi lo
     * chiede lo sta aspettando in quel momento, quindi un'ora non toglie niente a
     * nessuno.
     */
    RESET_PASSWORD(Duration.ofHours(1));

    private final Duration durata;

    TipoTokenEmail(Duration durata) {
        this.durata = durata;
    }

    /** Quanto vale un token di questo tipo dal momento in cui nasce. */
    public Duration durata() {
        return durata;
    }
}
