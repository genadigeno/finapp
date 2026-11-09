package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * What the build embeds is what is really on the classpath and in the repository (`P15-TSK-005`): a schema missing from the
 * embedded file is a schema the guard never looks at.
 */
@DisplayName("the expected versions are the build's, from the migrations themselves (P15-TSK-005)")
class ExpectedSchemaVersionsTest {

    private static final Pattern SCRIPT = Pattern.compile("db/migration/([^/]+)/V([0-9]+(?:[._][0-9]+)*)__[^/]*\\.sql$");

    private static void keepHighest(SortedMap<String, SchemaVersion> highest, String schema, String version) {
        highest.merge(schema, SchemaVersion.parse(version), (one, other) -> one.compareTo(other) >= 0 ? one : other);
    }

    @Test
    @DisplayName("the embedded versions equal a scan of the classpath's own migrations")
    void theEmbeddedFileIsTheClasspath() throws IOException {
        SortedMap<String, SchemaVersion> scanned = new TreeMap<>();
        for (Resource script : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*/V*.sql")) {
            Matcher matched = SCRIPT.matcher(script.getURL().toString());
            assertThat(matched.find()).as(script.toString()).isTrue();
            keepHighest(scanned, matched.group(1), matched.group(2));
        }
        assertThat(ExpectedSchemaVersions.embedded().versions()).isEqualTo(scanned);
    }

    @Test
    @DisplayName("and a walk of the repository: no module's migrations are missing from what the application ships")
    void theEmbeddedFileIsTheRepository() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        SortedMap<String, SchemaVersion> walked = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path directory = module.resolve("src/main/resources/db/migration").resolve(module.getFileName());
                if (Files.isDirectory(directory)) {
                    try (Stream<Path> files = Files.list(directory)) {
                        files.forEach(file -> {
                            Matcher name = Pattern.compile("V([0-9]+(?:[._][0-9]+)*)__.*\\.sql").matcher(file.getFileName().toString());
                            assertThat(name.matches()).as(file.toString()).isTrue();
                            keepHighest(walked, module.getFileName().toString(), name.group(1));
                        });
                    }
                }
            }
        }
        assertThat(walked).as("the walk found the schemas").containsKeys("ledger", "platform").hasSizeGreaterThanOrEqualTo(21);
        assertThat(ExpectedSchemaVersions.embedded().versions()).isEqualTo(walked);
    }

    @Test
    @DisplayName("an empty expectation is refused: no expectation must never read as nothing is older")
    void anEmptyExpectationIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> ExpectedSchemaVersions.parse(new StringReader("")));
    }

    @Test
    @DisplayName("an embedded value that is not a version is refused")
    void aBadValueIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> ExpectedSchemaVersions.parse(new StringReader("ledger=latest\n")));
    }

    @Test
    @DisplayName("behind is only behind: at, past and absent each answer as they should, as versions")
    void behindIsOnlyBehind() throws IOException {
        ExpectedSchemaVersions expected = ExpectedSchemaVersions.parse(new StringReader("a=10\nb=9\nc=033\n"));
        assertThat(expected.behindIn(Map.of("a", v("10"), "b", v("9"), "c", v("33")))).as("exactly at").isEmpty();
        assertThat(expected.behindIn(Map.of("a", v("11"), "b", v("10"), "c", v("100")))).as("past, in every one").isEmpty();
        assertThat(expected.behindIn(Map.of("a", v("9"), "b", v("9"), "c", v("33")))).as("9 is behind 10").hasSize(1).first().asString().startsWith("a is at 9");
        assertThat(expected.behindIn(Map.of("a", v("10"), "c", v("33")))).as("no applied version").hasSize(1).first().asString().startsWith("b has no");
        assertThat(expected.behindIn(Map.of("a", v("10"), "b", v("9"), "c", v("33"), "added-later", v("1")))).as("a schema this build knows nothing of").isEmpty();
    }

    private static SchemaVersion v(String text) {
        return SchemaVersion.parse(text);
    }
}
