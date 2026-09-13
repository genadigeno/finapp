package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal schema and its one-definition sources agree (`P3-TSK-005`) — enums, the
 * {@code MoneyColumns} monetary shape, and the bounds that mirror platform columns.
 */
@DisplayName("the journal schema and its definitions agree (P3-TSK-005)")
class JournalEntryMigrationTest {

    private static final String MIGRATION =
            "db/migration/ledger/V004__create_journal_entry_and_line.sql";

    @Test
    @DisplayName("every enum's CHECK lists exactly the values the enum declares")
    void everyValueListMatchesItsEnum() {
        assertThat(migration())
                .contains("CHECK (entry_type IN (" + JournalEntryType.sqlValueList() + "))")
                .contains("CHECK (direction IN (" + Direction.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the monetary columns are MoneyColumns' generated shape, verbatim")
    void theMonetaryShapeIsGenerated() {
        // One definition (ADR-0003): a migration declaring the triple by hand is how one
        // table quietly gets a nullable currency or an unbounded scale.
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames("amount_minor", "currency", "scale")
                                .ddl());
    }

    @Test
    @DisplayName("the bounds mirror the platform columns they must stay consistent with")
    void theBoundsMirrorTheirSources() {
        assertThat(migration())
                .as("the reason bound is AuditRecord's own, or the two drift")
                .contains("length(reason) BETWEEN 1 AND " + AuditRecord.MAX_REASON_LENGTH);
        assertThat(migration())
                .as("the actor bound mirrors audit_record's")
                .contains("length(actor_id) BETWEEN 1 AND " + Actor.MAX_ID_LENGTH);
        assertThat(migration())
                .as("the correlation bounds are the identifier types' own")
                .contains("length(correlation_id) BETWEEN 1 AND " + CorrelationId.MAX_LENGTH)
                .contains("length(causation_id) BETWEEN 1 AND " + CorrelationId.MAX_LENGTH);
    }

    @Test
    @DisplayName("an adjustment's reason is required by CHECK, as an implication")
    void theAdjustmentReasonRuleIsAnImplication() {
        // INV-REV-04 at the schema - and an implication rather than an equality, so
        // P3-TSK-016 stays free to decide whether a reversal carries one.
        assertThat(migration())
                .contains(
                        "CHECK (entry_type <> '" + JournalEntryType.ADJUSTMENT.name()
                                + "' OR reason IS NOT NULL)");
    }

    @Test
    @DisplayName("the grants are SELECT and INSERT and nothing else, on both tables")
    void theGrantsAreTheHeadline() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON ledger.journal_entry TO finapp_app")
                .contains("GRANT SELECT, INSERT ON ledger.journal_line TO finapp_app")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }

    @Test
    @DisplayName("the three trigger layers are wired: balance, line count, append-only")
    void theTriggersAreWired() {
        // The artefact half; the behavioural half - refused at COMMIT against a live
        // database, driven by raw SQL that never touches the domain - is
        // JournalPostingDatabaseTest's.
        assertThat(migration())
                .contains("CREATE CONSTRAINT TRIGGER journal_entry_balances")
                .contains("CREATE CONSTRAINT TRIGGER journal_entry_has_lines")
                .contains("CREATE TRIGGER journal_entry_is_append_only")
                .contains("CREATE TRIGGER journal_line_is_append_only");
        // Deferral is load-bearing twice: an immediate trigger would refuse the first line
        // of every legal entry, and only at COMMIT is the entry whole.
        assertThat(migration().split("DEFERRABLE INITIALLY DEFERRED", -1))
                .as("both constraint triggers defer to commit")
                .hasSize(3);
    }

    @Test
    @DisplayName("no cross-schema foreign key, and the account reference is a real one")
    void theForeignKeysPointTheRightWay() {
        assertThat(migration())
                .as("same schema, same module: a line naming a nonexistent account is corrupt"
                        + " on arrival, so this FK is right where a cross-module one is wrong")
                .contains("REFERENCES ledger.ledger_account (id)")
                .doesNotContain("REFERENCES accounts.")
                .doesNotContain("REFERENCES party.");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration())
                .contains("CREATE TABLE ledger.journal_entry")
                .contains("CREATE TABLE ledger.journal_line");
    }

    private static String migration() {
        try (InputStream migration =
                JournalEntryMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
