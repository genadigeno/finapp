package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Platform's definer function, read from its migration (`P15-TSK-005`). Text pins: they hold the words, and
 * {@code SchemaVersionReadDatabaseTest} runs them, at the exit's database pass.
 */
@DisplayName("the guard's read is one definer function, reading only applied versions (P15-TSK-005)")
class AppliedVersionsMigrationTest {

    private static final String FUNCTION = "platform.applied_migration_versions()";

    private static String sql;

    @BeforeAll
    static void read() throws IOException {
        Path directory = RepositoryPaths.locate("platform/build.gradle.kts").getParent().resolve("src/main/resources/db/migration/platform");
        try (Stream<Path> files = Files.list(directory)) {
            Path migration = files.filter(file -> file.getFileName().toString().endsWith("__the_readiness_read_of_applied_versions.sql"))
                    .findFirst()
                    .orElseThrow();
            sql = Files.readString(migration).replaceAll("(?m)^\\s*--.*$", "").replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("what the application reads is what the migration defines")
    void theReadNamesTheFunction() {
        assertThat(JdbcAppliedSchemaVersions.READ).contains(FUNCTION).contains("schema_name").contains("version");
        assertThat(sql).contains("CREATE FUNCTION " + FUNCTION + " RETURNS TABLE (schema_name text, version text)");
    }

    @Test
    @DisplayName("a definer function, with its search path pinned and names quoted")
    void aDefinerWithAPinnedPath() {
        assertThat(sql).contains("SECURITY DEFINER").contains("SET search_path = pg_catalog, pg_temp");
        assertThat(sql).as("the schema's name quoted as an identifier and a literal").contains("%I.flyway_schema_history").contains("%L::text");
    }

    @Test
    @DisplayName("execute to the application role, and to nobody else")
    void onlyTheApplicationRole() {
        assertThat(sql)
                .contains("REVOKE ALL ON FUNCTION " + FUNCTION + " FROM PUBLIC;")
                .contains("GRANT EXECUTE ON FUNCTION " + FUNCTION + " TO finapp_app;");
        assertThat(sql.split("GRANT ", -1)).as("that one grant").hasSize(2);
    }

    @Test
    @DisplayName("only versions that succeeded, of schemas found in the catalogue, skipping a history it cannot read")
    void onlySuccessfulVersions() {
        assertThat(sql)
                .contains("WHERE h.success AND h.version IS NOT NULL")
                .contains("c.relname = 'flyway_schema_history'")
                .contains("has_table_privilege(c.oid, 'SELECT')");
    }

    @Test
    @DisplayName("it writes nothing")
    void itWritesNothing() {
        assertThat(sql.toUpperCase(java.util.Locale.ROOT)).doesNotContain("INSERT ").doesNotContain("UPDATE ").doesNotContain("DELETE ");
    }
}
