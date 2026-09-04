-- I pagamenti: chi ha versato quanto, e quanto manca ancora.
--
-- E' il punto 24 del piano, l'ultima voce della fase 3, e si fa in due branch: qui il
-- dominio — la caparra, l'incasso registrato al banco, il conto di quel che resta —
-- e in `feature/stripe` il fornitore che incassa online.
--
-- **Perche' una tabella e non due colonne sulla prenotazione.** Sembrava che bastassero
-- `caparra_versata` e `saldato_il`, ed e' bastato chiedersi cosa succede al secondo
-- versamento per vedere che no: un soggiorno si paga a rate — caparra ora, saldo
-- all'arrivo, a volte un acconto in mezzo — e ogni versamento ha un suo importo, un suo
-- momento, un suo metodo e un suo riferimento da riconciliare. Due colonne reggono il
-- primo pagamento e mentono dal secondo in poi.
--
-- **Quello che questa tabella NON ha, e perche'.** Nessuna colonna dice se un versamento
-- sia "la caparra" o "il saldo": e' un'etichetta che si ricava — la caparra dovuta e' una
-- percentuale dell'importo totale, e il residuo e' una sottrazione — e scriverla vorrebbe
-- dire una verita' in due posti, con la seconda libera di divergere dalla prima. Lo stesso
-- vale per uno stato del pagamento sulla prenotazione: si calcola sommando, e una somma
-- non va mai fuori sincrono con i suoi addendi.

-- ============================================================
-- PERCENTUALE_CAPARRA (su impostazioni_hotel)
-- ============================================================
--
-- **Quanto si chiede in anticipo, in percentuale sull'importo totale.** Sta qui per la
-- regola 24: due alberghi la vogliono diversa — c'e' chi chiede il 30%, chi la prima
-- notte, chi niente — quindi la decide l'albergatore e non il codice.
--
-- **Gli estremi sono due frasi utili e non due casi limite da tollerare**: `0` vuol dire
-- "nessuna caparra, si paga tutto in struttura", che e' come lavora un albergo che prende
-- solo prenotazioni dirette; `100` vuol dire "si paga tutto alla conferma", che e' la
-- politica delle tariffe non rimborsabili. Coprirli con lo stesso campo evita due
-- interruttori in piu' che direbbero la stessa cosa.
--
-- **Il default e' 0 e non e' una scelta pigra**: e' l'unico valore che non cambia il
-- comportamento di un'installazione che esiste gia'. Una migration che mettesse 30
-- deciderebbe la politica commerciale di chi ha gia' il gestionale in funzione, dal
-- momento in cui aggiorna e senza dirglielo.
ALTER TABLE impostazioni_hotel
    ADD COLUMN percentuale_caparra NUMERIC(5,2) NOT NULL DEFAULT 0
        CHECK (percentuale_caparra >= 0 AND percentuale_caparra <= 100);

-- ============================================================
-- PAGAMENTO
-- ============================================================
CREATE TABLE pagamento (
    id                     BIGSERIAL      PRIMARY KEY,

    -- Il soggiorno che si sta pagando. **ON DELETE CASCADE** come i blocchi e gli
    -- ospiti: una prenotazione non si cancella (si annulla, ed e' uno stato), quindi il
    -- caso non si presenta per via dell'applicazione — ma se un giorno una riga sparisse
    -- davvero, dei pagamenti orfani sarebbero peggio di nessun pagamento.
    prenotazione_id        BIGINT         NOT NULL REFERENCES prenotazione(id) ON DELETE CASCADE,

    -- **Sempre positivo**: un rimborso non e' un pagamento negativo. Sarebbe comodo per
    -- un giorno e sbagliato per sempre — un rimborso ha una causale, un'autorizzazione e
    -- spesso un canale diverso dall'incasso, e sommarlo qui renderebbe illeggibile sia
    -- quanto e' entrato sia quanto e' uscito. Quando servira' sara' una tabella sua.
    importo                NUMERIC(10,2)  NOT NULL CHECK (importo > 0),

    -- Come e' arrivato il denaro. **Tre valori e non quattro**: manca `ONLINE`, che
    -- nascera' col branch di Stripe insieme al codice che lo produce. Dichiararlo adesso
    -- vorrebbe dire un valore che nessuna riga puo' avere — una promessa senza codice,
    -- vietata dalla regola 17.
    metodo                 VARCHAR(20)    NOT NULL CHECK (metodo IN ('CONTANTI', 'BONIFICO', 'POS')),

    -- **Quando il denaro e' arrivato, non quando qualcuno l'ha scritto qui.** Le due date
    -- divergono di continuo: un bonifico si vede sul conto il lunedi' e lo si registra il
    -- martedi'. Chi riconcilia con l'estratto conto ha bisogno della prima, e `created_at`
    -- continua a dire la seconda.
    incassato_il           TIMESTAMP      NOT NULL,

    -- Il numero del bonifico, l'identificativo della transazione POS, la ricevuta.
    -- Facoltativo perche' i contanti non ne hanno uno, e inventarne uno sarebbe peggio
    -- che lasciarlo vuoto.
    riferimento            VARCHAR(100),

    -- **Chi ha registrato l'incasso.** Un registro di denaro senza il nome di chi ci
    -- scrive e' un registro che non serve a niente il giorno in cui i conti non tornano.
    --
    -- Nullable, e per una ragione che vale ora e varra' di piu' dopo: un pagamento che
    -- arriva da un fornitore non lo registra nessuno, lo registra un webhook. Il branch di
    -- Stripe trovera' la colonna gia' pronta a dire "nessuno, e' arrivato da fuori".
    --
    -- **ON DELETE SET NULL e non CASCADE**: un account del personale si disattiva e non si
    -- cancella, ma se una riga di `staff` sparisse, cancellare i pagamenti che quella
    -- persona ha registrato vorrebbe dire perdere il denaro insieme a chi l'ha incassato.
    registrato_da_staff_id BIGINT         REFERENCES staff(id) ON DELETE SET NULL,

    created_at             TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP      NOT NULL DEFAULT now()
);

-- La domanda e' sempre la stessa e non ce n'e' un'altra: "quanto e' stato versato su
-- questa prenotazione". La fa il riepilogo, la fa il controllo che non si incassi piu' del
-- dovuto, e la fara' la riconciliazione del branch successivo.
CREATE INDEX idx_pagamento_prenotazione ON pagamento (prenotazione_id);
