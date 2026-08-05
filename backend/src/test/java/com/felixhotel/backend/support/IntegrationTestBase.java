package com.felixhotel.backend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Spring Boot 4 ha spacchettato spring-boot-test-autoconfigure in moduli per tecnologia:
// @AutoConfigureMockMvc vive in spring-boot-webmvc-test, non piu' sotto
// org.springframework.boot.test.autoconfigure.web.servlet (package di Boot 3).
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Base di ogni test di integrazione (classi {@code *IT}): avvia il contesto
 * Spring completo e il Postgres di {@link TestcontainersConfig}, ed espone
 * {@link MockMvc} per chiamare gli endpoint dal bordo HTTP.
 *
 * <p>Sta tutto qui per un motivo di forma: le annotazioni di contesto si
 * scrivono una volta sola. Un IT eredita da questa classe e contiene solo i
 * suoi test — se una classe di test riporta {@code @SpringBootTest} per conto
 * suo, vuol dire che sta divergendo dallo standard.
 *
 * <p>MockMvc e non un client HTTP reale: attraversa comunque tutta la catena
 * che ci interessa (filtri di sicurezza compresi, quindi 401 e 403 sono quelli
 * veri), ma senza aprire una porta di rete.
 *
 * <p><b>Isolamento fra test</b>: il database NON viene ripulito
 * automaticamente. I test qui dentro passano dagli endpoint HTTP, e una
 * transazione di test non avvolge quello che succede dietro MockMvc in modo
 * affidabile; per questo ogni test si costruisce i propri dati con email
 * univoche (vedi {@link TestDataFactory#emailUnivoca()}) invece di dipendere
 * dallo stato lasciato da altri.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Lo stesso ObjectMapper dell'applicazione (Jackson 3, package
     * {@code tools.jackson}): serializzare i body di richiesta con un mapper
     * diverso da quello che l'app usa per deserializzarli renderebbe il test
     * meno fedele proprio dove deve esserlo.
     */
    @Autowired
    protected ObjectMapper objectMapper;

    protected TestDataFactory dati;

    @BeforeEach
    void inizializzaDati() {
        dati = new TestDataFactory();
    }

    /** Serializza un oggetto nel JSON da mandare come body della richiesta. */
    protected String json(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
