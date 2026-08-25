package com.felixhotel.backend.support;

import com.felixhotel.backend.entity.Ruolo;
import com.felixhotel.backend.entity.Staff;
import com.felixhotel.backend.repository.RuoloRepository;
import com.felixhotel.backend.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea account del personale (ADMIN o STAFF) direttamente a database, per i
 * test che devono chiamare un endpoint riservato.
 *
 * <p>Non esiste un endpoint per farlo, ed e' un fatto del progetto e non una
 * mancanza dei test: la registrazione pubblica crea solo clienti con ruolo
 * USER, e la gestione degli account del personale e' deliberatamente rimandata
 * (oggi un ADMIN si crea scrivendo nel database). Finche' e' cosi', un test che
 * voglia un token da amministratore deve fare quello che farebbe una persona:
 * inserire la riga, e poi autenticarsi dall'endpoint vero.
 *
 * <p>La password e' {@link TestDataFactory#PASSWORD_VALIDA} e viene cifrata con
 * lo stesso {@code PasswordEncoder} dell'applicazione: cosi' l'account creato
 * qui e' indistinguibile da uno vero e {@code IntegrationTestBase.ottieniToken}
 * ci funziona sopra senza sapere come e' nato.
 *
 * <p>E' un {@code @Component} in {@code src/test/java}: viene raccolto dal
 * component scan del contesto di test — come {@code SoloAdminTestController} —
 * quindi non serve importarlo e non se ne crea un secondo contesto Spring, che
 * vorrebbe dire un secondo container Postgres.
 */
@Component
@RequiredArgsConstructor
public class CreatoreStaff {

    private final StaffRepository staffRepository;
    private final RuoloRepository ruoloRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Account con ruolo ADMIN, gia' attivo e pronto per il login.
     *
     * <p>Non restituisce niente di proposito: a chi chiama serve solo che
     * l'account esista, e l'email ce l'ha gia' perche' l'ha scelta lui. Un
     * valore di ritorno che nessuno legge e' superficie che qualcuno dovra'
     * mantenere senza sapere a cosa serviva.
     */
    public void creaAdmin(String email) {
        crea(email, "ADMIN");
    }

    /**
     * Account con ruolo STAFF. Serve a provare che il 403 sulle scritture del
     * catalogo non riguarda solo i clienti: neanche il personale non
     * amministratore ci passa.
     */
    public void creaStaff(String email) {
        crea(email, "STAFF");
    }

    private void crea(String email, String nomeRuolo) {
        Ruolo ruolo = ruoloRepository.findByNome(nomeRuolo)
                .orElseThrow(() -> new IllegalStateException(
                        "Ruolo " + nomeRuolo + " mancante in DB: verificare V1__init_schema.sql"));

        Staff staff = new Staff();
        staff.setNome("Anna");
        staff.setCognome("Bianchi");
        staff.setEmail(email);
        staff.setPasswordHash(passwordEncoder.encode(TestDataFactory.PASSWORD_VALIDA));
        staff.setAttivo(true);
        staff.setRuolo(ruolo);

        staffRepository.save(staff);
    }
}
