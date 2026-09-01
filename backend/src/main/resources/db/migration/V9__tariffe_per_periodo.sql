-- I prezzi come li intende un albergo: diversi a Ferragosto e in novembre,
-- diversi il sabato e il martedi', con un numero minimo di notti che in alta
-- stagione non si scende.
--
-- Fino a qui il prezzo era una colonna sola — tipologia_camera.prezzo_notte —
-- e valeva per sempre. Non e' un semplificazione da poco: e' il buco che si
-- vede da fuori, perche' un albergo che vende la doppia a 120 euro a Ferragosto
-- come a novembre non esiste. E' il primo pezzo della fase 2 e viene prima
-- degli altri due (tassa di soggiorno, Alloggiati) perche' tocca la ricerca, la
-- creazione della prenotazione e il calcolo dell'importo: ogni riga scritta
-- prima su un prezzo fisso sarebbe una riga da riscrivere dopo.
--
-- **La colonna vecchia resta, ed e' una decisione.** tipologia_camera.prezzo_notte
-- non sparisce e non diventa storia: diventa il **prezzo di listino**, quello che
-- vale per ogni notte che nessun periodo copre. Le alternative erano due, e sono
-- state entrambe guardate prima di scegliere:
--   * togliere la colonna e pretendere che i periodi coprano ogni data
--     prenotabile. Un solo posto dove sta il prezzo, che e' meglio; ma vuol dire
--     una migration che inventa un periodo "da sempre a sempre" per ogni
--     tipologia, e soprattutto vuol dire che un albergo che si dimentica di
--     configurare il 2027 **smette di vendere** il primo gennaio;
--   * lasciarla come valore morto, letto da nessuno. Sarebbe stata una colonna
--     che mente: presente nel catalogo pubblico e senza nessun effetto sul conto.
-- Il listino di base non ha nessuno dei due difetti — nessun buco possibile,
-- nessuna migration sui dati, e un albergo che non configura niente continua a
-- funzionare esattamente come il giorno prima. Il prezzo e' che per sapere
-- quanto costa una notte bisogna guardare in due posti, ed e' scritto nel
-- javadoc di TipologiaCamera#prezzoNotte perche' non lo si scopra per caso.

-- ============================================================
-- PERIODO_TARIFFARIO
-- ============================================================
--
-- Il periodo e' **della tipologia**, non dell'albergo. In un albergo vero
-- "alta stagione" e' una cosa sola con dentro i prezzi di ogni tipologia, e
-- quel modello — una tabella `stagione` piu' una `tariffa` che la incrocia con
-- la tipologia — sarebbe piu' fedele: spostare l'alta stagione di una settimana
-- si farebbe in un posto solo invece che su ogni tipologia. E' stato scartato
-- il 2026-09-01 sapendo cosa costa: due entita', due CRUD, un join, e una
-- domanda in piu' a cui rispondere ("una tipologia senza tariffa per quella
-- stagione, quanto costa?"). Con tre o quattro tipologie la ripetizione delle
-- date e' un fastidio dell'ADMIN; la seconda entita' sarebbe un peso del codice
-- per sempre. Se un giorno le tipologie diventassero venti, questa e' la
-- decisione da riaprire per prima.
CREATE TABLE periodo_tariffario (
    id                   BIGSERIAL      PRIMARY KEY,
    tipologia_camera_id  BIGINT         NOT NULL REFERENCES tipologia_camera(id) ON DELETE CASCADE,
    nome                 VARCHAR(100)   NOT NULL,
    data_inizio          DATE           NOT NULL,
    data_fine            DATE           NOT NULL,
    prezzo_notte         NUMERIC(10,2)  NOT NULL CHECK (prezzo_notte >= 0),
    soggiorno_minimo     INT            NOT NULL DEFAULT 1 CHECK (soggiorno_minimo >= 1),
    created_at           TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP      NOT NULL DEFAULT now(),

    -- Gli estremi sono **inclusi tutti e due**, e il periodo si misura in
    -- notti: un periodo 1->1 agosto e' la notte del primo agosto, cioe' chi
    -- arriva il primo e parte il due. E' la stessa aritmetica con cui il giorno
    -- di partenza non e' occupato — un soggiorno 10->13 sono le notti 10, 11 e
    -- 12 — e per questo un periodo di un giorno solo e' legittimo e non un
    -- errore da vietare.
    CONSTRAINT ck_periodo_tariffario_ordine_date CHECK (data_fine >= data_inizio)
);

CREATE INDEX idx_periodo_tariffario_tipologia ON periodo_tariffario(tipologia_camera_id);

-- Due periodi della stessa tipologia non possono sovrapporsi, **e lo dice il
-- database**.
--
-- Senza questo vincolo la domanda "quanto costa la notte del 15 agosto" puo'
-- avere due risposte, e non esiste nessun modo non arbitrario di sceglierne
-- una: sarebbe un prezzo che dipende dall'ordine in cui il planner restituisce
-- le righe. Le due alternative erano dare una priorita' ai periodi (piu'
-- potente, ma un campo in piu' da spiegare e un modo in piu' di configurarsi
-- male) oppure lasciar decidere l'ultimo inserito (cioe' un invariante affidato
-- alla fortuna). Vietare la sovrapposizione e' la regola che si spiega in una
-- frase a chi la usa: **un giorno, un prezzo.**
--
-- Il Service controlla la stessa cosa prima di arrivare qui, e risponde 409 con
-- un messaggio che dice con quale periodo si accavalla: quello e' la cortesia,
-- questo e' la garanzia — l'unica che regge anche quando due richieste arrivano
-- nello stesso istante. E' lo stesso rapporto che c'e' fra gli existsBy dei
-- repository e gli indici unici del V2, V3, V4 e V6.
--
-- **Serve btree_gist**, e va saputo prima della messa in esercizio: un vincolo
-- di esclusione sa confrontare gli intervalli (l'operatore &&) ma non sa
-- confrontare un bigint con =, che e' un operatore btree. L'estensione e' nel
-- contrib standard — c'e' nell'immagine ufficiale, su RDS e su ogni
-- distribuzione mainstream — ma CREATE EXTENSION vuole privilegi che l'utente
-- applicativo in produzione potrebbe non avere. In quel caso l'estensione va
-- creata a mano dal DBA **prima** di far girare Flyway. E' il primo pezzo di
-- schema del progetto che pone una condizione all'ambiente, e sta scritto qui
-- perche' e' qui che si scopre.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE periodo_tariffario
    ADD CONSTRAINT ex_periodo_tariffario_no_sovrapposizioni
    EXCLUDE USING gist (
        tipologia_camera_id WITH =,
        daterange(data_inizio, data_fine, '[]') WITH &&
    );

-- ============================================================
-- PREZZO_GIORNO_SETTIMANA
-- ============================================================
--
-- Dentro un periodo, il prezzo di un giorno della settimana preciso. Zero righe
-- vuol dire che tutte le notti del periodo costano `prezzo_notte`; una riga per
-- SATURDAY vuol dire che il sabato costa quello che dice lei e gli altri giorni
-- no.
--
-- **Sono scavalcamenti, non un listino completo**: non c'e' nessun obbligo di
-- riempirle tutte e sette, e il caso normale sono una o due righe. E' cio' che
-- distingue questa forma dalle due colonne `prezzo_feriale` / `prezzo_weekend`
-- che erano l'alternativa ovvia: quelle sono meno righe di SQL, ma pretendono
-- che "quali giorni sono weekend" stia nel codice, e due alberghi lo vogliono
-- diverso — un rifugio di montagna ha il picco il venerdi' e il sabato, un
-- hotel di citta' che vive di lavoro ce l'ha dal lunedi' al giovedi'. Per la
-- regola 24 cio' che due alberghi vogliono diverso non sta nel codice, e
-- infatti qui l'albergatore scrive quali giorni sono cari senza che nessuno
-- glielo abbia chiesto in anticipo.
--
-- Il giorno e' il nome dell'enum java.time.DayOfWeek (MONDAY..SUNDAY), come
-- ogni altro enum del progetto e per la stessa ragione: l'ordinale legherebbe
-- il significato delle righe all'ordine in cui i valori stanno scritti. Il
-- CHECK elenca i sette valori come il V1 fa per lo stato della camera — e'
-- l'unico modo che il database ha di rifiutare 'LUNEDI' scritto da una INSERT
-- a mano.
CREATE TABLE prezzo_giorno_settimana (
    id                     BIGSERIAL      PRIMARY KEY,
    periodo_tariffario_id  BIGINT         NOT NULL REFERENCES periodo_tariffario(id) ON DELETE CASCADE,
    giorno                 VARCHAR(10)    NOT NULL
        CHECK (giorno IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    prezzo                 NUMERIC(10,2)  NOT NULL CHECK (prezzo >= 0),
    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now()
);

-- Lo stesso giorno due volte nello stesso periodo e' la stessa ambiguita' della
-- sovrapposizione, in piccolo: due prezzi per il sabato di agosto, e nessun
-- criterio per scegliere. La PUT sul periodo riscrive l'insieme intero, quindi
-- il doppione non nasce dall'uso normale — nasce da una INSERT a mano o da un
-- bug, che sono esattamente i due casi in cui si vuole che il database si
-- rifiuti invece di lasciar passare.
CREATE UNIQUE INDEX uq_prezzo_giorno_settimana_periodo_giorno
    ON prezzo_giorno_settimana (periodo_tariffario_id, giorno);
