-- Le tabelle di codifica che pubblica il Ministero: i comuni italiani, gli stati
-- esteri, i tipi di documento e i tipi di alloggiato, ognuno col codice che il
-- servizio Alloggiati Web pretende.
--
-- **Perche' esistono qui e non nel codice.** Sono la quarta riga della regola 24,
-- nata il 2026-09-01 da una domanda di Giulio — *"quei dati come ISTAT non
-- possiamo renderli modificabili dal backoffice?"*. Non stanno in nessuno degli
-- altri tre secchi: non li cambia l'albergatore, non li cambia chi installa, ma
-- **cambiano lo stesso**, perche' i comuni si fondono e gli stati nascono. Nel
-- codice ogni fusione di comuni diventerebbe un rilascio; in una schermata di
-- digitazione l'albergatore potrebbe "correggere" un codice che gli sembra
-- sbagliato e scoprirlo solo quando la Questura rifiuta le schedine.
--
-- La forma giusta e' questa: **tabella seminata, leggibile, aggiornata in blocco
-- e mai digitata riga per riga**.

-- ============================================================
-- VOCE_CODIFICA
-- ============================================================
--
-- **Una tabella sola per quattro elenchi**, ed e' la decisione da guardare due
-- volte perche' un discriminatore di tipo su una tabella di solito e' un odore.
-- Qui non lo e', e la ragione e' che non sono quattro cose diverse: sono
-- **quattro famiglie della stessa cosa** — una voce di codifica, cioe' un codice
-- con la sua descrizione — pubblicate dalla stessa autorita', nello stesso
-- formato, che si aggiornano insieme e si consultano allo stesso modo. Quattro
-- tabelle avrebbero voluto dire quattro entita', quattro repository, quattro
-- endpoint di lettura e quattro di import, per una differenza di forma che e'
-- **una colonna** (la provincia, che hanno solo i comuni).
--
-- Il criterio, per chi un giorno volesse spacchettarle: si spacchetta quando una
-- delle quattro comincia ad avere campi o regole sue. Finche' la domanda che si
-- fa a tutte e' "dammi codice e descrizione", la tabella e' una.
--
-- **Le righe non ci sono, e non e' un lavoro lasciato a meta'.** Questa migration
-- crea le tabelle **vuote**: i valori veri li pubblica il Ministero sul portale
-- Alloggiati, e inventarli qui vorrebbe dire scrivere dati falsi proprio nella
-- tabella che esiste per essere esatta — cioe' il difetto che la quarta riga
-- della regola 24 esiste per evitare. Un'installazione vera li carica con
-- PUT /api/codifiche/{tipo} al primo avvio, scaricandoli dal portale con le
-- proprie credenziali. E' scritto anche nel contratto, perche' chi trova la
-- tabella vuota non pensi a un difetto.
CREATE TABLE voce_codifica (
    id          BIGSERIAL     PRIMARY KEY,

    -- Quale dei quattro elenchi. Il CHECK elenca i valori come il V1 fa per lo
    -- stato della camera, e per la stessa ragione: questo elenco lo scrive
    -- **l'applicazione** — e' il nome di una famiglia, non un dato del Ministero —
    -- quindi un valore fuori elenco sarebbe un difetto nostro, non una novita' del
    -- mondo. E' il caso opposto a tipo_documento dell'ospite, che il CHECK non ce
    -- l'ha proprio perche' l'elenco dei documenti validi lo cambia la Questura.
    tipo        VARCHAR(30)   NOT NULL
                CHECK (tipo IN ('COMUNE', 'STATO', 'TIPO_DOCUMENTO', 'TIPO_ALLOGGIATO')),

    -- Il codice come lo pubblica il Ministero. **Testo e non numero**, anche per i
    -- comuni: sono stringhe con gli zeri davanti, e un intero li mangerebbe.
    codice      VARCHAR(20)   NOT NULL,

    descrizione VARCHAR(150)  NOT NULL,

    -- La sigla della provincia. **Solo per i comuni**, nullable per tutti gli
    -- altri: e' l'unica colonna che distingue le quattro famiglie, ed e' anche il
    -- motivo per cui la tabella resta una sola. Serve a disambiguare gli omonimi,
    -- che in Italia sono tanti — di "San Giovanni" ce n'e' uno per regione.
    provincia   VARCHAR(2),

    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT now()
);

-- Un codice non si ripete dentro la sua famiglia, e fra famiglie diverse invece
-- coincidere e' il caso normale: niente vieta che il codice "100" sia un comune e
-- anche uno stato, perche' sono due elenchi che non si parlano.
--
-- **Case-sensitive**, come gli url delle foto e i numeri di documento e al
-- contrario dei nomi di tipologie, dotazioni e camere: non e' un nome che una
-- persona scrive a modo suo, e' una stringa emessa da un'autorita' che la
-- considera esatta cosi' com'e'.
CREATE UNIQUE INDEX uq_voce_codifica_tipo_codice ON voce_codifica (tipo, codice);

-- L'unica lettura di questa tabella e' "dammi le voci di questa famiglia, in
-- ordine, magari filtrate per nome" — cioe' cio' che serve a riempire una tendina.
-- L'indice sulla coppia (tipo, descrizione) la serve per intero: seleziona la
-- famiglia e restituisce gia' ordinato, senza un sort su ottomila righe ad ogni
-- apertura del menu a tendina.
CREATE INDEX idx_voce_codifica_tipo_descrizione ON voce_codifica (tipo, descrizione);
