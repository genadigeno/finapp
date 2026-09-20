package com.finapp.app.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.paymentmethods.PaymentMethod;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.TokenReference;
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
 * The payment method's schema and store against a real PostgreSQL (`P5-TSK-004`).
 *
 * <p><strong>Self-contained, because the schema is</strong>: {@code payment_method} carries no
 * foreign key — the party is a reference by value (ADR-0029) — so the fixtures are
 * identifiers. What this suite owns is what only the database can prove: the one-live index as
 * the ten-way arbiter, the freed slot ({@code INV-LIFE-04}), the converging conditional detach
 * with its ownership predicate, the every-writer trigger — and <strong>the PAN sweep</strong>:
 * `INV-PAY-02` at {@code DB-CONSTRAINT} rank, proven per column with the column list derived
 * from {@code information_schema} so a column added later is swept without anyone remembering.
 */
@Tag("database")
@DisplayName("payment method schema and store (P5-TSK-004)")
class PaymentMethodDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** The shapes a card number is written in — what the sweep plants into every text column. */
    private static final List<String> PAN_SHAPES =
            List.of("4111111111111111", "4111-1111-1111-1111", "378282246310005");

    private final PaymentMethodStore<Connection> store = new JdbcPaymentMethodStore();

    @Test
    @DisplayName("ten concurrent attaches of one instrument produce one live row, nine converged")
    void tenConcurrentAttachesProduceOneLiveRow() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_race-" + UUID.randomUUID());

        List<Callable<PaymentMethodStore.Attachment>> attaches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            attaches.add(
                    () -> {
                        // Own connection per simulated instance (P0-TST-009).
                        try (Connection app = DatabaseRoles.application()) {
                            app.setAutoCommit(false);
                            PaymentMethodStore.Attachment attachment =
                                    store.attachOrConverge(
                                            app,
                                            PaymentMethod.attach(
                                                    PaymentMethodId.next(IDS),
                                                    party,
                                                    token,
                                                    "Visa",
                                                    "4242",
                                                    12,
                                                    2030,
                                                    CLOCK));
                            app.commit();
                            return attachment;
                        }
                    });
        }

        ExecutorService racers = Executors.newFixedThreadPool(10);
        List<PaymentMethodStore.Attachment> outcomes = new ArrayList<>();
        try {
            for (Future<PaymentMethodStore.Attachment> outcome : racers.invokeAll(attaches)) {
                outcomes.add(outcome.get());
            }
        } finally {
            racers.shutdown();
        }

        // Counted in the table, never inferred from return values.
        assertThat(rowsFor(party)).hasSize(1);
        assertThat(outcomes.stream().filter(PaymentMethodStore.Attachment::created)).hasSize(1);
        UUID winner =
                outcomes.stream()
                        .filter(PaymentMethodStore.Attachment::created)
                        .findFirst()
                        .orElseThrow()
                        .method()
                        .id()
                        .value();
        assertThat(outcomes)
                .as("the nine losers converge onto the winner's row, not an error")
                .allSatisfy(
                        outcome -> assertThat(outcome.method().id().value()).isEqualTo(winner));
    }

    @Test
    @DisplayName("a detached slot is re-attachable, and the detached row survives as evidence")
    void aDetachedSlotIsReattachable() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_slot-" + UUID.randomUUID());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);

            PaymentMethod first = attach(app, party, token);
            assertThat(store.detach(app, first.id(), party, Instant.now(CLOCK))).isTrue();
            app.commit();

            // INV-LIFE-04's freed-slot asymmetry: detaching frees the (party, token) slot, so
            // attaching the same instrument again is a NEW aggregate - not a resurrection.
            PaymentMethodStore.Attachment second =
                    store.attachOrConverge(
                            app,
                            PaymentMethod.attach(
                                    PaymentMethodId.next(IDS),
                                    party,
                                    token,
                                    "Visa",
                                    "4242",
                                    12,
                                    2031,
                                    CLOCK));
            app.commit();

            assertThat(second.created()).isTrue();
            assertThat(second.method().id()).isNotEqualTo(first.id());
            // Two rows counted in the table: the evidence survives, exactly one live.
            assertThat(rowsFor(party))
                    .containsExactlyInAnyOrder(
                            PaymentMethodStatus.DETACHED.name(),
                            PaymentMethodStatus.ACTIVE.name());
        }
    }

    @Test
    @DisplayName("detachment converges retries and carries the ownership predicate")
    void detachmentConvergesAndIsOwnershipScoped() throws Exception {
        UUID party = UUID.randomUUID();
        TokenReference token = TokenReference.of("tok_own-" + UUID.randomUUID());
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PaymentMethod saved = attach(app, party, token);
            app.commit();

            // A stranger's attempt matches nothing: party_id = ? is the ownership check, in
            // the statement (ADR-0031) - and "not yours" is indistinguishable from "already
            // detached", the one-404 shape prepared at the port for P5-TSK-005.
            assertThat(store.detach(app, saved.id(), UUID.randomUUID(), Instant.now(CLOCK)))
                    .isFalse();
            app.commit();
            assertThat(statusOf(saved.id().value())).isEqualTo("ACTIVE");

            // Truncated to the column's own microsecond resolution: the driver ROUNDS
            // nanoseconds (the P3-TSK-005 finding), and this assertion compares instants.
            Instant detachedAt =
                    Instant.now(CLOCK).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertThat(store.detach(app, saved.id(), party, detachedAt)).isTrue();
            app.commit();

            // The retry converges on false, and the recorded instant is the first detach's -
            // the conditional's row count is the outcome, so nothing is written twice.
            assertThat(store.detach(app, saved.id(), party, detachedAt.plusSeconds(60)))
                    .isFalse();
            app.commit();
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT detached_at FROM paymentmethods.payment_method"
                                    + " WHERE id = ?")) {
                read.setObject(1, saved.id().value());
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getTimestamp(1).toInstant()).isEqualTo(detachedAt);
                }
            }
        }
    }

    @Test
    @DisplayName("raw SQL cannot resurrect, edit or incoherently store a payment method")
    void rawSqlCannotResurrectOrEditARow() throws Exception {
        UUID party = UUID.randomUUID();
        UUID detachedRow;
        UUID liveRow;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            PaymentMethod saved =
                    attach(app, party, TokenReference.of("tok_frozen-" + UUID.randomUUID()));
            store.detach(app, saved.id(), party, Instant.now(CLOCK));
            liveRow =
                    attach(app, party, TokenReference.of("tok_live-" + UUID.randomUUID()))
                            .id()
                            .value();
            app.commit();
            detachedRow = saved.id().value();
        }

        // As the MIGRATOR - the writer the grants do not bind. The trigger is the control
        // (INV-LIFE-04 at DB-CONSTRAINT rank, the beneficiary V003 shape).
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                refusedUpdate(
                        migrator,
                        "a DETACHED payment method cannot be resurrected by any writer",
                        "UPDATE paymentmethods.payment_method"
                                + " SET status = 'ACTIVE', detached_at = NULL WHERE id = ?",
                        detachedRow,
                        RAISED);
                // The probe that isolates the FROZEN half: an edit smuggled inside the legal
                // detach edge. A status-preserving edit is refused by the edge check too, so
                // only this shape proves the freeze is load-bearing on its own (P4-TSK-006's
                // probe design).
                refusedUpdate(
                        migrator,
                        "the token is immutable even on the legal edge:"
                                + " updating is detach-and-reattach",
                        "UPDATE paymentmethods.payment_method SET status = 'DETACHED',"
                                + " detached_at = created_at, token_reference = 'tok_edited'"
                                + " WHERE id = ?",
                        liveRow,
                        RAISED);

                // The coherence pair, from scratch: a live row carrying a detachment instant
                // and an unknown status are each refused at CHECK rank for every writer.
                refusedInsert(
                        migrator,
                        "an ACTIVE row carrying a detachment instant is unsplittable",
                        "tok_probe-a1",
                        "ACTIVE",
                        true,
                        CHECK_VIOLATION);
                // With a detachment instant, so the coherence pair is satisfied and the
                // refusal is isolated to the status CHECK (the P0-TSK-031 isolation lesson).
                refusedInsert(
                        migrator,
                        "an unknown status is refused",
                        "tok_probe-a2",
                        "SUSPENDED",
                        true,
                        CHECK_VIOLATION);
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("a PAN cannot be stored in any column, swept from information_schema")
    void aPanCannotBeStoredInAnyColumn() throws Exception {
        // INV-PAY-02 at DB-CONSTRAINT rank, per column: every text column refuses PAN-shaped
        // values by its own CHECK, and the non-text columns are structurally unable (uuid,
        // integer-with-range, timestamptz). Derived from information_schema, so a column added
        // later is swept - and a text column added without a PAN-refusing shape FAILS here,
        // which is the point: the sweep is the reviewer that never forgets.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                List<String[]> columns = columnsWithTypes(migrator);
                assertThat(columns).as("the derived sweep has subjects").isNotEmpty();
                for (String[] column : columns) {
                    String name = column[0];
                    String type = column[1];
                    if (!"text".equals(type)) {
                        // uuid, integer (bounded 1-12 / 2000-2100) and timestamptz cannot hold
                        // a 13-19 digit value; integers are additionally range-CHECKed above
                        // any PAN's numeric value... except expiry_year, whose 2000-2100 bound
                        // is what refuses it - both proven by the expiry probes in
                        // PaymentMethodTest and the range CHECKs exercised below.
                        continue;
                    }
                    for (String pan : PAN_SHAPES) {
                        refusedPanPlant(migrator, name, pan);
                    }
                }
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("the grants are the detachment columns and nothing more, swept per column")
    void theGrantsAreTheNarrowedSet() throws Exception {
        UUID party = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                PaymentMethod saved =
                        attach(app, party, TokenReference.of("tok_grant-" + UUID.randomUUID()));

                // Derived from information_schema (the P0-TST-007 idiom), so a column added
                // later is swept without anyone remembering. The granted pair is exercised as
                // the positive control by every detach in this suite.
                List<String> frozen = new ArrayList<>();
                for (String[] column : columnsWithTypes(app)) {
                    frozen.add(column[0]);
                }
                frozen.removeAll(List.of("status", "detached_at"));
                assertThat(frozen).as("the derived sweep has subjects").isNotEmpty();
                for (String column : frozen) {
                    Savepoint before = app.setSavepoint();
                    assertThatThrownBy(
                                    () -> {
                                        try (PreparedStatement update =
                                                app.prepareStatement(
                                                        "UPDATE paymentmethods.payment_method"
                                                                + " SET " + column + " = "
                                                                + column + " WHERE id = ?")) {
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
                                                    "DELETE FROM"
                                                            + " paymentmethods.payment_method"
                                                            + " WHERE id = ?")) {
                                        delete.setObject(1, saved.id().value());
                                        delete.executeUpdate();
                                    }
                                })
                        .as("a payment method's end is a status, never an absence")
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

    private PaymentMethod attach(Connection app, UUID party, TokenReference token) {
        PaymentMethodStore.Attachment attachment =
                store.attachOrConverge(
                        app,
                        PaymentMethod.attach(
                                PaymentMethodId.next(IDS),
                                party,
                                token,
                                "Visa",
                                "4242",
                                12,
                                2030,
                                CLOCK));
        assertThat(attachment.created()).isTrue();
        return attachment.method();
    }

    /**
     * Plants a PAN shape into one text column of an otherwise-legal row and requires the
     * refusal — each column's own {@code CHECK} is what makes {@code INV-PAY-02} physical.
     */
    private static void refusedPanPlant(Connection connection, String column, String pan)
            throws SQLException {
        Instant now = Instant.now(CLOCK);
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement insert =
                                    connection.prepareStatement(
                                            "INSERT INTO paymentmethods.payment_method"
                                                + " (id, party_id, token_reference, brand,"
                                                + " display_suffix, expiry_month, expiry_year,"
                                                + " status, created_at, detached_at)"
                                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setObject(1, IDS.next());
                                insert.setObject(2, UUID.randomUUID());
                                insert.setString(
                                        3,
                                        "token_reference".equals(column)
                                                ? pan
                                                : "tok_sweep-" + UUID.randomUUID());
                                insert.setString(4, "brand".equals(column) ? pan : "Visa");
                                insert.setString(
                                        5, "display_suffix".equals(column) ? pan : "4242");
                                insert.setInt(6, 12);
                                insert.setInt(7, 2030);
                                insert.setString(8, "status".equals(column) ? pan : "ACTIVE");
                                insert.setTimestamp(9, Timestamp.from(now));
                                insert.setTimestamp(10, null);
                                insert.executeUpdate();
                            }
                        })
                .as("column %s must refuse the PAN shape %s", column, pan)
                .isInstanceOf(SQLException.class)
                .extracting(f -> ((SQLException) f).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
        connection.rollback(before);
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
            String token,
            String status,
            boolean withDetachmentInstant,
            String sqlState)
            throws SQLException {
        // One instant for both columns, so detachment-follows-creation is satisfied and the
        // refusal is isolated to the constraint under test.
        Instant now = Instant.now(CLOCK);
        Instant detachedAt = withDetachmentInstant ? now : null;
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(
                        () -> {
                            try (PreparedStatement insert =
                                    connection.prepareStatement(
                                            "INSERT INTO paymentmethods.payment_method"
                                                + " (id, party_id, token_reference, brand,"
                                                + " display_suffix, expiry_month, expiry_year,"
                                                + " status, created_at, detached_at)"
                                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                                insert.setObject(1, IDS.next());
                                insert.setObject(2, UUID.randomUUID());
                                insert.setString(3, token);
                                insert.setString(4, "Visa");
                                insert.setString(5, "4242");
                                insert.setInt(6, 12);
                                insert.setInt(7, 2030);
                                insert.setString(8, status);
                                insert.setTimestamp(9, Timestamp.from(now));
                                insert.setTimestamp(
                                        10,
                                        detachedAt == null ? null : Timestamp.from(detachedAt));
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
                                "SELECT status FROM paymentmethods.payment_method"
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
                                "SELECT status FROM paymentmethods.payment_method"
                                        + " WHERE id = ?")) {
            read.setObject(1, id);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static List<String[]> columnsWithTypes(Connection connection) throws SQLException {
        List<String[]> columns = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT column_name, data_type FROM information_schema.columns"
                                + " WHERE table_schema = 'paymentmethods'"
                                + " AND table_name = 'payment_method'"
                                + " ORDER BY ordinal_position")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    columns.add(new String[] {rows.getString(1), rows.getString(2)});
                }
            }
        }
        return columns;
    }
}
