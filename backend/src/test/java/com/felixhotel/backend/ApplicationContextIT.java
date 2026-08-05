package com.felixhotel.backend;

import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifica che il contesto Spring si avvii per intero.
 *
 * <p>Sembra un test banale e non lo e': fallisce se un bean non e' iniettabile,
 * se Flyway non riesce ad applicare le migration, o se Hibernate trova
 * un'entity che non combacia con lo schema — cioe' proprio i guasti che questo
 * progetto ha scoperto solo avviando l'applicazione a mano.
 *
 * <p>Prende il posto del vecchio {@code BackendApplicationTests}: quello era un
 * {@code *Test}, quindi surefire lo eseguiva ad ogni {@code mvnw test} e
 * falliva a freddo, perche' pretendeva un Postgres gia' attivo e le variabili
 * d'ambiente impostate. Come {@code *IT} si porta dietro il proprio database.
 */
@DisplayName("Contesto applicativo")
class ApplicationContextIT extends IntegrationTestBase {

    @Test
    @DisplayName("si avvia con le migration applicate e le entity valide")
    void contesto_siAvvia() {
        // Nessuna asserzione: il test passa se il contesto (ereditato dalla classe base)
        // si e' avviato. Se qualcosa non torna, il fallimento avviene prima di qui.
    }
}
