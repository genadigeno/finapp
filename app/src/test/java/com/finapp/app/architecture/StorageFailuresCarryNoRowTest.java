package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.crossborder.CrossborderStorageException;
import com.finapp.kyc.KycStorageException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A storage failure in a schema holding rows above {@code INTERNAL} carries no driver exception (the Phase 9 to 10
 * transition gate; {@code DatabaseFailure}'s rule, INV-RAIL-03, INV-AUD-02).
 *
 * <h2>Why a build rule</h2>
 *
 * <p>PostgreSQL puts the refused row in a constraint violation's {@code DETAIL} - <em>"Failing row contains
 * (...)"</em> - and the driver puts the DETAIL in the {@code SQLException}'s message. The crossborder stores attached
 * that exception as the cause of every {@code CrossborderStorageException}, so a provider-returned IBAN-shaped
 * destination reference firing {@code beneficiary_destination_reference_no_instrument_shape} reached
 * {@code ApiErrorHandler}'s log with the bank identifier in it - the constraint protecting INV-RAIL-03 leaking the
 * value it refused. kyc's exception was built without a cause constructor from the start; crossborder's now is too,
 * and these rules keep both that way:
 *
 * <ul>
 *   <li><strong>No constructor takes a {@link Throwable}</strong> - read reflectively, so neither exception can grow
 *       a cause again without failing here.
 *   <li><strong>No module source hands one a second argument or calls {@code initCause}</strong> - read from the
 *       sources, the {@code SendPermitsAreTheDatabasesTest} idiom, so a wrapper or a subclass cannot route the cause
 *       around the constructor rule.
 * </ul>
 */
@Tag("architecture")
@DisplayName("storage failures carry no row (the Phase 9 to 10 transition)")
class StorageFailuresCarryNoRowTest {

    /** Each guarded exception and the module whose sources must never hand it a cause. */
    private static final Map<Class<? extends RuntimeException>, String> GUARDED =
            Map.of(CrossborderStorageException.class, "crossborder", KycStorageException.class, "kyc");

    @Test
    @DisplayName("neither exception has a constructor taking a cause")
    void noConstructorTakesACause() {
        for (Class<? extends RuntimeException> type : GUARDED.keySet()) {
            assertThat(causeConstructorsOf(type))
                    .as(type.getSimpleName() + " must not accept a cause: a SQLException carries the refused row")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("no module source hands either exception a second argument or calls initCause on it")
    void noSourceAttachesACause() {
        for (Map.Entry<Class<? extends RuntimeException>, String> guarded : GUARDED.entrySet()) {
            String name = guarded.getKey().getSimpleName();
            List<Path> sources = sourcesOf(guarded.getValue());
            assertThat(sources).as("the rule read real sources of " + guarded.getValue()).isNotEmpty();
            List<String> violations = new ArrayList<>();
            int constructions = 0;
            for (Path source : sources) {
                String text = read(source);
                constructions += constructionsOf(text, name).size();
                for (String violation : violationsIn(text, name)) {
                    violations.add(source.getFileName() + ": " + violation);
                }
            }
            assertThat(constructions).as(name + " is constructed somewhere - the rule is not vacuous").isPositive();
            assertThat(violations).as(name + " handed a cause").isEmpty();
        }
    }

    @Test
    @DisplayName("the rules are not vacuous: each planted violation is refused")
    void plantedViolationsAreRefused() {
        // The shape every crossborder store had before the transition gate.
        assertThat(violationsIn("throw new CrossborderStorageException(DatabaseFailure.describe(\"locking payment \" + id,"
                + " failure), failure);", "CrossborderStorageException")).hasSize(1);
        assertThat(violationsIn("throw new KycStorageException(\"could not read\", failure);", "KycStorageException"))
                .hasSize(1);
        assertThat(violationsIn("KycStorageException e = new KycStorageException(m); e.initCause(failure);",
                "KycStorageException")).hasSize(1);
        // The accepted shapes: commas inside the describe call and inside a string are not a second argument.
        assertThat(violationsIn("throw new CrossborderStorageException(DatabaseFailure.describe(\"a, b\", failure));",
                "CrossborderStorageException")).isEmpty();
        assertThat(violationsIn("new CrossborderStorageException(\"refused as a duplicate, none visible; retry\")",
                "CrossborderStorageException")).isEmpty();
        // The constructor rule, over a planted exception of the old shape.
        assertThat(causeConstructorsOf(PlantedStorageException.class)).hasSize(1);
    }

    // -----------------------------------------------------------------

    /** The pre-transition {@code CrossborderStorageException}, planted. */
    static final class PlantedStorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PlantedStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static List<Constructor<?>> causeConstructorsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(Throwable.class::isAssignableFrom))
                .toList();
    }

    private static List<String> violationsIn(String source, String exception) {
        List<String> violations = new ArrayList<>();
        for (String arguments : constructionsOf(source, exception)) {
            if (topLevelArguments(arguments) > 1) {
                violations.add("new " + exception + "(" + arguments + ")");
            }
        }
        if (source.contains(exception) && Pattern.compile("\\.initCause\\(").matcher(source).find()) {
            violations.add("initCause beside " + exception);
        }
        return violations;
    }

    /** Each construction's argument text, up to its balancing parenthesis. */
    private static List<String> constructionsOf(String source, String exception) {
        List<String> found = new ArrayList<>();
        Matcher construction = Pattern.compile("new\\s+" + Pattern.quote(exception) + "\\s*\\(").matcher(source);
        while (construction.find()) {
            int depth = 1;
            int at = construction.end();
            boolean quoted = false;
            while (at < source.length() && depth > 0) {
                char c = source.charAt(at);
                if (c == '"' && source.charAt(at - 1) != '\\') {
                    quoted = !quoted;
                } else if (!quoted && c == '(') {
                    depth++;
                } else if (!quoted && c == ')') {
                    depth--;
                }
                at++;
            }
            found.add(source.substring(construction.end(), at - 1));
        }
        return found;
    }

    private static int topLevelArguments(String arguments) {
        int depth = 0;
        int count = arguments.isBlank() ? 0 : 1;
        boolean quoted = false;
        for (int at = 0; at < arguments.length(); at++) {
            char c = arguments.charAt(at);
            if (c == '"' && (at == 0 || arguments.charAt(at - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[' || c == '{')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']' || c == '}')) {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                count++;
            }
        }
        return count;
    }

    private static List<Path> sourcesOf(String module) {
        Path root = repositoryRoot().resolve(module).resolve("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + source, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
