-- I token che viaggiano per email: verifica dell'indirizzo, invito del personale,
-- reset della password.
--
-- **Una tabella sola per tre usi**, ed e' la stessa domanda gia' risolta dal V12 con
-- le codifiche: un discriminatore di tipo su una tabella di solito e' un odore. Qui
-- non lo e' per la stessa ragione — non sono tre cose diverse ma **tre usi della
-- stessa cosa**: un segreto casuale, mandato a un indirizzo, che scade e che vale una
-- volta sola. Nascono insieme, si consumano allo stesso modo e si ripuliscono con la
-- stessa DELETE. A distinguerli sono la scadenza e cosa succede quando li si consuma,
-- che sono decisioni del codice e non colonne.
--
-- Il criterio per spacchettarle, per chi un giorno ci pensasse: si spacchetta quando
-- uno dei tre comincia ad avere **campi suoi**. Finche' la domanda che si fa a tutti e
-- tre e' "questo segreto e' valido, e per chi?", la tabella e' una.

CREATE TABLE token_email (
    id           BIGSERIAL     PRIMARY KEY,

    -- A cosa serve questo token. Il CHECK ricalca l'enum come per `voce_codifica.tipo`
    -- e per lo stesso criterio: **questo elenco lo scrive l'applicazione**, quindi un
    -- valore fuori elenco sarebbe un difetto nostro e non una novita' del mondo.
    tipo         VARCHAR(30)   NOT NULL
                 CHECK (tipo IN ('VERIFICA_EMAIL', 'INVITO_STAFF', 'RESET_PASSWORD')),

    -- Di chi e' questo token: in quale tabella vive il suo destinatario.
    --
    -- **Due colonne e nessuna chiave esterna**, ed e' la stessa forma che
    -- `AppUserPrincipal` usa dal 2026-08-27 per rispondere alla domanda "chi sta
    -- chiamando": i clienti stanno in `utente`, il personale in `staff`, due sequenze
    -- indipendenti, quindi **l'id da solo non identifica nessuno**. Una FK qui non e'
    -- scrivibile nemmeno volendo, perche' punterebbe a una tabella o all'altra a
    -- seconda della riga.
    --
    -- La conseguenza va saputa: cancellare un account **non** porta via i suoi token,
    -- come farebbe un ON DELETE CASCADE. Non fa danno — un token orfano non apre
    -- niente, perche' chi lo consuma cerca prima il destinatario e non lo trova — e la
    -- pulizia periodica li porta via comunque quando scadono.
    tipo_account VARCHAR(20)   NOT NULL CHECK (tipo_account IN ('CLIENTE', 'PERSONALE')),
    soggetto_id  BIGINT        NOT NULL,

    -- **L'impronta del token, non il token.** E' la decisione di sicurezza di questa
    -- tabella, e la ragione e' che un token di reset **e' una credenziale**: chi lo
    -- legge prende l'account senza sapere la password. In chiaro, una lettura del
    -- database — un backup finito nel posto sbagliato, una SELECT di troppo — sarebbe
    -- il controllo di ogni account con un reset in corso. Salvando l'impronta, quel
    -- che si legge qui non serve a niente.
    --
    -- **SHA-256 e non BCrypt**, al contrario delle password, e non e' una scorciatoia:
    -- il costo di BCrypt esiste per rallentare chi prova a indovinare un segreto
    -- *scelto da una persona*, cioe' con poca entropia. Questo segreto lo sceglie
    -- `SecureRandom` e ha 256 bit: non lo si indovina, quindi rallentare i tentativi
    -- non difende da niente e in cambio renderebbe la verifica lenta proprio dove
    -- serve essere rapidi. Sessantaquattro caratteri sono l'esadecimale di SHA-256.
    token_hash   VARCHAR(64)   NOT NULL,

    -- Quando smette di valere. **Non e' configurabile** (regola 24): quanto debba
    -- durare un link di conferma non e' qualcosa che due alberghi vogliano diverso, e
    -- le tre durate stanno nel codice, ognuna col suo perche'.
    scadenza     TIMESTAMP     NOT NULL,

    -- Quando e' stato usato, oppure NULL se non lo e' ancora stato.
    --
    -- **Un timestamp e non un booleano**, e la differenza si vede il giorno in cui
    -- qualcuno chiede *"quando e' stato attivato questo account?"*: un booleano
    -- risponde "si'", questa colonna risponde con la data. Costa uguale.
    --
    -- **Il token si consuma e non si cancella**, ed e' l'altra faccia: un secondo clic
    -- sullo stesso link deve poter rispondere *"questo link e' gia' stato usato"*, che
    -- e' un'informazione, invece di *"questo link non esiste"*, che manda chi legge a
    -- cercare il problema dalla parte sbagliata. Cancellandolo subito i due casi
    -- diventerebbero indistinguibili.
    usato_il     TIMESTAMP,

    created_at   TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP     NOT NULL DEFAULT now()
);

-- Due token non possono avere la stessa impronta, ed e' insieme un vincolo di
-- correttezza e la query piu' importante della tabella: chi arriva con un link in mano
-- cerca **esattamente per impronta**, e questo indice glielo serve per intero.
CREATE UNIQUE INDEX uq_token_email_hash ON token_email (token_hash);

-- L'altra lettura e' "questo account ha gia' un token di questo tipo in corso?", che
-- serve a invalidare i precedenti quando se ne emette uno nuovo — chi chiede due volte
-- il reset deve poter usare l'ultimo link ricevuto, non ritrovarsi due link validi.
CREATE INDEX idx_token_email_soggetto ON token_email (tipo, tipo_account, soggetto_id);

-- ============================================================
-- STAFF SENZA PASSWORD
-- ============================================================
--
-- **Un account del personale invitato non ha ancora una password**, e fino al
-- 2026-09-02 non poteva esistere: la colonna era NOT NULL perche' la password la
-- sceglieva l'ADMIN al momento di creare l'account e la comunicava a voce.
--
-- Quella scelta era dichiaratamente un ripiego — l'invito per email era stato scartato
-- il 2026-08-27 **solo** perche' non c'era modo di spedire — e oggi quel motivo non
-- c'e' piu'. Da qui in avanti l'ADMIN crea l'account e parte un invito; la password la
-- sceglie la persona, e fino ad allora questa colonna e' NULL.
--
-- **NULL vuol dire "non puo' autenticarsi"**, e non e' un caso da gestire con
-- attenzione: e' il caso normale fra l'invito e la sua accettazione. Chi valuta il
-- login lo tratta come un account non abilitato, esattamente come uno disattivato —
-- vedi CustomUserDetailsService.
--
-- **Le righe che ci sono gia' non cambiano**: togliere un NOT NULL non tocca nessun
-- dato, e gli account creati prima di oggi tengono la loro password. Nessuna migration
-- puo' inventarne una, e nessuna deve provarci.
ALTER TABLE staff ALTER COLUMN password_hash DROP NOT NULL;
