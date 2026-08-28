-- L'identita' della struttura: chi e', dove sta, e con che codici si presenta
-- alle amministrazioni.
--
-- Il V1 aveva previsto `impostazioni_hotel` come una tabella di recapiti — nome,
-- indirizzo, telefono, email e i due orari di default — e per il piano di allora
-- era abbastanza: quei campi servono al sito, e il sito era tutto cio' che
-- doveva leggerli. Dal 2026-08-28 non basta piu'. La tassa di soggiorno ha
-- bisogno di sapere in che **comune** ci si trova, perche' l'aliquota la decide
-- il comune; le schedine ad Alloggiati Web hanno bisogno del **codice
-- struttura** assegnato dalla Questura; le fatture hanno bisogno della
-- **ragione sociale** e della **partita IVA**, che non sono il nome
-- commerciale. Sono tutti dati della stessa riga — l'anagrafica di questo
-- albergo — quindi stanno qui e non in tabelle loro.
--
-- **Cosa NON entra**: le aliquote della tassa di soggiorno. Sembrano due
-- colonne finche' non si chiede se debba gestirle l'albergatore: con le
-- esenzioni per eta', il tetto di notti e i periodi diventano un'entita' con le
-- sue righe, e come tale nascera' dove e' *la* decisione invece di
-- un'appendice.
--
-- **Tutte le colonne nuove sono nullable**, e non e' pigrizia. La riga esiste
-- gia' (vedi sotto) e un albergo che apre il gestionale ha il proprio nome
-- prima di avere sottomano il codice ISTAT del comune: renderle NOT NULL
-- vorrebbe dire o inventare un valore finto per ciascuna, o impedire di salvare
-- il numero di telefono finche' non si e' cercato tutto il resto.
ALTER TABLE impostazioni_hotel
    ADD COLUMN ragione_sociale             VARCHAR(200),
    ADD COLUMN partita_iva                 VARCHAR(20),
    ADD COLUMN codice_fiscale              VARCHAR(16),
    ADD COLUMN cin                         VARCHAR(30),
    ADD COLUMN comune                      VARCHAR(100),
    ADD COLUMN codice_istat_comune         VARCHAR(6),
    ADD COLUMN codice_struttura_alloggiati VARCHAR(20);

-- La riga e' una sola, e da qui in poi lo dice il database invece di una
-- convenzione.
--
-- Il progetto e' single-hotel: `impostazioni_hotel` non e' una collezione ma
-- l'anagrafica dell'unica struttura, e infatti nessuna rotta la indirizza per
-- id. Finora quel "una sola" era una frase in un commento — niente impediva a
-- una INSERT di aggiungerne una seconda, e da quel momento in poi "le
-- impostazioni dell'hotel" avrebbe smesso di identificare qualcosa.
--
-- Il CHECK e' il modo piu' economico di dirlo: nessun trigger, nessuna tabella
-- di appoggio, e chi prova ad aggiungerne una seconda lo scopre subito invece
-- che il giorno in cui due letture rispondono in modo diverso.
ALTER TABLE impostazioni_hotel
    ADD CONSTRAINT ck_impostazioni_hotel_riga_singola CHECK (id = 1);

-- La riga nasce qui, con dei segnaposto, e non alla prima chiamata di un
-- endpoint.
--
-- **E' la decisione che semplifica tutto il resto.** Con la riga seminata la
-- GET risponde sempre 200 e non esiste nessuno stato "impostazioni non ancora
-- configurate": non lo deve gestire questo codice, e soprattutto non lo
-- dovranno gestire le fatture e le schedine che verranno dopo, che altrimenti
-- si porterebbero dietro un ramo "e se non ci fossero?" ciascuna. Il prezzo e'
-- che per un momento il database contiene un nome che nessuno ha scelto; il
-- prezzo dell'alternativa e' un ramo in piu' in ogni consumatore, per sempre.
--
-- Gli orari sono quelli tipici di un albergo e non un valore assurdo di
-- proposito: sono NOT NULL dal V1, quindi un valore va scritto comunque, e
-- 14:00/10:00 e' quello che un albergatore correggerebbe solo se il suo e'
-- diverso. Il nome invece dice apertamente di essere da sistemare, perche'
-- quello nessuno puo' indovinarlo.
--
-- L'id e' esplicito perche' il CHECK qui sopra pretende esattamente 1: lasciarlo
-- alla sequenza funzionerebbe adesso (e' la prima riga) ma legherebbe la
-- correttezza della migration allo stato del contatore.
INSERT INTO impostazioni_hotel (id, nome, orario_check_in_default, orario_check_out_default)
VALUES (1, 'Struttura da configurare', '14:00', '10:00');
