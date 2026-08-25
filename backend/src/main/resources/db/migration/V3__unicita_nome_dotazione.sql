-- Il nome di una dotazione era gia' unico dal V1, ma con un UNIQUE di colonna,
-- quindi case-sensitive: "Wi-Fi" e "wi-fi" passavano come due voci distinte.
--
-- E' lo stesso difetto corretto sulle tipologie di camera dal V2, e qui pesa
-- di piu': le dotazioni finiscono in un elenco a scelta multipla nel
-- backoffice, dove due righe che si leggono uguali non sono un doppione
-- fastidioso ma un modo per assegnare "la stessa" dotazione due volte a due
-- camere diverse e non ritrovarla piu' filtrando.
--
-- Il service fa lo stesso confronto (existsByNomeIgnoreCase) per poter
-- rispondere 409 con un messaggio comprensibile; la garanzia vera e' questo
-- indice, l'unico che regge anche quando due richieste identiche arrivano
-- nello stesso istante.
ALTER TABLE dotazione DROP CONSTRAINT dotazione_nome_key;

CREATE UNIQUE INDEX uq_dotazione_nome ON dotazione (lower(nome));
