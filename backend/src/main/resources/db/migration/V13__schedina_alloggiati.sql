-- I campi che la schedina alloggiati pretende e che l'ospite non aveva.
--
-- **Perche' adesso.** La tabella `ospite` nasce nel V1 come registro del TULPS: chi
-- dorme qui e con che documento. E' il contenuto della schedina, ma non tutto il
-- contenuto: il tracciato che il portale Alloggiati Web accetta chiede anche il
-- **sesso**, il **luogo di nascita**, la **cittadinanza**, il **luogo di rilascio
-- del documento** e soprattutto il **tipo di alloggiato** — cioe' con chi sei, non
-- solo chi sei. Senza queste sei colonne il file si potrebbe generare lo stesso, e
-- verrebbe rifiutato al caricamento: un export che produce un file invalido e' una
-- promessa senza il codice che la mantenga (regola 17), quindi le colonne vengono
-- prima dell'export e stanno in questa migration.
--
-- **Tutte facoltative, e non e' un rinvio.** Sono tre ragioni distinte, e conviene
-- tenerle separate perche' solo la prima e' transitoria:
--   1. le righe che ci sono gia' non si possono riempire. Nessuno sa in che comune
--      sia nato un ospite registrato la settimana scorsa, e un default — l'epoca,
--      "ROMA", "M" — sarebbe un dato **inventato in un registro di legge**, che e'
--      esattamente il difetto che il V10 ha appena finito di togliere di mezzo;
--   2. i codici che questi campi contengono stanno in `voce_codifica`, che il V12
--      crea **vuota** di proposito: li pubblica il Ministero e li importa l'ADMIN.
--      Renderli obbligatori alla registrazione vorrebbe dire che un'installazione
--      appena fatta non puo' registrare nessun ospite finche' non ha importato
--      ottomila comuni — cioe' subordinare un obbligo di legge (il registro) a un
--      adempimento diverso (la schedina);
--   3. il registro e la schedina sono **due obblighi con due momenti**. Al banco,
--      alle due di notte, si scrive chi e' arrivato; la schedina si manda entro
--      ventiquattro ore. Un campo mancante deve fermare la seconda, non la prima.
--
-- **Chi fa allora rispettare l'obbligo**: l'export. `GET /api/alloggiati/schedine`
-- risponde **409 e nomina l'ospite e il campo** invece di produrre un file a meta',
-- che il portale rifiuterebbe senza dire di chi. E' lo stesso verso del check-in
-- che pretende gli ospiti registrati: il controllo sta dove il dato serve davvero.

ALTER TABLE ospite

    -- Che ruolo ha questa persona nel gruppo che soggiorna. E' il primo campo del
    -- tracciato e decide **quanto del resto va compilato**: per un ospite singolo,
    -- un capofamiglia o un capogruppo la schedina vuole anche il documento; per un
    -- familiare o un membro del gruppo quei campi vanno lasciati in bianco, perche'
    -- il documento lo ha esibito chi li accompagna.
    --
    -- **Valori nostri e non del Ministero**, ed e' la stessa scelta gia' fatta per
    -- `tipo_documento`: il Ministero pubblica i suoi codici in `voce_codifica`
    -- (famiglia TIPO_ALLOGGIATO), ma *quali* dei nostri casi sono "capo" e quali
    -- "accompagnato" e' logica di questa applicazione, non un dato importabile —
    -- nessun file del Ministero dice che a un familiare il documento non si chiede.
    -- La corrispondenza fra i due elenchi sta in CodiciAlloggiati.java.
    --
    -- **Col CHECK**, al contrario di `tipo_documento` e come `voce_codifica.tipo`:
    -- il criterio e' scritto nel V12 e vale identico qui — questo elenco lo scrive
    -- l'applicazione, quindi un valore fuori elenco e' un difetto nostro e non una
    -- novita' del mondo.
    ADD COLUMN tipo_alloggiato VARCHAR(30)
        CHECK (tipo_alloggiato IN ('OSPITE_SINGOLO', 'CAPOFAMIGLIA', 'CAPOGRUPPO',
                                   'FAMILIARE', 'MEMBRO_GRUPPO')),

    -- 'M' o 'F'. Il tracciato lo vuole come 1 o 2, e la traduzione sta in Java:
    -- qui si scrive la lettera, perche' e' quel che una persona legge riaprendo la
    -- tabella fra un anno — un 2 in una colonna `sesso` non si interpreta senza
    -- avere sotto mano il tracciato.
    --
    -- **Due valori e non tre.** Non e' una posizione su cosa sia il sesso di una
    -- persona: e' il campo di un modulo che accetta due valori e ne rifiuta ogni
    -- altro, e inventarne un terzo qui vorrebbe dire una schedina scartata. Se il
    -- Ministero un giorno ne accettera' altri, e' il CHECK a cambiare.
    --
    -- **CHAR e non VARCHAR**, ed e' l'unica colonna del progetto a esserlo: e' un
    -- codice di lunghezza fissa, una lettera e non una parola, e Hibernate mappa cosi'
    -- un enum lungo un carattere — con un VARCHAR(1) la validazione dello schema
    -- all'avvio fallisce. Sul contenuto non cambia niente, perche' con lunghezza uno
    -- non c'e' nessun riempimento da fare.
    ADD COLUMN sesso CHAR(1) CHECK (sesso IN ('M', 'F')),

    -- Il codice del comune italiano di nascita, come lo pubblica il Ministero
    -- (famiglia COMUNE di `voce_codifica`). **Testo e non numero**, per la stessa
    -- ragione scritta nel V12: sono stringhe con gli zeri davanti.
    --
    -- **Nessuna chiave esterna verso `voce_codifica`**, ed e' deliberato. Sarebbe
    -- venuto spontaneo, e legherebbe due cose che devono restare libere di muoversi
    -- separatamente: l'import del Ministero **sostituisce** l'intera famiglia
    -- (V12), quindi con una FK ogni aggiornamento dei comuni fallirebbe — o
    -- cancellerebbe a cascata — per colpa di ospiti registrati anni prima. Una
    -- schedina gia' mandata non deve poter cambiare perche' un comune si e' fuso.
    -- Il codice qui e' **una fotografia**, come `importo_totale` sulla prenotazione.
    -- Che il codice esista lo verifica il Service quando lo scrive.
    ADD COLUMN comune_nascita VARCHAR(20),

    -- Il codice dello stato estero di nascita (famiglia STATO), per chi in Italia
    -- non e' nato. **Esclusivo rispetto al comune**: il tracciato ha due campi
    -- distinti e ne vuole compilato esattamente uno — comune piu' provincia per chi
    -- e' nato qui, stato per chi e' nato altrove. Il CHECK qui sotto vieta la
    -- coppia; che almeno uno ci sia lo pretende l'export, per la ragione scritta in
    -- cima.
    ADD COLUMN stato_nascita VARCHAR(20),

    -- Il codice dello stato di cittadinanza (famiglia STATO). **Non si deduce dal
    -- luogo di nascita** e non va confuso con lui: chi e' nato a Milano da genitori
    -- stranieri puo' benissimo non essere cittadino italiano, e il modulo chiede
    -- tutte e due le cose proprio perche' sono diverse.
    ADD COLUMN cittadinanza VARCHAR(20),

    -- Dove e' stato rilasciato il documento: un comune italiano **oppure** uno stato
    -- estero, in un campo solo perche' il tracciato ne ha uno solo. E' l'unica
    -- colonna che puo' contenere un codice di due famiglie diverse, ed e' anche il
    -- motivo per cui non poteva avere una FK nemmeno volendo.
    --
    -- Serve solo a chi un documento ce l'ha: resta vuoto per i minorenni registrati
    -- senza (V10) e per chi e' familiare o membro di un gruppo.
    ADD COLUMN luogo_rilascio_documento VARCHAR(20);

-- Nato in un comune **o** in uno stato estero, mai in tutti e due.
--
-- **Vieta la coppia e non pretende la presenza**, ed e' la differenza che conta: un
-- NOT NULL qui bloccherebbe la registrazione degli ospiti su un'installazione senza
-- codifiche importate (ragione 2 in cima), mentre due valori insieme sono un dato
-- contraddittorio in ogni momento della vita della riga — nessuno nasce in due posti.
-- Un vincolo si scrive in database quando e' vero sempre; quando dipende da *quando*
-- lo si guarda, sta nel codice che conosce il momento.
ALTER TABLE ospite
    ADD CONSTRAINT ck_ospite_nascita_comune_o_stato
        CHECK (comune_nascita IS NULL OR stato_nascita IS NULL);

-- L'export chiede "chi e' arrivato il giorno X", cioe' le prenotazioni con quella
-- data di arrivo e in uno stato che dica che l'ospite si e' presentato davvero.
--
-- **Non lo serviva nessun indice esistente**: `idx_prenotazione_disponibilita`
-- comincia da `tipologia_camera_id`, che questa domanda non nomina, quindi restava
-- una scansione dell'intera tabella una volta al giorno per sempre. Le due colonne
-- sono in quest'ordine perche' la data seleziona quasi tutto — gli arrivi di un
-- giorno sono una manciata di righe — e lo stato rifinisce.
CREATE INDEX idx_prenotazione_arrivo ON prenotazione(data_check_in, stato);
