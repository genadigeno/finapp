package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V002`'s machine and coherence against raw SQL (`P4-TSK-004`, ADR-0044 at
 * {@code DB-CONSTRAINT} rank) — the writers the aggregate never sees. Every probe here rolls
 * back: the schema is the subject, and the shared container's other suites must find nothing
 * planted.
 */
@Tag("database")
@DisplayName("the transfer schema binds every writer (P4-TSK-004, ADR-0044)")
class TransferSchemaDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION = "23503";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** The columns the reversal legitimately touches — the granted UPDATE set. */
    private static final List<String> REVERSAL_COLUMNS =
            List.of("status", "reversal_entry_id", "reversed_by", "reversed_at");

    @Test
    @DisplayName("raw SQL cannot store an incoherent row: status, reason, entry, triple and pair"
            + " each judged by the schema")
    void rawSqlCannotStoreAnIncoherentRow() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                // Positive controls first, so every refusal below is the constraint named and
                // not a broken INSERT: the two coherent shapes P4-TSK-005 will actually write.
                assertThatCode(() -> insertTransfer(app, coherent(Shape.COMPLETED)))
                        .doesNotThrowAnyException();
                assertThatCode(() -> insertTransfer(app, coherent(Shape.SELF_REFUSAL)))
                        .doesNotThrowAnyException();

                refused(app, "an unknown status", CHECK_VIOLATION,
                        coherent(Shape.COMPLETED).with(r -> r.status = "SETTLED"));
                refused(app, "an unknown reason", CHECK_VIOLATION,
                        coherent(Shape.FAILED).with(r -> r.failureReason = "BAD_MOON"));
                refused(app, "FAILED without its reason", CHECK_VIOLATION,
                        coherent(Shape.FAILED).with(r -> r.failureReason = null));
                refused(app, "a reason on a completed transfer", CHECK_VIOLATION,
                        coherent(Shape.COMPLETED).with(r -> r.failureReason = "INSUFFICIENT_FUNDS"));
                refused(app, "COMPLETED without its entry", CHECK_VIOLATION,
                        coherent(Shape.COMPLETED).with(r -> r.journalEntryId = null));
                refused(app, "an entry on a refusal that posted nothing", CHECK_VIOLATION,
                        coherent(Shape.FAILED).with(r -> r.journalEntryId = IDS.next()));
                refused(app, "a reversal missing its actor", CHECK_VIOLATION,
                        coherent(Shape.REVERSED).with(r -> r.reversedBy = null));
                refused(app, "a completed self-transfer", CHECK_VIOLATION,
                        coherent(Shape.COMPLETED).with(r -> r.destination = r.source));
                refused(app, "SELF_TRANSFER naming two different accounts", CHECK_VIOLATION,
                        coherent(Shape.SELF_REFUSAL).with(r -> r.destination = IDS.next()));
                refused(app, "a zero amount", CHECK_VIOLATION,
                        coherent(Shape.COMPLETED).with(r -> r.amountMinor = 0));
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("a stored transfer moves only along the machine's edges, and the reversal is"
            + " the app role's one legitimate update — the positive control")
    void theTriggerPermitsOnlyTheMachinesEdges() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                Row completed = coherent(Shape.COMPLETED);
                insertTransfer(app, completed);
                Row failed = coherent(Shape.FAILED);
                insertTransfer(app, failed);

                // COMPLETED -> FAILED is not an edge; neither is an idle same-status touch.
                refusedUpdate(app, "COMPLETED -> FAILED", completed.id,
                        "SET status = 'FAILED'", "machine's edges");
                refusedUpdate(app, "an idle COMPLETED -> COMPLETED touch", completed.id,
                        "SET status = 'COMPLETED'", "machine's edges");
                // A terminal has no exit whatever the target (INV-LIFE-04).
                refusedUpdate(app, "FAILED -> COMPLETED", failed.id,
                        "SET status = 'COMPLETED'", "machine's edges");

                // The positive control: the reversal, through exactly the granted columns, as
                // the app role — proving the trigger's edge, the coherence CHECKs and the
                // narrowed grant sufficient for the one legitimate update all at once.
                try (PreparedStatement reverse = app.prepareStatement(
                        "UPDATE transfers.transfer SET status = 'REVERSED',"
                                + " reversal_entry_id = ?, reversed_by = ?, reversed_at = ?"
                                + " WHERE id = ?")) {
                    reverse.setObject(1, IDS.next());
                    reverse.setObject(2, IDS.next());
                    reverse.setTimestamp(3, Timestamp.from(Instant.now(CLOCK)));
                    reverse.setObject(4, completed.id);
                    assertThat(reverse.executeUpdate()).isEqualTo(1);
                }
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the record is frozen outside the reversal columns for EVERY writer — the"
            + " migrator's own update is refused by the trigger")
    void theFrozenPayloadBindsEveryWriter() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                Connection migrator = DatabaseRoles.migrator()) {
            app.setAutoCommit(false);
            migrator.setAutoCommit(false);
            try {
                Row completed = coherent(Shape.COMPLETED);
                insertTransfer(app, completed);
                app.commit(); // Visible to the migrator's connection; removed in finally.

                assertThatThrownBy(() -> {
                            try (PreparedStatement update = migrator.prepareStatement(
                                    "UPDATE transfers.transfer"
                                            + " SET amount_minor = amount_minor + 1"
                                            + " WHERE id = ?")) {
                                update.setObject(1, completed.id);
                                update.executeUpdate();
                            }
                        })
                        .as("what the customer was told happened is what stays recorded,"
                                + " whoever asks")
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", RAISED)
                        .hasMessageContaining("immutable outside the reversal columns");
                migrator.rollback();
            } finally {
                // The one committed probe row leaves with the migrator, the only role that may
                // delete — the shared container's other suites must find nothing planted.
                migrator.rollback();
                try (PreparedStatement cleanup = migrator.prepareStatement(
                        "DELETE FROM transfers.transfer WHERE reference = ?")) {
                    cleanup.setString(1, PROBE_REFERENCE);
                    cleanup.executeUpdate();
                }
                migrator.commit();
            }
        }
    }

    @Test
    @DisplayName("the app role's UPDATE is exactly the reversal columns, and DELETE exists"
            + " nowhere — the column list derived, not remembered")
    void theGrantsAreTheNarrowedSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                Row completed = coherent(Shape.COMPLETED);
                insertTransfer(app, completed);

                // Derived from information_schema (the P0-TST-007 idiom), so a column added
                // later is swept without anyone remembering.
                List<String> frozen = new ArrayList<>(columnsOf(app, "transfer"));
                frozen.removeAll(REVERSAL_COLUMNS);
                assertThat(frozen).as("the derived sweep has subjects").isNotEmpty();
                for (String column : frozen) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(() -> {
                                try (PreparedStatement update = app.prepareStatement(
                                        "UPDATE transfers.transfer SET " + column + " = "
                                                + column + " WHERE id = ?")) {
                                    update.setObject(1, completed.id);
                                    update.executeUpdate();
                                }
                            })
                            .as("the application role must hold no UPDATE on %s", column)
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback(before);
                }

                for (String statement : List.of(
                        "DELETE FROM transfers.transfer WHERE id = ?",
                        "DELETE FROM transfers.transfer_event WHERE transfer_id = ?")) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(() -> {
                                try (PreparedStatement delete = app.prepareStatement(statement)) {
                                    delete.setObject(1, completed.id);
                                    delete.executeUpdate();
                                }
                            })
                            .as("a transfer's end is a status, never an absence")
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback(before);
                }

                for (String column : columnsOf(app, "transfer_event")) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(() -> {
                                try (PreparedStatement update = app.prepareStatement(
                                        "UPDATE transfers.transfer_event SET " + column + " = "
                                                + (column.equals("id") ? "DEFAULT" : column)
                                                + " WHERE transfer_id = ?")) {
                                    update.setObject(1, completed.id);
                                    update.executeUpdate();
                                }
                            })
                            .as("the history is append-only: no UPDATE on %s", column)
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback(before);
                }
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("one transfer per posted entry, and history is anchored to a real transfer with"
            + " a known vocabulary")
    void uniquenessAndHistoryAnchoring() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                Row first = coherent(Shape.COMPLETED);
                insertTransfer(app, first);

                Savepoint before = app.setSavepoint();
                assertThatThrownBy(() -> insertTransfer(app,
                                coherent(Shape.COMPLETED)
                                        .with(r -> r.journalEntryId = first.journalEntryId)))
                        .as("one transfer per posted entry, whichever instance wrote it")
                        .isInstanceOf(SQLException.class)
                        .extracting(f -> ((SQLException) f).getSQLState())
                        .isEqualTo(UNIQUE_VIOLATION);
                app.rollback(before);

                // The history's positive control, and its two refusals: an event about a
                // transfer that does not exist is not evidence of anything, and an unknown
                // status is outside the machine's vocabulary.
                assertThatCode(() -> insertEvent(app, first.id, "INITIATED", "COMPLETED"))
                        .doesNotThrowAnyException();
                before = app.setSavepoint();
                assertThatThrownBy(() -> insertEvent(app, IDS.next(), "INITIATED", "COMPLETED"))
                        .isInstanceOf(SQLException.class)
                        .extracting(f -> ((SQLException) f).getSQLState())
                        .isEqualTo(FK_VIOLATION);
                app.rollback(before);
                before = app.setSavepoint();
                assertThatThrownBy(() -> insertEvent(app, first.id, "INITIATED", "SETTLED"))
                        .isInstanceOf(SQLException.class)
                        .extracting(f -> ((SQLException) f).getSQLState())
                        .isEqualTo(CHECK_VIOLATION);
                app.rollback(before);
            } finally {
                app.rollback();
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Marks every row this suite writes, so cleanup can never touch another suite's data. */
    private static final String PROBE_REFERENCE = "P4-TSK-004 schema probe";

    private enum Shape {
        COMPLETED,
        FAILED,
        REVERSED,
        SELF_REFUSAL
    }

    private static final class Row {
        UUID id = IDS.next();
        UUID customerId = IDS.next();
        UUID source = IDS.next();
        UUID destination = IDS.next();
        long amountMinor = 12_50;
        String status;
        String failureReason;
        UUID journalEntryId;
        UUID reversalEntryId;
        UUID reversedBy;
        Instant reversedAt;
        Instant initiatedAt = Instant.now(CLOCK).minusSeconds(3600);

        Row with(java.util.function.Consumer<Row> change) {
            change.accept(this);
            return this;
        }
    }

    /** The coherent shape per status — what the guarded writers will actually store. */
    private static Row coherent(Shape shape) {
        Row row = new Row();
        switch (shape) {
            case COMPLETED -> {
                row.status = "COMPLETED";
                row.journalEntryId = IDS.next();
            }
            case FAILED -> {
                row.status = "FAILED";
                row.failureReason = "INSUFFICIENT_FUNDS";
            }
            case REVERSED -> {
                row.status = "REVERSED";
                row.journalEntryId = IDS.next();
                row.reversalEntryId = IDS.next();
                row.reversedBy = IDS.next();
                row.reversedAt = Instant.now(CLOCK);
            }
            case SELF_REFUSAL -> {
                row.status = "FAILED";
                row.failureReason = "SELF_TRANSFER";
                row.destination = row.source;
            }
        }
        return row;
    }

    private static void refused(
            Connection app, String what, String sqlState, Row row) throws SQLException {
        Savepoint before = app.setSavepoint();
        assertThatThrownBy(() -> insertTransfer(app, row))
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        app.rollback(before);
    }

    private static void refusedUpdate(
            Connection app, String what, UUID id, String set, String messageFragment)
            throws SQLException {
        Savepoint before = app.setSavepoint();
        assertThatThrownBy(() -> {
                    try (PreparedStatement update = app.prepareStatement(
                            "UPDATE transfers.transfer " + set + " WHERE id = ?")) {
                        update.setObject(1, id);
                        update.executeUpdate();
                    }
                })
                .as(what)
                .isInstanceOf(SQLException.class)
                .hasFieldOrPropertyWithValue("SQLState", RAISED)
                .hasMessageContaining(messageFragment);
        app.rollback(before);
    }

    private static void insertTransfer(Connection connection, Row row) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO transfers.transfer (id, customer_id, source_account_id,"
                        + " destination_account_id, amount_minor, currency, scale, reference,"
                        + " status, failure_reason, journal_entry_id, reversal_entry_id,"
                        + " reversed_by, reversed_at, initiated_by, initiated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'EUR', 2, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, row.id);
            insert.setObject(2, row.customerId);
            insert.setObject(3, row.source);
            insert.setObject(4, row.destination);
            insert.setLong(5, row.amountMinor);
            insert.setString(6, PROBE_REFERENCE);
            insert.setString(7, row.status);
            insert.setString(8, row.failureReason);
            insert.setObject(9, row.journalEntryId);
            insert.setObject(10, row.reversalEntryId);
            insert.setObject(11, row.reversedBy);
            insert.setTimestamp(12, row.reversedAt == null ? null : Timestamp.from(row.reversedAt));
            insert.setObject(13, IDS.next());
            insert.setTimestamp(14, Timestamp.from(row.initiatedAt));
            insert.executeUpdate();
        }
    }

    private static void insertEvent(
            Connection connection, UUID transferId, String from, String to) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO transfers.transfer_event"
                        + " (transfer_id, from_status, to_status, actor_id, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            insert.setObject(1, transferId);
            insert.setString(2, from);
            insert.setString(3, to);
            insert.setObject(4, IDS.next());
            insert.setTimestamp(5, Timestamp.from(Instant.now(CLOCK)));
            insert.executeUpdate();
        }
    }

    /** The table's columns from the catalogue, so the sweep cannot go stale. */
    private static List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'transfers' AND table_name = ?"
                        + " ORDER BY ordinal_position")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        assertThat(columns).as("columns of transfers.%s", table).isNotEmpty();
        return columns;
    }
}
