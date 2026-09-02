-- Le camere che non si possono vendere pur non essendo prenotate da nessuno.
--
-- **Perche' serve un'entita' nuova e non basta una prenotazione finta.** Fino a oggi
-- l'unico modo per cui una camera risultava occupata era una riga di `prenotazione`:
-- c'e' un cliente, c'e' un importo, c'e' una macchina a stati. Una camera col bagno
-- rotto non ha niente di tutto questo — nessuno la paga, nessuno ci dorme, nessuno la
-- conferma o la annulla — e rappresentarla come una prenotazione intestata a un cliente
-- inventato vorrebbe dire sporcare di righe false la tabella su cui si calcolano
-- fatturato, tassa di soggiorno e schedine.
--
-- **E' anche il pezzo che manca alla sincronia col canale** (punto 25): quando Booking
-- vende una camera, da noi non nasce una prenotazione — i dati del cliente non ce li
-- danno — nasce l'indisponibilita' di una unita' in quelle notti. Questa tabella e' il
-- prerequisito di quel branch, ed e' il motivo per cui viene prima e da sola: sta in
-- piedi anche senza iCal, perche' "chiudi la camera 12 fino a venerdi'" e' gia' una
-- funzionalita' intera.

CREATE TABLE blocco_disponibilita (
    id                  BIGSERIAL   PRIMARY KEY,

    -- Di quale tipologia si sta togliendo una unita'. **Sempre valorizzata**, anche
    -- quando si sa gia' quale camera: e' la tipologia che la disponibilita' conta, e
    -- risalirci dalla camera vorrebbe dire una join in piu' nella query piu' calda del
    -- progetto.
    tipologia_camera_id BIGINT      NOT NULL REFERENCES tipologia_camera(id) ON DELETE CASCADE,

    -- **Quale** camera, se lo si sa. NULL vuol dire "una qualunque di quella tipologia".
    --
    -- **La semantica del conteggio non cambia**, ed e' la decisione che tiene insieme la
    -- tabella: un blocco vale **una unita'**, punto. Che sia la camera 12 o "una doppia
    -- qualsiasi" cambia solo cosa si sa dirne, non quanto toglie alla disponibilita'.
    -- Senza questa regola servirebbero due tabelle, o una colonna `quantita` che
    -- nessuno saprebbe come compilare per una manutenzione.
    --
    -- Chi blocca per manutenzione la camera la nomina — e' quella che ha il bagno rotto.
    -- Chi importa da un canale esterno no, perche' Booking dice "una doppia e' venduta"
    -- e quale sia non lo dice nessuno.
    camera_id           BIGINT      REFERENCES camera(id) ON DELETE CASCADE,

    -- Estremi del periodo, con la stessa convenzione delle prenotazioni: si entra il
    -- giorno di inizio e si libera il giorno di fine. Chi blocca dal 3 al 5 rende
    -- invendibili le notti del 3 e del 4, e il 5 la camera torna disponibile — cosi'
    -- un blocco e una prenotazione si confrontano senza tradurre niente.
    data_inizio         DATE        NOT NULL,
    data_fine           DATE        NOT NULL,

    -- **Chi ha scritto questa riga**, non perche'. Il perche' sta in `note`, che e'
    -- testo libero; questa colonna serve a una decisione sola e concreta: **l'import di
    -- un canale esterno rifa' i propri blocchi da capo ad ogni giro e non deve toccare
    -- quelli scritti a mano**. Senza questa colonna, la prima sincronizzazione
    -- porterebbe via la manutenzione che qualcuno ha inserito alla reception.
    origine             VARCHAR(20) NOT NULL CHECK (origine IN ('MANUALE', 'CANALE_ESTERNO')),

    -- L'identificativo che il calendario esterno da' a quell'occupazione (l'UID di una
    -- riga iCal). NULL per i blocchi manuali.
    --
    -- **Non e' ancora usato da niente**, e sta qui lo stesso: e' l'unica colonna di
    -- questa tabella che il punto 25 non potrebbe aggiungere senza una migration in
    -- piu', perche' senza di lei l'import non sa distinguere "questa occupazione l'ho
    -- gia' vista" da "e' nuova". Il codice che la legge arriva col branch dell'iCal.
    riferimento_esterno VARCHAR(255),

    -- Perche' la camera non e' vendibile, in parole. Facoltativo: un blocco importato
    -- non ha niente da dire, e uno manuale spesso si spiega da solo.
    note                VARCHAR(500),

    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now()
);

-- Un periodo che finisce prima di cominciare non e' un blocco corto, e' un errore.
-- **Disuguaglianza stretta**, come per le prenotazioni: inizio e fine coincidenti
-- vorrebbero dire zero notti bloccate, cioe' una riga che non fa niente.
ALTER TABLE blocco_disponibilita
    ADD CONSTRAINT ck_blocco_periodo CHECK (data_fine > data_inizio);

-- La camera indicata deve essere di quella tipologia, ma **il database non lo verifica**
-- e vale la pena dire perche': un CHECK non puo' leggere un'altra tabella, e un trigger
-- per questo sarebbe il primo del progetto. Lo pretende il Service, dove la domanda si
-- risponde con una query che c'e' gia'.
--
-- La conseguenza, come per l'accoppiata tipo/numero del documento sull'ospite: una
-- INSERT scritta a mano puo' mettere una camera di un'altra tipologia, e quel blocco
-- toglierebbe una unita' alla tipologia sbagliata.

-- **Due blocchi sulla stessa camera non possono sovrapporsi**, ed e' un invariante e non
-- una raffinatezza: la disponibilita' conta un'unita' per blocco, quindi due righe
-- sovrapposte sulla camera 12 toglierebbero **due** camere mentre la stanza e' una sola.
-- L'albergo risulterebbe pieno prima di esserlo, e nessuno saprebbe perche'.
--
-- E' il **terzo vincolo di esclusione** del progetto, dopo i periodi tariffari (V9) e le
-- aliquote della tassa di soggiorno (V11), e usa la stessa estensione `btree_gist` che
-- quelli hanno gia' introdotto. Come li', l'intervallo e' `[)` — inizio incluso, fine
-- esclusa — cosi' un blocco che finisce il 5 e uno che comincia il 5 convivono, che e'
-- il caso normale di due manutenzioni consecutive.
--
-- **Parziale**: vale solo dove la camera e' nominata. Due blocchi anonimi della stessa
-- tipologia negli stessi giorni sono legittimi — vogliono dire due unita' vendute
-- altrove — ed e' esattamente il caso che il branch dell'iCal produrra' in continuazione.
ALTER TABLE blocco_disponibilita
    ADD CONSTRAINT ck_blocco_camera_non_sovrapposta
        EXCLUDE USING gist (
            camera_id WITH =,
            daterange(data_inizio, data_fine, '[)') WITH &&
        ) WHERE (camera_id IS NOT NULL);

-- La domanda della disponibilita' e' "quali blocchi di questa tipologia toccano queste
-- notti", ed e' anche la piu' frequente: la fa ogni ricerca e ogni conferma. Le colonne
-- sono in quest'ordine perche' la tipologia seleziona per prima e le date rifiniscono —
-- lo stesso criterio di idx_prenotazione_disponibilita del V1.
CREATE INDEX idx_blocco_tipologia_periodo
    ON blocco_disponibilita (tipologia_camera_id, data_inizio, data_fine);

-- Il check-in cerca invece "questa camera precisa e' bloccata in queste notti", e senza
-- un indice suo scorrerebbe tutti i blocchi della tipologia. Parziale perche' i blocchi
-- senza camera — quelli che arrivano dai canali — a questa domanda non rispondono mai.
CREATE INDEX idx_blocco_camera_periodo
    ON blocco_disponibilita (camera_id, data_inizio, data_fine)
    WHERE camera_id IS NOT NULL;
