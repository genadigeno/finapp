package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.Beneficiary;
import com.finapp.transfers.BeneficiaryId;
import com.finapp.transfers.BeneficiaryStatus;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.JdbcBeneficiaryStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The beneficiary's schema and store against a real PostgreSQL (`P4-TSK-006`).
 *
 * <p><strong>Self-contained, because the schema is</strong>: {@code transfers.beneficiary}
 * carries no foreign key — the party and the destination are references by value (ADR-0029) —
 * so the fixtures are identifiers, and what guarantees a real destination in production is
 * {@code BeneficiaryCreation}'s port validation, proven hermetically where the port can be
 * faked. What this suite owns is what only the database can prove: the one-live index as the
 * ten-way arbiter, the freed slot ({@code INV-LIFE-04}), the converging conditional removal
 * with its ownership predicate, the every-writer trigger, and the grants.
 */
@Tag("database")
@DisplayName("beneficiary schema and store (P4-TSK-006)")
class BeneficiaryDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final BeneficiaryStore<Connection> store = new JdbcBeneficiaryStore();

    @Test
    @DisplayName("ten concurrent creates of one destination produce one live row, nine converged")
    void tenConcurrentCreatesProduceOneLiveRow() throws Exception {
        UUID party = UUID.randomUUID();
        UUID destination = UUID.randomUUID();

        List<Callable<BeneficiaryStore.Creation>> creates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            creates.add(
                    () -> {
                        // Own connection per simulated instance (P0-TST-009).
                        try (Connection app = DatabaseRoles.application()) {
                            app.setAutoCommit(false);
                            BeneficiaryStore.Creation creation =
                                    store.createOrConverge(
                                            app,
                                            Beneficiary.create(
                                                    BeneficiaryId.next(IDS),
                                                    party,
                                                    "Aunt Vera",
                                                    destination,
                                                    CLOCK));
                            app.commit();
                            return creation;
                        }
                    });
        }

        ExecutorService racers = Executors.newFixedThreadPool(10);
        List<BeneficiaryStore.Creation> outcomes = new ArrayList<>();
        try {
            for (Future<BeneficiaryStore.Creation> outcome : racers.invokeAll(creates)) {
                outcomes.add(outcome.get());
            }
        } finally {
            racers.shutdown();
        }

        // Counted in the table, never inferred from return values.
        assertThat(rowsFor(party)).hasSize(1);
        assertThat(outcomes.stream().filter(BeneficiaryStore.Creation::created)).hasSize(1);
        UUID winner =
                outcomes.stream()
                        .filter(BeneficiaryStore.Creation::created)
                        .findFirst()
                        .orElseThrow()
                        .beneficiary()
                        .id()
                        .value();
        assertThat(outcomes)
                .as("the nine losers converge onto the winner's row, not an error")
                .allSatisfy(
                        outcome ->
                                assertThat(outcome.beneficiary().id().value()).isEqualTo(winner));
    }

    @Test
    @DisplayName("a removed slot is re-creatable, and the removed row survives as evidence")
    void aRemovedSlotIsRecreatable() throws Exception {
        UUID party = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);

            Beneficiary first = create(app, party, destination);
            assertThat(store.remove(app, first.id(), party, Instant.now(CLOCK))).isTrue();
            app.commit();

            // INV-LIFE-04's freed-slot asymmetry: removal frees the (party, destination) slot,
            // so saving the same destination again is a NEW aggregate - not a resurrection.
            BeneficiaryStore.Creation second =
                    store.createOrConverge(
                            app,
                            Beneficiary.create(
                                    BeneficiaryId.next(IDS),
                                    party,
                                    "Aunt Vera again",
                                    destination,
                                    CLOCK));
            app.commit();

            assertThat(second.created()).isTrue();
            assertThat(second.beneficiary().id()).isNotEqualTo(first.id());
            // Two rows counted in the table: the evidence survives, exactly one live.
            List<String> statuses = rowsFor(party);
            assertThat(statuses)
                    .containsExactlyInAnyOrder(
                            BeneficiaryStatus.REMOVED.name(), BeneficiaryStatus.ACTIVE.name());
        }
    }

    @Test
    @DisplayName("removal converges retries and carries the ownership predicate")
    void removalConvergesAndIsOwnershipScoped() throws Exception {
        UUID party = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            Beneficiary saved = create(app, party, destination);
            app.commit();

            // A stranger's attempt matches nothing: party_id = ? is the ownership check, in
            // the statement (ADR-0031) - and "not yours" is indistinguishable from "already
            // removed", the one-404 shape prepared at the port.
            assertThat(store.remove(app, saved.id(), UUID.randomUUID(), Instant.now(CLOCK)))
                    .isFalse();
            app.commit();
            assertThat(statusOf(saved.id().value())).isEqualTo("ACTIVE");

            // Truncated to the column's own microsecond resolution: the driver ROUNDS
            // nanoseconds (the P3-TSK-005 finding), and this assertion compares instants.
            Instant removedAt =
                    Instant.now(CLOCK).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertThat(store.remove(app, saved.id(), party, removedAt)).isTrue();
            app.commit();

            // The retry converges on false, and the recorded instant is the first removal's -
            // the conditional's row count is the outcome, so nothing is written twice.
            assertThat(store.remove(app, saved.id(), party, removedAt.plusSeconds(60)))
                    .isFalse();
            app.commit();
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT removed_at FROM transfers.beneficiary WHERE id = ?")) {
                read.setObject(1, saved.id().value());
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getTimestamp(1).toInstant()).isEqualTo(removedAt);
                }
            }
        }
    }

    @Test
    @DisplayName("raw SQL cannot resurrect, edit or incoherently store a beneficiary")
    void rawSqlCannotResurrectOrEditARow() throws Exception {
        UUID party = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        UUID removedRow;
        UUID liveRow;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            Beneficiary saved = create(app, party, destination);
            store.remove(app, saved.id(), party, Instant.now(CLOCK));
            liveRow = create(app, UUID.randomUUID(), destination).id().value();
            app.commit();
            removedRow = saved.id().value();
        }

        // As the MIGRATOR - the writer the grants do not bind. The trigger is the control
        // (INV-LIFE-04 at DB-CONSTRAINT rank, the ledger V008 shape).
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                refusedUpdate(
                        migrator,
                        "a REMOVED beneficiary cannot be resurrected by any writer",
                        "UPDATE transfers.beneficiary SET status = 'ACTIVE', removed_at = NULL"
                                + " WHERE id = ?",
                        removedRow,
                        RAISED);
                // The probe that isolates the FROZEN half: an edit smuggled inside the legal
                // removal edge. A status-preserving edit is refused by the edge check too, so
                // only this shape proves the freeze is load-bearing on its own.
                refusedUpdate(
                        migrator,
                        "the display name is immutable even on the legal edge:"
                                + " renaming is remove-and-recreate",
                        "UPDATE transfers.beneficiary SET status = 'REMOVED',"
                                + " removed_at = created_at, display_name = 'Edited'"
                                + " WHERE id = ?",
                        liveRow,
                        RAISED);

                // The coherence pair, from scratch: a live row carrying a removal instant and
                // an unknown status are each refused at CHECK rank for every writer.
                refusedInsert(
                        migrator,
                        "an ACTIVE row carrying a removal instant is unsplittable",
                        "ACTIVE",
                        true,
                        CHECK_VIOLATION);
                // With a removal instant, so the coherence pair is satisfied and the refusal
                // is isolated to the status CHECK - a probe two constraints would catch says
                // nothing about either (the P0-TSK-031 isolation lesson).
                refusedInsert(
                        migrator,
                        "an unknown status is refused",
                        "SUSPENDED",
                        true,
                        CHECK_VIOLATION);
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("the grants are the removal columns and nothing more, swept per column")
    void theGrantsAreTheNarrowedSet() throws Exception {
        UUID party = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                Beneficiary saved = create(app, party, UUID.randomUUID());

                // Derived from information_schema (the P0-TST-007 idiom), so a column added
                // later is swept without anyone remembering. The granted pair is exercised as
                // the positive control by every removal in this suite.
                List<String> frozen = columnsOf(app);
                frozen.removeAll(List.of("status", "removed_at"));
                assertThat(frozen).as("the derived sweep has subjects").isNotEmpty();
                for (String column : frozen) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(
                                    () -> {
                                        try (PreparedStatement update =
                                                app.prepareStatement(
                                                        "UPDATE transfers.beneficiary SET "
                                                                + column + " = " + column
                                                                + " WHERE id = ?")) {
                                            update.setObject(1, saved.id().value());
                                            update.executeUpdate();
                                        }
                                    })
                            .as("the application role must hold no UPDATE on %s", column)
                            .isInstanceOf(SQLException.class)
                            .extracting(f -> ((SQLException) f).getSQLState())
                            .isEqualTo(INSUFFICIENT_PRIVILEGE);
                    app.rollback(before);
                }

                Savepoint before = app.setSavepoint();
                assertThatThrownBy(
                                () -> {
                                    try (PreparedStatement delete =
                                            app.prepareStatement(
                                                    "DELETE FROM transfers.beneficiary"
                                                            + " WHERE id = ?")) {
                                        delete.setObject(1, saved.id().value());
                                        delete.executeUpdate();
                                    }
                                })
                        .as("a beneficiary's end is a status, never an absence")
                        .isInstanceOf(SQLException.class)
                        .extracting(f -> ((SQLException) f).getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE);
                app.rollback(before);
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private Beneficiary create(Connection app, UUID party, UUID destination) {
        BeneficiaryStore.Creation creation =
                store.createOrConverge(
                        app,
                        Beneficiary.create(
                                BeneficiaryId.next(IDS), party, "Aunt Vera", destination, CLOCK));
        assertThat(creation.created()).isTrue();
        return creation.beneficiary();
    }

    private static void refusedUpdate(
            Connection connection, String what, String statement, UUID id, String sqlState)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement update =
                                    connection.prepareStatement(statement)) {
                                update.setObject(1, id);
                                update.executeUpdate();
                            }
                        })
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private void refusedInsert(
            Connection connection,
            String what,
            String status,
            boolean withRemovalInstant,
            String sqlState)
            throws SQLException {
        // One instant for both columns, so removal-follows-creation is satisfied and the
        // refusal is isolated to the constraint under test.
        Instant now = Instant.now(CLOCK);
        Instant removedAt = withRemovalInstant ? now : null;
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement insert =
                                    connection.prepareStatement(
                                            "INSERT INTO transfers.beneficiary (id, party_id,"
                                                + " display_name, destination_account_id,"
                                                + " status, created_at, removed_at)"
                                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setObject(1, IDS.next());
                                insert.setObject(2, UUID.randomUUID());
                                insert.setString(3, "P4-TSK-006 schema probe");
                                insert.setObject(4, UUID.randomUUID());
                                insert.setString(5, status);
                                insert.setTimestamp(6, Timestamp.from(now));
                                insert.setTimestamp(
                                        7,
                                        removedAt == null ? null : Timestamp.from(removedAt));
                                insert.executeUpdate();
                            }
                        })
                .as(what)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(sqlState);
        connection.rollback(before);
    }

    private static List<String> rowsFor(UUID party) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM transfers.beneficiary"
                                        + " WHERE party_id = ?")) {
            read.setObject(1, party);
            try (ResultSet rows = read.executeQuery()) {
                List<String> statuses = new ArrayList<>();
                while (rows.next()) {
                    statuses.add(rows.getString(1));
                }
                return statuses;
            }
        }
    }

    private static String statusOf(UUID id) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT status FROM transfers.beneficiary WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String> columnsOf(Connection connection) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_schema = 'transfers'"
                                + " AND table_name = 'beneficiary'"
                                + " ORDER BY ordinal_position")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        assertThat(columns).as("columns of transfers.beneficiary").isNotEmpty();
        return columns;
    }
}
