# Felix Hotel

[![Backend CI](https://github.com/Giulio-Marra/felix-hotel-new/actions/workflows/backend-ci.yml/badge.svg?branch=dev)](https://github.com/Giulio-Marra/felix-hotel-new/actions/workflows/backend-ci.yml)

Gestionale per hotel: **backoffice** (camere, tariffe, prenotazioni, adempimenti di
legge) e **frontoffice** (ricerca disponibilità e prenotazione lato cliente).

È il rifacimento di un mio progetto di due anni fa, con un secondo obiettivo dichiarato:
**capire cosa sappia fare un'AI a cui si chiede di condurre lo sviluppo di un progetto
reale**, non di completare frammenti di codice. Claude lavora qui come sviluppatore sotto
un processo definito — prende le decisioni, scrive, si rilegge a freddo, si dà un voto e
documenta il perché di ogni scelta. Il codice e la storia dei commit sono il risultato di
quell'esperimento, ed è il motivo per cui questa repo è pubblica.

---

## A che punto è

Il **backend è quasi finito**; il frontend non è ancora iniziato.

| | |
|---|---|
| Migration Flyway | 16 |
| Rotte REST | 43 |
| Entità di dominio | 17 |
| Test | 388 unitari + 364 di integrazione |
| Copertura | ~96% istruzioni, ~91% rami |
| Commit | 65, tutti con CI verde su `dev` |

Restano due funzionalità — la sincronia dei calendari coi canali esterni e i pagamenti —
più il frontend, che è una fase a sé.

---

## Cosa c'è dentro, oltre al CRUD

Un gestionale alberghiero è facile da scrivere male: quattro entità e otto endpoint. Le
parti che rendono questo progetto diverso sono quelle in cui il dominio ha regole vere.

**La disponibilità si calcola sulla notte peggiore.** Non "quante prenotazioni toccano il
periodo", ma "qual è la notte più affollata": chi prenota vuole una stanza per *tutte* le
notti che ha chiesto, e tre soggiorni brevi in fila occupano una camera sola. È una query
nativa con un CTE, perché la JPQL non lo esprime.

**Il prezzo si calcola in un posto solo.** Periodi tariffari, prezzi per giorno della
settimana e soggiorno minimo, con un vincolo di esclusione PostgreSQL che impedisce a due
periodi della stessa tipologia di sovrapporsi. La stessa query serve la ricerca *e* la
creazione della prenotazione: due formule divergenti vorrebbero dire una pagina che mostra
un numero e una fattura che ne dice un altro.

**Gli adempimenti italiani, che sono il pezzo che vende.** Il registro degli ospiti del
TULPS, l'export delle schedine per il portale **Alloggiati Web** della Polizia di Stato —
tracciato a posizioni fisse, 168 caratteri per persona — e il calcolo della **tassa di
soggiorno**, con le esenzioni distinte fra *calcolate* (età, tetto di notti) e *dichiarate*
(residente, disabile, forze dell'ordine).

**Il documento lo dà chi ce l'ha.** Un minorenne un documento d'identità non ce l'ha, e
pretenderlo lo farebbe inventare al banco: un dato falso in un registro di legge è peggio
di uno mancante, perché nessuno lo distingue più da uno vero. Al suo posto diventa
obbligatoria la data di nascita, perché è lei a decidere.

**Le email come porta, non come dettaglio.** Verifica dell'indirizzo che blocca il login,
invito del personale al posto della password comunicata a voce, reset e conferma di
prenotazione. Un token viaggia in chiaro solo dentro il link: in tabella c'è la sua
impronta SHA-256, perché un token di reset è a tutti gli effetti una credenziale.

---

## Stack

**Backend** — Java 21, Spring Boot 4.1, PostgreSQL 16, Flyway, Spring Security con JWT
emesso e validato in casa.

**Contract-first**: lo YAML OpenAPI è la fonte di verità. DTO *e* interfacce dei Controller
sono generati a build-time da `openapi-generator`; i Controller le implementano e basta. Un
endpoint che non è nello spec non esiste.

**La qualità è un cancello, non un avviso** — `mvnw verify` fallisce se:

- la copertura JaCoCo scende sotto le soglie;
- SpotBugs con Find Security Bugs trova qualcosa (tolleranza zero);
- Checkstyle trova una violazione;
- un test è rosso, unitario o di integrazione.

**Test di integrazione su PostgreSQL vero**, con Testcontainers: lo schema è di Flyway e
Hibernate lo valida all'avvio, quindi un database finto non proverebbe la cosa che conta.

---

## Come si lavora, e perché è la parte interessante

Il processo è scritto, si applica a ogni branch e non si salta.

1. **Una decisione per branch.** Se una modifica è una decisione indipendente è un branch
   suo; se appartiene a quello già aperto, ci va dentro. Il criterio non è la dimensione
   del diff.
2. **Le decisioni di dominio si prendono prima di scrivere**, non mentre si scrive.
3. **`mvnw verify` verde prima di ogni commit**, poi si rilegge il proprio diff.
4. **A branch finito, review a freddo di tutto il diff**, riletto come se l'avesse scritto
   qualcun altro e ricostruendo l'intenzione dal solo codice. Se un pezzo non si capisce
   senza ricordarsi perché fu scritto, quello è già un rilievo.
5. **La review si chiude con un voto**, complessivo e per area. Il progetto sta fra 8 e 10:
   **sotto 8 non si mergia**, si sistema. Prima si elencano i problemi, poi si vota — mai il
   contrario, perché un numero deciso per primo cerca soltanto conferme.
6. **Prima di chiudere, l'applicazione si avvia davvero** contro un PostgreSQL locale e gli
   endpoint si provano a mano. In cinque branch su sei questo passo ha trovato qualcosa che
   750 test non vedevano — fra cui un 500 su una rotta pubblica che c'era da settimane.
7. **Quello che non si sistema subito si scrive subito.** Ogni voce porta con sé cosa non va,
   perché non è stato fatto adesso e *cosa lo renderà urgente*: senza il terzo punto, un
   elenco di debiti diventa rumore che si smette di leggere.

Il codice è commentato molto più della media, e di proposito: i commenti non dicono *cosa*
fa una riga, ma **perché è scritta così e quali alternative sono state scartate**. Un
esempio, da un repository:

> *La regola vera è un'altra: un parametro facoltativo va confrontato con la colonna, e con
> nient'altro. In `:da is null` il tipo non serve — qualunque cosa può essere null — e in
> `b.dataFine > :da` lo dà la colonna. È l'unica forma che non dipenda da quali conversioni
> Postgres accetti.*

Quella nota nasce da un difetto vero, comparso due volte a un giorno di distanza, la cui
prima diagnosi era sbagliata.

---

## Avviare il backend in locale

Servono **Java 21** e Docker.

```bash
# 1. il database
docker run -d --name felix-postgres \
  -e POSTGRES_DB=felix_hotel -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=felix_local_dev \
  -p 5432:5432 postgres:16-alpine

# 2. le variabili d'ambiente (vedi backend/.env.example)
cd backend && cp .env.example .env

# 3. l'applicazione
set -a; . ./.env; set +a
./mvnw spring-boot:run
```

Swagger UI su `http://localhost:8080/swagger-ui.html`.

```bash
./mvnw test      # solo unitari: ~2 secondi, nessun Docker
./mvnw verify    # unitari + integrazione + tutti i cancelli di qualità
```

> **In arrivo**: un `docker compose up` che avvia database, backend, webmail e dati di
> esempio, per provare l'applicazione senza configurare niente.

---

## Struttura

```
felix-hotel-new/
├── backend/     # Spring Boot — API REST, dominio, migration
├── frontend/    # React (non ancora iniziato)
└── docs/        # analisi funzionale e storico delle decisioni
```

---

## Cosa manca

- **Sincronia coi canali** — feed iCal verso Booking e Airbnb, per non vendere due volte la
  stessa camera
- **Pagamenti e caparre**, con preautorizzazione e incasso al check-in
- **Frontend React**
- Revoca dei token, coda di invio per le email, invio automatico delle schedine

Ognuna di queste voci ha, nella documentazione di progetto, il motivo per cui non è ancora
stata fatta e l'evento che la renderà urgente.
