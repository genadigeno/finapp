package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the code cannot drift (`P4-TSK-004`, the {@code P0-TSK-022} pattern).
 *
 * <p>Four generated artefacts, each with one definition: the status {@code CHECK} from
 * {@link TransferStatus#sqlValueList()} (twice more on the history's from/to columns), the
 * reason {@code CHECK} from {@link FailureReason#sqlValueList()}, the monetary shape from
 * {@link MoneyColumns.ColumnNames#ddl()} — pinned verbatim so the three-column shape cannot
 * drift per table — and, the sharp one, <strong>the transition trigger's edge conditions from
 * {@link TransferStatus#permittedTransitions()}</strong>: an edge added to the machine without
 * its trigger half is a transition the aggregate permits and every other writer is refused, and
 * an edge in the trigger the machine lost is a move raw SQL can make that the domain cannot —
 * both silent without this reconciliation.
 *
 * <p>`V002` is pinned directly rather than by latest-definition derivation because it is the
 * tables' creating migration; when a later migration replaces a constraint, the
 * {@code RoleAssignmentMigrationTest} applied-history lesson applies and this test must learn
 * it.
 */
@DisplayName("transfer migration reconciliation (P4-TSK-004)")
class TransferMigrationTest {

    private static final String MIGRATION =
            "db/migration/transfers/V002__create_transfer_and_history.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = TransferStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("the reason CHECK is generated from the reason enum")
    void reasonCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (failure_reason IN (" + FailureReason.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the monetary shape is MoneyColumns.ddl(), verbatim")
    void monetaryShapeIsTheGeneratedFragment() {
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames("amount_minor", "currency", "scale").ddl());
    }

    @Test
    @DisplayName("the trigger's edge conditions are generated from permittedTransitions()")
    void triggerEdgesMatchTheMachine() {
        for (TransferStatus from : TransferStatus.values()) {
            if (from.isTerminal()) {
                // A terminal state must appear in NO edge condition as a source: the trigger
                // grants exits only to states the machine gives exits to (INV-LIFE-04).
                assertThat(migration())
                        .doesNotContain("OLD.status = '" + from.name() + "'");
                continue;
            }
            String condition = "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                    + from.permittedTransitions().stream()
                            .map(to -> "'" + to.name() + "'")
                            .collect(Collectors.joining(", "))
                    + "))";
            assertThat(migration())
                    .as("the trigger must carry %s's exact edge set", from)
                    .contains(condition);
        }
    }

    @Test
    @DisplayName("the grants are the planned set: the reversal columns and nothing more")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON transfers.transfer TO finapp_app;")
                .contains("GRANT UPDATE (status, reversal_entry_id, reversed_by, reversed_at)"
                        + " ON transfers.transfer TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON transfers.transfer_event TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON transfers")
                .doesNotContain("GRANT DELETE");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE transfers.transfer (");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                TransferMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
