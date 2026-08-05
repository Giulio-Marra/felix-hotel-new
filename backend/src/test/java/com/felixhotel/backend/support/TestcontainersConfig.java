package com.felixhotel.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Il database dei test: un Postgres vero, avviato in Docker da Testcontainers.
 *
 * <p>Non un database in memoria, di proposito. Lo schema di questo progetto lo
 * possiede Flyway e Hibernate si limita a validarlo
 * ({@code ddl-auto=validate}): su un H2 la validazione passerebbe contro uno
 * schema diverso da quello reale, e il DDL usa comunque costrutti Postgres.
 * Effetto collaterale voluto: ogni giro di test riapplica le migration da zero,
 * quindi verifica anche quelle.
 *
 * <p>{@code @ServiceConnection} sostituisce da solo url, utente e password del
 * datasource con quelli del container: non serve scriverli da nessuna parte, e
 * i test non toccano mai il Postgres di sviluppo.
 *
 * <p>Il container e' un bean del contesto, quindi Spring lo riusa per tutte le
 * classi di test che condividono lo stesso contesto: parte una volta sola, non
 * una per classe.
 *
 * <p>Richiede Docker attivo sulla macchina — vale solo per {@code mvnw verify},
 * gli unitari non ne hanno bisogno.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    /**
     * Stessa major version del Postgres di sviluppo e (in prospettiva) di
     * produzione: testare su una versione diversa da quella che gira davvero
     * vanifica meta' del motivo per cui si usa un DB reale.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
