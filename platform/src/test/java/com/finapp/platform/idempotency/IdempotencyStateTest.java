package com.finapp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IdempotencyState} and the {@code CHECK} constraint that persists it are one
 * definition, and this is what holds them together.
 *
 * <p>Hermetic on purpose. {@code IdempotencyRecordSchemaTest} proves the same agreement against
 * a real database, but that runs only under {@code :platform:databaseTest}. Drift introduced by
 * adding an enum constant would then sit undetected until someone remembered to run it. This
 * check runs in {@code ./gradlew build}, so the two definitions cannot separate for longer than
 * one compile.
 */
class IdempotencyStateTest {

    private static final String MIGRATION =
            "db/migration/platform/V002__create_idempotency_record.sql";

    @Test
    @DisplayName("the migration's state constraint lists exactly the states the enum declares")
    void migrationAndEnumAgree() {
        String migration = readMigration();

        // The enum generates the literal list; the assertion is that the migration contains
        // precisely that text. Adding a constant without a migration fails here, as does
        // reordering, because the persisted set is a schema contract rather than a Java detail.
        assertThat(migration)
                .as("V002's CHECK constraint must match IdempotencyState exactly")
                .contains("CHECK (state IN (" + IdempotencyState.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the generated list is SQL literals, not enum names")
    void sqlValueListIsQuoted() {
        assertThat(IdempotencyState.sqlValueList())
                .isEqualTo("'IN_PROGRESS', 'COMPLETED', 'FAILED'");
    }

    @Test
    @DisplayName("only IN_PROGRESS is non-terminal")
    void onlyInProgressIsNonTerminal() {
        // The distinction the wrapper turns on: a terminal record is replayed, a non-terminal
        // one is waited on. Getting it wrong for FAILED would re-execute a command that was
        // definitively rejected.
        assertThat(IdempotencyState.IN_PROGRESS.isTerminal()).isFalse();
        assertThat(IdempotencyState.COMPLETED.isTerminal()).isTrue();
        assertThat(IdempotencyState.FAILED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("the migration names the invariants it enforces")
    void migrationNamesItsInvariants() {
        // DATA_MIGRATIONS.md §3.4: a migration touching money, postings, evidence or audit
        // states which invariants it affects. A reviewer should not have to infer it.
        assertThat(readMigration()).contains("INV-IDEM-01").contains("INV-IDEM-03");
    }

    private static String readMigration() {
        try (InputStream source =
                IdempotencyStateTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            // Failing loudly rather than skipping: a test that cannot find the migration and
            // quietly passes would report that the two definitions agree without comparing them.
            assertThat(source).as("migration %s must be on the classpath", MIGRATION).isNotNull();
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }
}
