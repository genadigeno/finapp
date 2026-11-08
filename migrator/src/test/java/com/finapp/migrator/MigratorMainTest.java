package com.finapp.migrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the entrypoint refuses before it opens a connection, and what it never prints ({@code P15-TSK-004}). */
@DisplayName("the migrator's entrypoint refuses before it connects (P15-TSK-004)")
class MigratorMainTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    @TempDir
    Path sql;

    private static final Map<String, String> LOCAL = Map.of(
            "FINAPP_DB_URL", "jdbc:postgresql://127.0.0.1:1/finapp", "FINAPP_DB_MIGRATOR_PASSWORD", "a-throwaway-password");

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void aDirectoryForEverySchema(boolean withAScript) throws IOException {
        for (String schema : MigrationPlan.schemasInBuildOrder()) {
            Files.createDirectories(sql.resolve(schema));
            if (withAScript) {
                Files.writeString(sql.resolve(schema).resolve("V001__x.sql"), "SELECT 1;\n");
            }
        }
    }

    @Test
    @DisplayName("any argument is refused: there is no second thing this process can be asked to do, clean included")
    void takesNoArguments() {
        assertThat(MigratorMain.run(new String[] {"clean"}, LOCAL, sql, out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("result=REFUSED");
    }

    @Test
    @DisplayName("no URL, no run: exit 12, and the code a FailJob rule can name")
    void noUrl() {
        ExitCode exit = MigratorMain.run(new String[0], Map.of("FINAPP_DB_MIGRATOR_PASSWORD", "x"), sql, out);
        assertThat(exit).isEqualTo(ExitCode.REFUSED);
        assertThat(exit.getCode()).isEqualTo(12);
    }

    @Test
    @DisplayName("a schema with no directory is a refused image, not 'no migrations to apply'")
    void aMissingDirectoryIsRefused() {
        assertThat(MigratorMain.run(new String[0], LOCAL, sql.resolve("nowhere"), out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("cannot read");
    }

    @Test
    @DisplayName("a schema's directory with no scripts in it is refused too: Flyway would call that nothing to do")
    void anEmptyDirectoryIsRefused() throws IOException {
        aDirectoryForEverySchema(false);
        assertThat(MigratorMain.run(new String[0], LOCAL, sql, out)).isEqualTo(ExitCode.REFUSED);
        assertThat(output()).contains("no migrations in");
    }

    @Test
    @DisplayName("a database that is not there is transient, and its failure prints neither the password nor the URL's value")
    void anUnreachableDatabaseIsTransientAndPrintsNoSecret() throws IOException {
        aDirectoryForEverySchema(true);
        ExitCode exit = MigratorMain.run(new String[0], LOCAL, sql, out);
        assertThat(exit).as("nothing listens on port 1").isEqualTo(ExitCode.TRANSIENT);
        assertThat(output()).doesNotContain("a-throwaway-password").doesNotContain("127.0.0.1:1");
    }
}
