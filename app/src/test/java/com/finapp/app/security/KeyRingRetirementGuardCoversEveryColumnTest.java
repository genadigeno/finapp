package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.KeyRing;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

/**
 * The retirement check is only as good as its list of columns, so this reads the migrations and the bean graph, rather than
 * trusting the table in {@code KeyRingRetirementGuard} (`P15-TSK-019`).
 */
@DisplayName("the retirement guard's table is the schema's and the beans' (P15-TSK-019)")
class KeyRingRetirementGuardCoversEveryColumnTest {

    /** A column named as a key version in a CREATE TABLE: the plain one, and the two kyc spell with a prefix. */
    private static final Pattern STAMP_COLUMN =
            Pattern.compile("(?m)^\\s+([a-z_]*key_version)\\s+(?:integer|int)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)?\\s+([a-z_]+\\.[a-z_]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ALTER TABLE\\s+([a-z_]+\\.[a-z_]+)\\s+ADD COLUMN(?: IF NOT EXISTS)?\\s+([a-z_]*key_version)\\s+(?:integer|int)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every key-version column in any migration is listed by the guard, and the guard lists only real columns")
    void everyStampedColumnIsListedAndEveryListingIsReal() throws IOException {
        Set<String> inMigrations = new TreeSet<>();
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        try (Stream<Path> modules = Files.list(root)) {
            modules.filter(Files::isDirectory)
                    .map(module -> module.resolve("src/main/resources/db/migration"))
                    .filter(Files::isDirectory)
                    .forEach(migrations -> collectStampedColumns(migrations, inMigrations));
        }
        assertThat(inMigrations).as("the reader found the columns it exists to read").contains("kyc.kyc_document.key_version");

        Set<String> listed = new TreeSet<>();
        KeyRingRetirementGuard.STAMPED_COLUMNS.values().forEach(listed::addAll);

        assertThat(listed).as("the guard lists exactly the key-version columns the schema holds").isEqualTo(inMigrations);
        assertThat(KeyRingRetirementGuard.NOT_READABLE_BY_THE_APPLICATION).as("the exception is a column the guard lists").isSubsetOf(listed);
    }

    @Test
    @DisplayName("every column's census, at its timeout, still leaves the rest of startup inside the deployment's startup probe")
    void theCensusFitsTheStartupProbe() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        String deployment = Files.readString(root.resolve("deploy/base/deployment.yaml"), StandardCharsets.UTF_8);
        Matcher probe = Pattern.compile("startupProbe:(.*?)periodSeconds:\\s*(\\d+).*?failureThreshold:\\s*(\\d+)", Pattern.DOTALL).matcher(deployment);
        assertThat(probe.find()).as("the deployment's startup probe was read").isTrue();
        int probeSeconds = Integer.parseInt(probe.group(2)) * Integer.parseInt(probe.group(3));

        Set<String> listed = new TreeSet<>();
        KeyRingRetirementGuard.STAMPED_COLUMNS.values().forEach(listed::addAll);
        int everyColumnSeconds = listed.size() * KeyRingRetirementGuard.CHECK_TIMEOUT_SECONDS;
        // Sixty seconds for the rest of the application's startup: a figure, not a measurement, kept beside what it spends.
        assertThat(everyColumnSeconds + 60).as("%d columns at %d s, inside the %d s startup probe", listed.size(), KeyRingRetirementGuard.CHECK_TIMEOUT_SECONDS, probeSeconds)
                .isLessThanOrEqualTo(probeSeconds);
    }

    @Test
    @DisplayName("the document key's two cipher classes are built from one ring object: the second reads no key of its own")
    void theDocumentKeysTwoCiphersShareOneRing() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        String screening = Files.readString(
                root.resolve("app/src/main/java/com/finapp/app/kyc/CounterpartyScreeningBeans.java"), StandardCharsets.UTF_8);
        assertThat(screening).contains("KeyRing documentKeyRing").doesNotContain("finapp.doc.key");
    }

    @Test
    @DisplayName("every KeyRing bean is listed by the guard, and the guard lists no ring nothing builds")
    void everyRingBeanIsListed() {
        Set<String> beans = new TreeSet<>();
        for (Class<?> type : ConfinedCredentialVariablesTest.applicationClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && method.getReturnType() == KeyRing.class) {
                    beans.add(method.getName());
                }
            }
        }
        assertThat(beans).as("the walk found the rings it exists to find").contains("documentKeyRing", "mfaKeyRing");
        assertThat(beans).isEqualTo(new TreeSet<>(KeyRingRetirementGuard.STAMPED_COLUMNS.keySet()));
    }

    private static void collectStampedColumns(Path migrations, Set<String> columns) {
        try (Stream<Path> files = Files.walk(migrations)) {
            files.filter(file -> file.toString().endsWith(".sql")).forEach(file -> collect(file, columns));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void collect(Path file, Set<String> columns) {
        String sql;
        try {
            // Comments out first: a parenthesis in one would unbalance the walk, and a column mentioned in one would count.
            sql = Files.readString(file, StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher added = ADD_COLUMN.matcher(sql);
        while (added.find()) {
            columns.add(added.group(1).toLowerCase(Locale.ROOT) + "." + added.group(2).toLowerCase(Locale.ROOT));
        }
        Matcher tables = CREATE_TABLE.matcher(sql);
        while (tables.find()) {
            String table = tables.group(1).toLowerCase(Locale.ROOT);
            int bodyStart = tables.end();
            int bodyEnd = endOfCreate(sql, bodyStart);
            Matcher stamps = STAMP_COLUMN.matcher(sql.substring(bodyStart, bodyEnd));
            while (stamps.find()) {
                columns.add(table + "." + stamps.group(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The statement's end: the semicolon after its balancing parenthesis, comments and strings ignored by neither but sufficient here. */
    private static int endOfCreate(String sql, int start) {
        int depth = 1;
        for (int i = start; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return sql.length();
    }
}
