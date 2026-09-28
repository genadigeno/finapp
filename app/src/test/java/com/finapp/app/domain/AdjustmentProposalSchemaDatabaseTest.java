package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentProposalStatus;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JournalEntryId;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V010`'s four-eyes machinery against raw SQL (`P3-TSK-021`, {@code INV-AUD-04} at
 * {@code DB-CONSTRAINT} rank) — the writers the domain never sees: an {@code ADJUSTMENT}
 * entry cannot COMMIT unapproved, a self-approved proposal row is unstorable, and the
 * proposal a person read is the proposal that decides, frozen for every writer including
 * the migrator.
 */
@Tag("database")
@DisplayName("the proposal schema binds every writer (P3-TSK-021, INV-AUD-04)")
class AdjustmentProposalSchemaDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a raw-SQL ADJUSTMENT entry cannot commit without an approved proposal -"
            + " and a POSTING needs none, so history's writers are untouched")
    void aRawSqlAdjustmentCannotCommitUnapproved() throws SQLException {
        UUID entry = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            insertEntry(app, entry, "ADJUSTMENT", "raw four-eyes probe");
            insertBalancedLines(app, entry);
            // Every statement succeeded; the COMMIT is where the deferred trigger judges
            // the whole (the V004 mechanism), and it refuses naming the invariant.
            assertThatThrownBy(app::commit)
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514")
                    .hasMessageContaining("INV-AUD-04");
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(entryExists(app, entry))
                    .as("the refused commit persisted nothing")
                    .isFalse();
        }

        // The positive control, and the history argument: the trigger is scoped to
        // adjustments, so an ordinary posting needs no proposal. Forced IMMEDIATE so the
        // trigger provably fires and passes, then rolled back - nothing is committed, and
        // no unprojected posting can poison the shared container's drift readings.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                execute(app, "SET CONSTRAINTS ledger.adjustment_entry_is_approved IMMEDIATE");
                assertThatCode(() -> insertEntry(app, IDS.next(), "POSTING", null))
                        .doesNotThrowAnyException();
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("a self-approved proposal row is unstorable: approver <> initiator is the"
            + " schema's own CHECK (INV-AUD-04's Enforce clause)")
    void aSelfApprovedProposalRowIsUnstorable() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                // A journal entry for the FK's sake, never committed: the probe's subject
                // is the CHECK, and everything here rolls back.
                UUID entry = IDS.next();
                insertEntry(app, entry, "POSTING", null);
                UUID proposal = insertProposal(app, "person-1");

                Savepoint beforeTheProbe = app.setSavepoint();
                assertThatThrownBy(
                                () ->
                                        decide(app, proposal, "person-1", entry))
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514")
                        .hasMessageContaining("adjustment_proposal_approver_is_not_initiator");
                app.rollback(beforeTheProbe);

                // The positive control, so the refusal is the distinctness and not the
                // UPDATE: a second person's approval of the same row is storable.
                assertThatCode(() -> decide(app, proposal, "person-2", entry))
                        .doesNotThrowAnyException();
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the proposal a person read is the proposal that decides: payload frozen,"
            + " lines immutable, terminals terminal - for the migrator too")
    void theProposalIsFrozenForEveryWriter() throws SQLException {
        UUID standing;
        UUID rejected;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            standing = insertProposal(app, "person-1");
            insertProposalLine(app, standing);
            rejected = insertProposal(app, "person-1");
            app.commit();
            // A legitimate rejection through the granted columns, so a terminal row exists -
            // decided on the test's clock, against a proposal back-dated an hour. Two database
            // now() reads in two transactions are not ordered on a clock that steps back
            // (P1-TSK-031), and V010 holds decided_at >= proposed_at (X-TSK-005).
            execute(
                    app,
                    "UPDATE ledger.adjustment_proposal SET status = 'REJECTED',"
                            + " decided_by = 'person-2', decided_at = ? WHERE id = ?",
                    testClockNow(),
                    rejected);
            app.commit();
        }

        // The application role cannot touch the payload at all: the UPDATE grant is the
        // decision's four columns (the V004 column-narrowing).
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE ledger.adjustment_proposal"
                                                    + " SET reference = 'rewritten'"
                                                    + " WHERE id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
        }

        // And the trigger binds the writers the grant does not: the migrator's payload
        // edit, terminal reopening, and any line edit are all refused.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE ledger.adjustment_proposal"
                                                    + " SET reference = 'rewritten'"
                                                    + " WHERE id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("approver approves what they read");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE ledger.adjustment_proposal"
                                                    + " SET status = 'PROPOSED',"
                                                    + " decided_by = NULL, decided_at = NULL"
                                                    + " WHERE id = ?",
                                            rejected))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("PROPOSED -> APPROVED and PROPOSED -> REJECTED");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE ledger.adjustment_proposal_line"
                                                    + " SET amount_minor = 999999"
                                                    + " WHERE proposal_id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("approver approves what they read");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "DELETE FROM ledger.adjustment_proposal_line"
                                                    + " WHERE proposal_id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("approver approves what they read");
        }
    }

    /**
     * The insert-then-decide fixture shape is proven insensitive to a backwards clock correction
     * ({@code P1-TSK-031}).
     *
     * <p>{@link #theProposalIsFrozenForEveryWriter} proposes through {@link #insertProposal},
     * commits, and rejects in a later transaction with {@code decided_at = now()}: two reads of
     * the server clock, and the local container's clock runs fast and is corrected backwards, so
     * nothing orders the second read after the first. {@code V010}'s
     * {@code adjustment_proposal_decision_follows_proposal} is right to refuse such a pair, and no
     * store clamp reaches a raw UPDATE, so the fixture must not produce one.
     *
     * <p>The decision below simulates a correction of thirty minutes and must succeed against a
     * fixture proposal. The second half is the vacuity control: the same decision against a
     * proposal written at plain {@code now()} is still refused, so a pass proves the back-dating
     * carries the property rather than the CHECK being dead.
     */
    @Test
    @DisplayName("a back-dated proposal fixture survives a backwards clock correction, one at"
            + " now() does not (P1-TSK-031)")
    void theProposalFixtureSurvivesABackwardsClockCorrection() throws SQLException {
        String rejectionBehindTheClock =
                "UPDATE ledger.adjustment_proposal SET status = 'REJECTED',"
                        + " decided_by = 'person-2', decided_at = now() - interval '30 minutes'"
                        + " WHERE id = ?";
        try (Connection app = DatabaseRoles.application()) {
            UUID fixture = insertProposal(app, "person-1");
            assertThatCode(() -> execute(app, rejectionBehindTheClock, fixture))
                    .as("a clock corrected backwards between the proposal and its decision must"
                            + " not refuse the fixture")
                    .doesNotThrowAnyException();

            UUID atNow = IDS.next();
            execute(
                    app,
                    "INSERT INTO ledger.adjustment_proposal (id, status, posting_date,"
                            + " value_date, reference, reason, proposed_by, proposed_at)"
                            + " VALUES (?, 'PROPOSED', current_date, current_date,"
                            + " 'raw-probe', 'raw schema probe', 'person-1', now())",
                    atNow);
            assertThatThrownBy(() -> execute(app, rejectionBehindTheClock, atNow))
                    .as("the same decision against a proposal written at now() is refused - the"
                            + " CHECK is alive, so the back-dating is what carries the property")
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514")
                    .hasMessageContaining("adjustment_proposal_decision_follows_proposal");
        }
    }

    // -----------------------------------------------------------------
    // The clock

    /**
     * A proposal is decided on the clock of whichever instance serves the approval, the
     * rejection or the initiator's withdrawal, which may read behind the one that recorded it.
     * Both edges are the store's one conditional, driven directly: what such a clock can break is
     * the statement - for an approval, the entry posted in the same transaction with it.
     */
    @Test
    @DisplayName("a clock behind the proposal cannot fail a legal decision: decided_at clamps to"
            + " proposed_at in the statement (the P1-TSK-031 drift; ADR-0014)")
    void aClockBehindTheProposalCannotFailALegalDecision() throws SQLException {
        JdbcAdjustmentProposalStore store = new JdbcAdjustmentProposalStore();

        // The approval, rolled back: its entry is a raw POSTING for the FK's sake, never
        // committed, as the self-approval probe above has it.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID entry = IDS.next();
                insertEntry(app, entry, "POSTING", null);
                UUID approved = insertProposal(app, "person-1");
                Instant born = stampsOf(app, approved).proposedAt();
                assertThat(
                                store.decide(
                                        app,
                                        AdjustmentProposalId.of(approved),
                                        AdjustmentProposalStatus.APPROVED,
                                        "person-2",
                                        born.minusMillis(250),
                                        Optional.of(JournalEntryId.of(entry))))
                        .isTrue();
                assertThat(stampsOf(app, approved).decidedAt()).isEqualTo(born);
            } finally {
                app.rollback();
            }
        }

        // The rejection, committed.
        try (Connection app = DatabaseRoles.application()) {
            UUID rejected = insertProposal(app, "person-1");
            UUID own = insertProposal(app, "person-1");
            Instant born = stampsOf(app, rejected).proposedAt();
            assertThat(
                            store.decide(
                                    app,
                                    AdjustmentProposalId.of(rejected),
                                    AdjustmentProposalStatus.REJECTED,
                                    "person-2",
                                    born.minusMillis(250),
                                    Optional.empty()))
                    .isTrue();
            assertThat(stampsOf(app, rejected).decidedAt()).isEqualTo(born);

            // A floor, not a pin: a clock past the proposal stamps its own read.
            Instant later = stampsOf(app, own).proposedAt().plusSeconds(5);
            assertThat(
                            store.decide(
                                    app,
                                    AdjustmentProposalId.of(own),
                                    AdjustmentProposalStatus.REJECTED,
                                    "person-2",
                                    later,
                                    Optional.empty()))
                    .isTrue();
            assertThat(stampsOf(app, own).decidedAt()).isEqualTo(later);
        }
    }

    @Test
    @DisplayName("deciding a decided proposal under a behind clock is still the machine's or the"
            + " conditional's refusal, never V010's CHECK")
    void anIllegalDecisionUnderABehindClockIsStillRefused() throws SQLException {
        JdbcAdjustmentProposalStore store = new JdbcAdjustmentProposalStore();
        try (Connection app = DatabaseRoles.application()) {
            UUID proposal = insertProposal(app, "person-1");
            AdjustmentProposalId id = AdjustmentProposalId.of(proposal);
            Instant born = stampsOf(app, proposal).proposedAt();
            assertThat(
                            store.decide(
                                    app,
                                    id,
                                    AdjustmentProposalStatus.REJECTED,
                                    "person-2",
                                    born,
                                    Optional.empty()))
                    .isTrue();
            Instant behind = born.minusSeconds(1);

            // The machine: PROPOSED is no decision, refused before any SQL (INV-LIFE-02).
            assertThatThrownBy(
                            () ->
                                    store.decide(
                                            app,
                                            id,
                                            AdjustmentProposalStatus.PROPOSED,
                                            "person-2",
                                            behind,
                                            Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            // The conditional: terminal is terminal (INV-LIFE-04), a second decision zero rows.
            assertThat(
                            store.decide(
                                    app,
                                    id,
                                    AdjustmentProposalStatus.REJECTED,
                                    "person-3",
                                    behind,
                                    Optional.empty()))
                    .isFalse();
            assertThat(stampsOf(app, proposal).decidedAt()).isEqualTo(born);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures - raw SQL on purpose: the subject is the schema, not the domain

    private static void insertEntry(Connection app, UUID entry, String type, String reason)
            throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO ledger.journal_entry (id, posting_date, value_date,"
                                + " entry_type, reference, reason, actor_id, correlation_id,"
                                + " causation_id, idempotency_scope, created_at)"
                                + " VALUES (?, current_date, current_date, ?, 'raw-probe', ?,"
                                + " 'raw-writer', 'raw-corr', 'raw-cause', ?, now())")) {
            insert.setObject(1, entry);
            insert.setString(2, type);
            insert.setString(3, reason);
            insert.setString(4, "raw:" + entry);
            insert.executeUpdate();
        }
    }

    private static void insertBalancedLines(Connection app, UUID entry) throws SQLException {
        UUID clearing = clearingUsd(app);
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id,"
                                + " direction, amount_minor, currency, scale, seq)"
                                + " VALUES (?, ?, ?, ?, 500, 'USD', 2, ?)")) {
            String[] directions = {"DEBIT", "CREDIT"};
            for (int seq = 0; seq < directions.length; seq++) {
                insert.setObject(1, IDS.next());
                insert.setObject(2, entry);
                insert.setObject(3, clearing);
                insert.setString(4, directions[seq]);
                insert.setInt(5, seq);
                insert.executeUpdate();
            }
        }
    }

    private static UUID insertProposal(Connection app, String proposedBy) throws SQLException {
        UUID proposal = IDS.next();
        try (PreparedStatement insert =
                app.prepareStatement(
                        // Back-dated: a test later decides this proposal with an UPDATE that
                        // reads now() again in another transaction, and the local container's
                        // clock is corrected backwards between statements (P1-TSK-031). V010's
                        // ordering CHECK is right and a fixture must not depend on two now()
                        // reads being ordered.
                        "INSERT INTO ledger.adjustment_proposal (id, status, posting_date,"
                                + " value_date, reference, reason, proposed_by, proposed_at)"
                                + " VALUES (?, 'PROPOSED', current_date, current_date,"
                                + " 'raw-probe', 'raw schema probe', ?,"
                                + " now() - interval '1 hour')")) {
            insert.setObject(1, proposal);
            insert.setString(2, proposedBy);
            insert.executeUpdate();
        }
        return proposal;
    }

    private static void insertProposalLine(Connection app, UUID proposal) throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq,"
                                + " ledger_account_id, direction, amount_minor, currency,"
                                + " scale) VALUES (?, 0, ?, 'DEBIT', 500, 'USD', 2)")) {
            insert.setObject(1, proposal);
            insert.setObject(2, clearingUsd(app));
            insert.executeUpdate();
        }
    }

    private static void decide(Connection app, UUID proposal, String decidedBy, UUID entry)
            throws SQLException {
        try (PreparedStatement update =
                app.prepareStatement(
                        "UPDATE ledger.adjustment_proposal SET status = 'APPROVED',"
                                + " decided_by = ?, decided_at = ?,"
                                + " journal_entry_id = ? WHERE id = ?")) {
            update.setString(1, decidedBy);
            update.setObject(2, testClockNow());
            update.setObject(3, entry);
            update.setObject(4, proposal);
            update.executeUpdate();
        }
    }

    /** The proposal's two instants as stored, at the column's own microsecond resolution. */
    private record Stamps(Instant proposedAt, Instant decidedAt) {}

    private static Stamps stampsOf(Connection app, UUID proposal) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT proposed_at, decided_at FROM ledger.adjustment_proposal"
                                + " WHERE id = ?")) {
            read.setObject(1, proposal);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                Timestamp decidedAt = row.getTimestamp(2);
                return new Stamps(
                        row.getTimestamp(1).toInstant(),
                        decidedAt == null ? null : decidedAt.toInstant());
            }
        }
    }

    /**
     * The test's clock at the column's microsecond resolution. The fixtures' decisions are
     * stamped from it, against proposals back-dated an hour, so a decision never precedes its
     * proposal whatever the database's clock does (X-TSK-005, P1-TSK-031).
     */
    private static OffsetDateTime testClockNow() {
        return OffsetDateTime.ofInstant(
                Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static UUID clearingUsd(Connection app) throws SQLException {
        try (PreparedStatement select =
                        app.prepareStatement(
                                "SELECT id FROM ledger.ledger_account"
                                        + " WHERE purpose = 'SETTLEMENT_CLEARING'"
                                        + " AND currency = 'USD'");
                ResultSet row = select.executeQuery()) {
            assertThat(row.next()).as("the operational chart is seeded (P3-TSK-003)").isTrue();
            return row.getObject(1, UUID.class);
        }
    }

    private static boolean entryExists(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT 1 FROM ledger.journal_entry WHERE id = ?")) {
            select.setObject(1, entry);
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
