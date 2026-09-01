-- La tassa di soggiorno: quanto deve il comune, e chi non lo deve.
--
-- E' il secondo dei tre adempimenti italiani della fase 2, e vale per la stessa
-- ragione degli altri: e' obbligatorio, e' noioso, cambia da comune a comune, ed
-- e' il motivo per cui in un tre stelle di provincia i prodotti internazionali
-- non li usa nessuno. Un gestionale che non la calcola lascia l'albergatore a
-- fare il conto su un foglio, e il foglio e' cio' con cui compete davvero.
--
-- **Perche' e' un'entita' e non due colonne di impostazioni_hotel.** Il
-- 2026-08-28 sembrava che bastassero un importo e un'eta'; e' bastato chiedersi
-- chi li cambia (regola 24) per vedere che no. L'importo cambia nel tempo — i
-- comuni la ritoccano, e spesso la stagionalizzano — il tetto di notti e' una
-- terza cosa, e le esenzioni sono un elenco. Un'aliquota valida in un periodo e'
-- il modello piu' piccolo che regge tutto questo senza mentire.

-- ============================================================
-- ALIQUOTA_TASSA_SOGGIORNO
-- ============================================================
--
-- **E' dell'albergo e non della tipologia**, al contrario del periodo tariffario
-- del V9, e la differenza non e' una svista: il prezzo della camera lo decide chi
-- vende, l'aliquota la decide il comune e vale per chiunque dorma in quel comune.
-- Alcuni regolamenti graduano l'importo per categoria della struttura — le
-- stelle — ma questa installazione gestisce **una struttura sola** (vedi il
-- CHECK (id = 1) del V8), quindi quella graduazione qui e' gia' risolta: la
-- categoria e' una sola, e il suo importo e' questo.
--
-- **Gli estremi sono inclusi tutti e due e si misurano in notti**, come nel V9:
-- un'aliquota 1->31 agosto copre le notti dal primo al trentun agosto, cioe'
-- anche chi arriva il 31 e parte il primo settembre. Ripetere quella scelta
-- invece di cambiarla e' cio' che permette di scrivere i due calcoli — prezzo e
-- tassa — con la stessa aritmetica delle notti.
CREATE TABLE aliquota_tassa_soggiorno (
    id                        BIGSERIAL      PRIMARY KEY,
    data_inizio               DATE           NOT NULL,
    data_fine                 DATE           NOT NULL,
    importo_per_persona_notte NUMERIC(10,2)  NOT NULL CHECK (importo_per_persona_notte >= 0),

    -- Il tetto di notti tassate: si paga per le prime N notti del soggiorno e
    -- dalla N+1 in poi no. E' l'esenzione piu' diffusa dopo quella per eta' —
    -- praticamente ogni regolamento comunale ne ha una, di solito fra le tre e
    -- le sette notti — e serve a non tassare chi in albergo ci vive.
    --
    -- **NULL vuol dire nessun tetto**, e non zero: zero direbbe "nessuna notte
    -- si paga", che e' una cosa diversa e che un ADMIN puo' voler dire davvero
    -- scrivendo un importo a zero. Il CHECK pretende almeno una notte proprio
    -- per tenere separate le due frasi.
    notti_massime_tassate     INT            CHECK (notti_massime_tassate >= 1),

    -- Sotto questa eta' non si paga. NULL vuol dire nessuna esenzione per eta',
    -- che esiste: qualche comune fa pagare tutti.
    --
    -- **Non e' la maggiore eta' del V10 e non va confusa con lei.** Quella dice
    -- se serve un documento ed e' 18 per legge, uguale per ogni albergo d'Italia,
    -- quindi sta nel codice; questa la decide il regolamento comunale — dieci
    -- anni, dodici, quattordici — quindi sta qui. Somigliarsi non le rende la
    -- stessa cosa, e legarle vorrebbe dire che cambiare l'una muove l'altra.
    eta_esenzione             INT            CHECK (eta_esenzione >= 0),

    created_at                TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT ck_aliquota_tassa_soggiorno_ordine_date CHECK (data_fine >= data_inizio)
);

-- Due aliquote non possono sovrapporsi, per la stessa ragione per cui non
-- possono farlo due periodi tariffari: "quanto si paga per la notte del 15
-- agosto" deve avere **una** risposta, e senza il vincolo dipenderebbe
-- dall'ordine in cui il planner restituisce le righe. Su un importo che si versa
-- al comune, "dipende da come gira la query" non e' una risposta.
--
-- **Piu' semplice del vincolo del V9**: li' due periodi di tipologie diverse
-- possono coincidere, quindi la colonna della tipologia entrava nel vincolo con
-- WITH =; qui l'aliquota e' una sola per l'albergo, e a non doversi accavallare
-- sono le date e basta. Per la stessa ragione qui `btree_gist` **non servirebbe**
-- — non c'e' nessuna colonna btree da confrontare — ma l'estensione esiste gia'
-- dal V9 e il requisito d'ambiente e' gia' stato pagato.
ALTER TABLE aliquota_tassa_soggiorno
    ADD CONSTRAINT ex_aliquota_tassa_soggiorno_no_sovrapposizioni
    EXCLUDE USING gist (
        daterange(data_inizio, data_fine, '[]') WITH &&
    );

-- ============================================================
-- OSPITE.MOTIVO_ESENZIONE
-- ============================================================
--
-- Le esenzioni sono di due specie, e questa colonna esiste perche' la seconda
-- specie il sistema non la puo' dedurre da niente.
--
--   * quelle che si **calcolano**: l'eta' e il tetto di notti. Discendono da dati
--     che esistono gia' — ospite.data_nascita dal V10, le date della
--     prenotazione dal V1 — quindi chiederle a qualcuno sarebbe chiedergli di
--     ripetere cio' che il sistema sa gia', e permettergli di sbagliarlo.
--   * quelle che si **dichiarano**: residente nel comune, disabile,
--     accompagnatore di un disabile, forze dell'ordine in servizio, autista di
--     bus o guida turistica, ricoverato o suo accompagnatore. Nessuna di queste
--     e' derivabile: sono fatti del mondo che qualcuno al banco constata, quasi
--     sempre guardando un tesserino.
--
-- **Senza questa colonna il calcolo sarebbe sbagliato e non incompleto**, ed e'
-- la ragione per cui sta nello stesso branch invece che in uno successivo: il
-- primo residente che dorme in albergo obbligherebbe a correggere l'importo a
-- mano, cioe' a non fidarsi piu' del numero. Un totale di cui ci si fida a meta'
-- non serve a niente.
--
-- Nessun CHECK con l'elenco dei valori, ed e' la stessa scelta gia' fatta per
-- tipo_documento: l'elenco dei motivi lo decide un regolamento comunale, cioe'
-- il mondo fuori, e aggiungerne uno dev'essere una riga di enum e non una
-- migration. Lo stato di una prenotazione, che invece lo scrive l'applicazione,
-- il suo CHECK ce l'ha dal V1.
--
-- NULL e' il caso normale: quasi nessuno ha un'esenzione dichiarata.
ALTER TABLE ospite ADD COLUMN motivo_esenzione VARCHAR(40);
