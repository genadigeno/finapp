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
import java.time.Clock;
import java.time.ZoneOffset;
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
            // A legitimate rejection through the granted columns, so a terminal row exists.
            execute(
                    app,
                    "UPDATE ledger.adjustment_proposal SET status = 'REJECTED',"
                            + " decided_by = 'person-2', decided_at = now() WHERE id = ?",
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

    // -----------------------------------------------------------------
    // The reason-code regime and the binding at DB-CONSTRAINT rank (P8-TSK-006)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a raw insert without a code - and one stating UNCODED - is refused for"
            + " every writer: the default is history's backfill value (INV-REV-04)")
    void aRawInsertWithoutACodeIsRefused() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                // Omitting the column lands on the UNCODED default; stating it is the same
                // insert spelled out - one trigger refuses both.
                assertThatThrownBy(
                                () ->
                                        execute(
                                                app,
                                                "INSERT INTO ledger.adjustment_proposal (id,"
                                                        + " status, posting_date, value_date,"
                                                        + " reference, reason, proposed_by,"
                                                        + " proposed_at) VALUES (?, 'PROPOSED',"
                                                        + " current_date, current_date,"
                                                        + " 'raw-probe', 'no code', 'person-1',"
                                                        + " now())",
                                                IDS.next()))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("INV-REV-04");
            } finally {
                app.rollback();
            }
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try {
                assertThatThrownBy(
                                () ->
                                        execute(
                                                migrator,
                                                "INSERT INTO ledger.adjustment_proposal (id,"
                                                        + " status, posting_date, value_date,"
                                                        + " reference, reason, reason_code,"
                                                        + " origin, proposed_by, proposed_at)"
                                                        + " VALUES (?, 'PROPOSED',"
                                                        + " current_date, current_date,"
                                                        + " 'raw-probe', 'uncoded', 'UNCODED',"
                                                        + " 'MANUAL', 'person-1', now())",
                                                IDS.next()))
                        .as("the migrator too: the trigger binds every writer")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("INV-REV-04");
            } finally {
                migrator.rollback();
            }
        }
    }

    @Test
    @DisplayName("a MANUAL line on a reconciled position is unstorable at the schema, a"
            + " RECONCILIATION one stores, and the pairing is a CHECK (P8-TSK-006, ADR-0071)")
    void theBindingAndThePairingHoldAtTheSchema() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                UUID manual = insertProposal(app, "person-1");
                Savepoint beforeTheProbe = app.setSavepoint();
                assertThatThrownBy(
                                () -> insertProposalLine(app, manual, clearingUsd(app)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ADR-0071");
                app.rollback(beforeTheProbe);
                // The positive controls, so the refusal is the binding and not the insert:
                // the same MANUAL line off the positions stores, and a RECONCILIATION
                // proposal's line ON one stores - that is the door resolutions use.
                assertThatCode(() -> insertProposalLine(app, manual, feeRevenueUsd(app)))
                        .doesNotThrowAnyException();
                UUID owned =
                        insertProposal(
                                app, "person-1", "RECONCILIATION_WRITE_OFF", "RECONCILIATION");
                assertThatCode(() -> insertProposalLine(app, owned, clearingUsd(app)))
                        .doesNotThrowAnyException();

                // The pairing: a RECONCILIATION_* code with a MANUAL origin is unstorable.
                Savepoint beforeThePairing = app.setSavepoint();
                assertThatThrownBy(
                                () ->
                                        insertProposal(
                                                app,
                                                "person-1",
                                                "RECONCILIATION_GAIN",
                                                "MANUAL"))
                        .isInstanceOf(SQLException.class)
                        .hasFieldOrPropertyWithValue("SQLState", "23514")
                        .hasMessageContaining("adjustment_proposal_code_matches_origin");
                app.rollback(beforeThePairing);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the new columns join the frozen payload: reason_code and origin are"
            + " immutable for every writer, the migrator included (V015)")
    void theNewColumnsAreFrozenForEveryWriter() throws SQLException {
        UUID standing;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            standing = insertProposal(app, "person-1");
            app.commit();
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE ledger.adjustment_proposal"
                                                    + " SET reason_code ="
                                                    + " 'RECONCILIATION_GAIN',"
                                                    + " origin = 'RECONCILIATION'"
                                                    + " WHERE id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("approver approves what they read");
        }
        // The application role cannot touch them at all: the UPDATE grant is still the
        // decision's four columns (V015 grants nothing new).
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE ledger.adjustment_proposal"
                                                    + " SET origin = 'RECONCILIATION'"
                                                    + " WHERE id = ?",
                                            standing))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
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
        // MANUAL_CORRECTION stated explicitly since P8-TSK-006: the column's UNCODED
        // default is history's backfill value, and V015's trigger refuses it on INSERT -
        // which has its own test above.
        return insertProposal(app, proposedBy, "MANUAL_CORRECTION", "MANUAL");
    }

    private static UUID insertProposal(
            Connection app, String proposedBy, String reasonCode, String origin)
            throws SQLException {
        UUID proposal = IDS.next();
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO ledger.adjustment_proposal (id, status, posting_date,"
                                + " value_date, reference, reason, reason_code, origin,"
                                + " proposed_by, proposed_at)"
                                + " VALUES (?, 'PROPOSED', current_date, current_date,"
                                + " 'raw-probe', 'raw schema probe', ?, ?, ?, now())")) {
            insert.setObject(1, proposal);
            insert.setString(2, reasonCode);
            insert.setString(3, origin);
            insert.setString(4, proposedBy);
            insert.executeUpdate();
        }
        return proposal;
    }

    private static void insertProposalLine(Connection app, UUID proposal) throws SQLException {
        // FEE_REVENUE since P8-TSK-006: this fixture wanted "some seeded account", and a
        // MANUAL line on the clearing is now the binding's own refusal, tested above.
        insertProposalLine(app, proposal, feeRevenueUsd(app));
    }

    private static void insertProposalLine(Connection app, UUID proposal, UUID account)
            throws SQLException {
        try (PreparedStatement insert =
                app.prepareStatement(
                        "INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq,"
                                + " ledger_account_id, direction, amount_minor, currency,"
                                + " scale) VALUES (?, 0, ?, 'DEBIT', 500, 'USD', 2)")) {
            insert.setObject(1, proposal);
            insert.setObject(2, account);
            insert.executeUpdate();
        }
    }

    private static void decide(Connection app, UUID proposal, String decidedBy, UUID entry)
            throws SQLException {
        try (PreparedStatement update =
                app.prepareStatement(
                        "UPDATE ledger.adjustment_proposal SET status = 'APPROVED',"
                                + " decided_by = ?, decided_at = now(),"
                                + " journal_entry_id = ? WHERE id = ?")) {
            update.setString(1, decidedBy);
            update.setObject(2, entry);
            update.setObject(3, proposal);
            update.executeUpdate();
        }
    }

    private static UUID clearingUsd(Connection app) throws SQLException {
        return operationalUsd(app, "SETTLEMENT_CLEARING");
    }

    private static UUID feeRevenueUsd(Connection app) throws SQLException {
        return operationalUsd(app, "FEE_REVENUE");
    }

    private static UUID operationalUsd(Connection app, String purpose) throws SQLException {
        try (PreparedStatement select =
                app.prepareStatement(
                        "SELECT id FROM ledger.ledger_account"
                                + " WHERE purpose = ? AND currency = 'USD'")) {
            select.setString(1, purpose);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next())
                        .as("the operational chart is seeded (P3-TSK-003)")
                        .isTrue();
                return row.getObject(1, UUID.class);
            }
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
