-- Il numero di una camera era gia' unico dal V1, ma con un UNIQUE di colonna,
-- quindi case-sensitive: "12A" e "12a" sarebbero entrate come due camere.
--
-- E' la terza volta che si corregge questo stesso punto (V2 sulle tipologie, V3
-- sulle dotazioni) e qui il caso e' il piu' concreto dei tre: il numero di
-- camera lo si trascrive da una porta, e chi lo digita non sta pensando alle
-- maiuscole. Due righe per la stessa stanza vorrebbero dire due inventari che
-- non tornano e, il giorno che ci saranno le prenotazioni, la stessa camera
-- prenotabile due volte.
--
-- Il service fa lo stesso confronto (existsByNumeroIgnoreCase) per poter
-- rispondere 409 con un messaggio comprensibile; la garanzia vera e' questo
-- indice, l'unico che regge anche quando due richieste identiche arrivano nello
-- stesso istante.
ALTER TABLE camera DROP CONSTRAINT camera_numero_key;

CREATE UNIQUE INDEX uq_camera_numero ON camera (lower(numero));
