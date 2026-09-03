-- I calendari altrui che leggiamo, e il legame fra un blocco e la sorgente che lo ha
-- scritto.
--
-- **E' la meta' mancante del punto 25.** Il V16 ha pubblicato un feed per camera, cosi'
-- che Booking sappia quando quella stanza e' occupata da noi; questa tabella fa il giro
-- opposto — quando Booking la vende, la stanza da noi diventa invendibile. Senza il
-- ritorno, la sincronia funziona in una direzione sola e l'albergo rivende quel che ha
-- gia' venduto.
--
-- **Una sorgente sta su una camera, non su una tipologia**, per la stessa ragione per cui
-- ci sta il feed del V16 e non e' una simmetria estetica: iCal non sa esprimere le
-- quantita', quindi ogni calendario — il nostro come quello del canale — corrisponde a
-- **una unita' vendibile**. Su Booking e su Airbnb l'indirizzo iCal si copia dalla scheda
-- di una camera, e quella scheda e' la camera che gli abbiamo pubblicato noi.
--
-- **Conseguenza diretta, e va detta perche' cambia una previsione del V15**: i blocchi
-- che questa sincronia scrive **nominano la camera**. Il V15 dava per scontato che
-- fossero anonimi ("Booking dice che *una* doppia e' venduta e quale sia non lo dice
-- nessuno"), ed era vero finche' non si sapeva su quale scheda quel calendario stesse.
-- Ora si sa, perche' la sorgente e' agganciata a una camera. In cambio il check-in non
-- assegna piu' la stanza che il canale ha venduto — che e' l'unica cosa che nominare una
-- camera serve a fare.
CREATE TABLE sorgente_calendario (
    id                      BIGSERIAL    PRIMARY KEY,

    -- La camera di cui questo calendario racconta l'occupazione. CASCADE perche' una
    -- sorgente senza la sua camera non vuol dire niente.
    camera_id               BIGINT       NOT NULL REFERENCES camera(id) ON DELETE CASCADE,

    -- Come si chiama il canale, per riconoscerlo in un elenco: "Booking", "Airbnb". E'
    -- testo libero e non un elenco chiuso, ed e' la regola 24: quali canali esistano
    -- cambia da albergo ad albergo e nessun codice qui dentro li guarda.
    nome                    VARCHAR(100) NOT NULL,

    -- L'indirizzo da cui si scarica. Non e' un segreto **nostro** ma lo e' di chi ce lo
    -- ha dato: chi ha questo URL legge l'occupazione di quella camera su quel canale.
    --
    -- 500 caratteri e non 1000 per una ragione concreta: entra in un indice unico
    -- insieme a camera_id, e una chiave btree non puo' superare circa 2700 byte. Gli
    -- indirizzi veri di Booking e Airbnb stanno abbondantemente sotto i 200.
    url                     VARCHAR(500) NOT NULL,

    -- **L'esito dell'ultimo giro, non un registro dei giri passati**, ed e' una decisione
    -- e non una scorciatoia: un conflitto e' uno **stato**, non un evento. Finche' il
    -- blocco importato e la prenotazione convivono, la sincronizzazione successiva lo
    -- ritrova e lo riscrive qui; quando il conflitto viene risolto, sparisce da solo.
    -- Una tabella di storico direbbe le stesse cose in piu' righe e chiederebbe a
    -- qualcuno di ripulirla.
    --
    -- NULL finche' non e' mai stata sincronizzata: e' l'unico modo di distinguere "non
    -- e' ancora partita" da "e' andata bene".
    ultima_sincronizzazione TIMESTAMP,
    ultimo_esito            VARCHAR(20)  CHECK (ultimo_esito IN ('OK', 'CONFLITTI', 'ERRORE')),

    -- Cosa e' andato storto, in parole: l'errore di rete, oppure i conflitti trovati.
    -- Facoltativo perche' un giro andato bene non ha niente da dire.
    ultimo_messaggio        VARCHAR(1000),

    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now()
);

-- Lo stesso indirizzo due volte sulla stessa camera vorrebbe dire importare due volte la
-- stessa occupazione, cioe' **togliere due unita' mentre la stanza e' una**. E' lo stesso
-- danno che il vincolo di esclusione del V15 evita fra due blocchi, preso un passo prima.
CREATE UNIQUE INDEX uq_sorgente_camera_url ON sorgente_calendario (camera_id, url);

-- L'elenco del backoffice si legge per camera, ed e' anche la domanda che la
-- sincronizzazione fa per prima.
CREATE INDEX idx_sorgente_camera ON sorgente_calendario (camera_id);

-- Da quale sorgente arriva un blocco importato.
--
-- **Serve a rifare i propri blocchi senza toccare quelli degli altri.** Ogni giro
-- cancella cio' che quella sorgente aveva scritto l'ultima volta e riscrive cio' che il
-- calendario dice adesso; senza questa colonna, "i propri" si potrebbe dedurre solo
-- dall'origine, e allora il giro di Booking porterebbe via i blocchi di Airbnb sulla
-- stessa camera. Il riferimento_esterno del V15 non basta: un UID e' unico dentro un
-- calendario, non fra calendari diversi.
--
-- CASCADE: togliere una sorgente toglie l'occupazione che raccontava. E' la risposta
-- giusta — smettere di leggere un canale vuol dire che quel canale non ci dice piu'
-- niente, non che l'ultima cosa detta resta vera per sempre.
ALTER TABLE blocco_disponibilita
    ADD COLUMN sorgente_calendario_id BIGINT REFERENCES sorgente_calendario(id) ON DELETE CASCADE;

-- Le due colonne dicono la stessa cosa da due lati e non possono contraddirsi: un blocco
-- di un canale ha una sorgente, uno manuale non ce l'ha. Senza questo vincolo esisterebbe
-- un CANALE_ESTERNO orfano — che nessuna sincronizzazione rifarebbe mai e nessuna persona
-- si sentirebbe in diritto di cancellare.
ALTER TABLE blocco_disponibilita
    ADD CONSTRAINT ck_blocco_sorgente_coerente_con_origine
        CHECK ((origine = 'CANALE_ESTERNO') = (sorgente_calendario_id IS NOT NULL));

-- La domanda del giro di sincronizzazione e' "quali blocchi ha scritto questa sorgente",
-- e senza indice scorrerebbe tutta la tabella ad ogni quarto d'ora. Parziale perche' i
-- blocchi manuali a questa domanda non rispondono mai, ed e' la stessa forma di
-- idx_blocco_camera_periodo.
CREATE INDEX idx_blocco_sorgente
    ON blocco_disponibilita (sorgente_calendario_id)
    WHERE sorgente_calendario_id IS NOT NULL;
