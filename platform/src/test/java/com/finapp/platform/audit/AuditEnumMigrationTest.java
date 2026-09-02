package com.finapp.platform.audit;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ActorType}, {@link AuditOutcome} and the {@code CHECK} constraints that persist them
 * are one definition, and this is what holds them together.
 *
 * <p>Hermetic on purpose, exactly as {@code IdempotencyStateTest} is. {@code AuditRecordSchemaTest}
 * proves the same agreement against a real database, but that runs only under
 * {@code :platform:databaseTest}; drift introduced by adding an enum constant would sit
 * undetected until someone remembered to run it. This runs in {@code ./gradlew build}, so the
 * definitions cannot separate for longer than one compile.
 *
 * <p>The failure it prevents is quiet in a specific way: a new {@code ActorType} would compile,
 * pass every unit test, and then fail at run time on the first real action performed by an actor
 * of that kind — a constraint violation on the audit write, which under ADR-0010 rolls back the
 * action itself. A forgotten migration would therefore take down the operation it was meant to
 * record.
 */
class AuditEnumMigrationTest {

    private static final String MIGRATION = "db/migration/platform/V009__create_audit_record.sql";

    @Test
    @DisplayName("the migration's actor-type constraint lists exactly the types the enum declares")
    void actorTypesAgree() {
        assertThat(readMigration())
                .as("V009's CHECK must match ActorType exactly")
                .contains("CHECK (actor_type IN (" + ActorType.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the migration's outcome constraint lists exactly the outcomes the enum declares")
    void outcomesAgree() {
        assertThat(readMigration())
                .as("V009's CHECK must match AuditOutcome exactly")
                .contains("CHECK (outcome IN (" + AuditOutcome.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the generated lists are SQL literals, not enum names")
    void listsAreQuoted() {
        // Pinned, so a change to the generator that produced valid-looking but different SQL -
        // dropping the quotes, say - fails here rather than at the next migration.
        assertThat(ActorType.sqlValueList()).isEqualTo("'SYSTEM', 'CUSTOMER', 'EMPLOYEE', 'SERVICE'");
        assertThat(AuditOutcome.sqlValueList()).isEqualTo("'SUCCEEDED', 'FAILED', 'DENIED'");
    }

    @Test
    @DisplayName("the migration grants the application role INSERT and SELECT and nothing else")
    void theGrantIsAppendOnly() {
        // The grant is INV-HIST-03's entire enforcement. AuditImmutabilityTest proves the
        // resulting behaviour against a real database; this asserts the migration's text, so
        // that adding UPDATE to that line fails the hermetic build too rather than waiting for
        // someone to run the database suite.
        String migration = readMigration();

        assertThat(migration).contains("GRANT SELECT, INSERT ON platform.audit_record TO finapp_app;");
        assertThat(migration)
                .as("no UPDATE, DELETE or ALL may be granted on the audit table")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }

    @Test
    @DisplayName("the migration names the invariants it enforces")
    void migrationNamesItsInvariants() {
        // DATA_MIGRATIONS.md §3.4: a migration touching money, postings, evidence or audit
        // states which invariants it affects. A reviewer should not have to infer it.
        assertThat(readMigration())
                .contains("INV-HIST-03")
                .contains("INV-AUD-01")
                .contains("INV-AUD-02");
    }

    private static String readMigration() {
        try (InputStream source =
                AuditEnumMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            // Failing loudly rather than skipping: a test that cannot find the migration and
            // quietly passes would report agreement without comparing anything.
            assertThat(source).as("migration %s must be on the classpath", MIGRATION).isNotNull();
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }
}
