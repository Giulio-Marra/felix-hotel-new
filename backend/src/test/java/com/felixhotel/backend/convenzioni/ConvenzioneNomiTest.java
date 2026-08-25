package com.felixhotel.backend.convenzioni;

import com.felixhotel.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fa rispettare la regola 16 sui nomi dei file di test.
 *
 * <p><b>Perche' esiste.</b> Il suffisso del nome non e' una convenzione
 * decorativa: e' quello che decide **quale plugin esegue cosa**. Surefire
 * raccoglie i {@code *Test} e li lancia ad ogni {@code mvnw test}, failsafe i
 * {@code *IT} e li lancia solo su {@code mvnw verify}, dove c'e' Docker. Un
 * nome sbagliato non da' errore: sposta soltanto un test da uno stadio
 * all'altro, o lo toglie di mezzo del tutto — in silenzio, che e' il modo
 * peggiore.
 *
 * <p>Fino a questo branch la regola era scritta e basta, e infatti era gia'
 * stata violata due volte: {@code OrologioDiTest} e {@code StaffDiTest} erano
 * file di supporto che finivano per {@code Test} senza contenere un test.
 * Surefire li raccoglieva, non ci trovava niente e passava oltre. Innocuo
 * quel giorno, ma e' una trappola per chi legge — e soprattutto e' una
 * promessa senza il codice che la mantenga (regola 17).
 *
 * <p><b>Cosa controlla</b>, cioe' la regola 16 tradotta in asserzioni:
 * <ul>
 *   <li>un {@code *Test} contiene almeno un test, altrimenti non e' un test;</li>
 *   <li>un {@code *IT} eredita da {@link IntegrationTestBase}, dove stanno le
 *       annotazioni di contesto scritte una volta sola;</li>
 *   <li>nessun altro dichiara {@code @SpringBootTest} per conto proprio, che
 *       e' il modo in cui una classe diverge dallo standard senza dirlo;</li>
 *   <li>niente {@code *Test} o {@code *IT} fra i sorgenti di produzione, dove
 *       finirebbero per essere eseguiti come test.</li>
 * </ul>
 * I file di supporto restano coperti dal primo punto: uno che finisse per
 * {@code Test} senza contenere test verrebbe fermato qui.
 *
 * <p>Unitario e non IT: guarda file e classi, non serve ne' Spring ne'
 * Postgres.
 */
@DisplayName("Convenzioni sui nomi dei file di test (regola 16)")
class ConvenzioneNomiTest {

    private static final Path RADICE_TEST = Path.of("src", "test", "java");
    private static final Path RADICE_MAIN = Path.of("src", "main", "java");

    /**
     * Le annotazioni che rendono un metodo un test per JUnit 5, citate per nome
     * e non per classe: cosi' il controllo non obbliga il progetto a dipendere
     * da moduli che oggi non usa (dei quattro, qui si usa solo {@code @Test} —
     * gli altri tre sono elencati perche' il giorno che ne arrivasse uno il
     * cancello deve accorgersene, non fermare la build per un falso allarme).
     */
    private static final Set<String> ANNOTAZIONI_DI_TEST = Set.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.params.ParameterizedTest");

    private static final String SPRING_BOOT_TEST = "org.springframework.boot.test.context.SpringBootTest";

    @Test
    @DisplayName("ogni file *Test contiene almeno un test")
    void suffissoTest_implicaAlmenoUnTest() throws IOException {
        // when: si guardano tutte le classi che surefire eseguira'
        List<String> senzaTest = new ArrayList<>();
        for (Class<?> classe : classiDiTest("Test")) {
            if (!contieneUnTest(classe)) {
                senzaTest.add(classe.getName());
            }
        }

        // then: un file di supporto chiamato *Test viene raccolto da surefire, che
        // non ci trova niente e non protesta. E' il caso di OrologioDiTest e
        // StaffDiTest, rinominati in questo stesso branch: senza questo controllo
        // la regola 16 sarebbe di nuovo solo una frase scritta
        assertThat(senzaTest)
                .as("classi *Test che non contengono nessun test: o contengono un test, o non si chiamano cosi'")
                .isEmpty();
    }

    @Test
    @DisplayName("ogni file *IT eredita da IntegrationTestBase")
    void suffissoIT_implicaLaBaseDiIntegrazione() throws IOException {
        // when: si guardano tutte le classi che failsafe eseguira'
        List<String> fuoriStandard = new ArrayList<>();
        for (Class<?> classe : classiDiTest("IT")) {
            if (!IntegrationTestBase.class.isAssignableFrom(classe)) {
                fuoriStandard.add(classe.getName());
            }
        }

        // then: un IT che non eredita dalla base o non ha nessun contesto Spring
        // (e fallisce), o se lo dichiara da solo — e allora esiste una seconda
        // configurazione di test che puo' divergere da quella vera, con in piu' un
        // secondo container Postgres da avviare
        assertThat(fuoriStandard)
                .as("classi *IT che non ereditano da IntegrationTestBase")
                .isEmpty();
    }

    @Test
    @DisplayName("nessuna classe di test dichiara @SpringBootTest per conto suo")
    void contestoSpring_dichiaratoInUnPostoSolo() throws IOException {
        // when: si cerca l'annotazione dichiarata (non ereditata) fuori dalla base
        List<String> doppioni = new ArrayList<>();
        for (Class<?> classe : classiDi(RADICE_TEST)) {
            if (classe.equals(IntegrationTestBase.class)) {
                continue;
            }
            for (Annotation annotazione : classe.getDeclaredAnnotations()) {
                if (SPRING_BOOT_TEST.equals(annotazione.annotationType().getName())) {
                    doppioni.add(classe.getName());
                }
            }
        }

        // then: le annotazioni di contesto stanno scritte una volta sola. Una
        // classe che se le riscrive sta divergendo dallo standard, ed e' esattamente
        // quello che la regola 16 dice a parole
        assertThat(doppioni)
                .as("classi che dichiarano @SpringBootTest invece di ereditarlo da IntegrationTestBase")
                .isEmpty();
    }

    @Test
    @DisplayName("nessun sorgente di produzione si chiama *Test o *IT")
    void produzione_nonUsaISuffissiDeiTest() throws IOException {
        // when: si guardano i nomi dei file sotto src/main/java
        List<String> travestiti;
        try (Stream<Path> file = Files.walk(RADICE_MAIN)) {
            travestiti = file
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("Test.java") || n.endsWith("IT.java"))
                    .toList();
        }

        // then: una classe di produzione con quel nome finirebbe fra i test eseguiti
        // da surefire o da failsafe — e failsafe la cercherebbe su un contesto che
        // non esiste. Il suffisso decide chi esegue cosa, quindi in produzione non
        // si usa
        assertThat(travestiti)
                .as("sorgenti di produzione che portano un suffisso riservato ai test")
                .isEmpty();
    }

    /** Le classi sotto {@code src/test/java} il cui nome finisce col suffisso dato. */
    private static List<Class<?>> classiDiTest(String suffisso) throws IOException {
        return classiDi(RADICE_TEST).stream()
                .filter(c -> c.getSimpleName().endsWith(suffisso))
                .toList();
    }

    /**
     * Carica le classi di primo livello dichiarate sotto la cartella indicata.
     *
     * <p>Si parte dai **file** e non da una scansione del classpath perche' la
     * regola 16 parla di nomi di file: e' il nome sul disco quello che surefire e
     * failsafe guardano per decidere se eseguire una classe.
     *
     * <p>Le classi si caricano senza inizializzarle ({@code initialize = false}):
     * qui servono solo i loro metodi e le loro annotazioni, e far girare gli
     * inizializzatori statici di mezza suite per leggerne il nome sarebbe un
     * effetto collaterale che nessuno si aspetta da un test come questo.
     */
    private static List<Class<?>> classiDi(Path radice) throws IOException {
        assertThat(radice).as("cartella dei sorgenti, cercata a partire da %s",
                Path.of("").toAbsolutePath()).exists();

        try (Stream<Path> file = Files.walk(radice)) {
            List<String> nomi = file
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> radice.relativize(p).toString()
                            .replace(".java", "")
                            .replace(File.separatorChar, '.'))
                    .toList();

            List<Class<?>> classi = new ArrayList<>();
            for (String nome : nomi) {
                try {
                    classi.add(Class.forName(nome, false, ConvenzioneNomiTest.class.getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(
                            "File " + nome + " non corrisponde a nessuna classe: "
                                    + "package e percorso devono coincidere", e);
                }
            }
            return classi;
        }
    }

    /**
     * Dice se la classe contiene almeno un metodo di test, guardando anche dentro
     * le classi annidate: con i {@code @Nested} della regola 16 i test stanno
     * quasi sempre li' dentro e non sulla classe esterna.
     */
    private static boolean contieneUnTest(Class<?> classe) {
        for (Method metodo : classe.getDeclaredMethods()) {
            for (Annotation annotazione : metodo.getDeclaredAnnotations()) {
                if (ANNOTAZIONI_DI_TEST.contains(annotazione.annotationType().getName())) {
                    return true;
                }
            }
        }
        for (Class<?> annidata : classe.getDeclaredClasses()) {
            if (contieneUnTest(annidata)) {
                return true;
            }
        }
        return false;
    }
}
