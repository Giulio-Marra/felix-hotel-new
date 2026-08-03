# Felix Hotel — Backend

Spring Boot 4.1.0, Java 21, Maven.

## Prerequisiti

- Java 21
- PostgreSQL in esecuzione (locale o Docker)
- Un file `.env` (copiato da `.env.example`, mai committato) con le
  credenziali del database e il secret JWT

## Setup locale

1. Copia `.env.example` in `.env` e valorizzalo:
   ```
   cp .env.example .env
   ```
2. Esporta le variabili nell'ambiente della shell (o usa un plugin
   tipo `spring-dotenv` più avanti — per ora vanno esportate a mano
   o passate come variabili d'ambiente del sistema/IDE).
3. Avvia:
   ```
   ./mvnw spring-boot:run
   ```
4. Le migration Flyway (`src/main/resources/db/migration`) si
   applicano automaticamente all'avvio.
5. Swagger UI: `http://localhost:8080/swagger-ui.html`

## Struttura pacchetti (convenzione di progetto)

Pattern Controller → Service (interfaccia + implementazione) →
Repository. Il Controller resta sottile, la logica di business sta
nel Service dietro un'interfaccia.

## Note

Dettagli su modellazione dominio, decisioni architetturali e
convenzioni complete: vedi `docs/` nella root del progetto (non
versionato su Git, solo locale).
