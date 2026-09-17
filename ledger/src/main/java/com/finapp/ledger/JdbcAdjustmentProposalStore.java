package com.finapp.ledger;

import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC storage for adjustment proposals (ADR-0033, `P3-TSK-021`). */
public final class JdbcAdjustmentProposalStore
        implements AdjustmentProposalStore<Connection> {

    private static final String TABLE = "ledger.adjustment_proposal";
    private static final String LINES = "ledger.adjustment_proposal_line";

    private static final String COLUMNS =
            "id, status, posting_date, value_date, reference, reason, proposed_by,"
                    + " proposed_at, decided_by, decided_at, journal_entry_id";

    @Override
    public void insert(Connection unitOfWork, AdjustmentProposal proposal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)")) {
            insert.setObject(1, proposal.id().value());
            insert.setString(2, proposal.status().name());
            insert.setDate(3, Date.valueOf(proposal.postingDate()));
            insert.setDate(4, Date.valueOf(proposal.valueDate()));
            insert.setString(5, proposal.reference());
            insert.setString(6, proposal.reason());
            insert.setString(7, proposal.proposedBy());
            insert.setTimestamp(8, Timestamp.from(proposal.proposedAt()));
            insert.executeUpdate();
            try (PreparedStatement line =
                    unitOfWork.prepareStatement(
                            "INSERT INTO " + LINES
                                    + " (proposal_id, seq, ledger_account_id, direction,"
                                    + " amount_minor, currency, scale)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                short seq = 0;
                for (JournalLine each : proposal.lines()) {
                    line.setObject(1, proposal.id().value());
                    line.setShort(2, seq++);
                    line.setObject(3, each.account().value());
                    line.setString(4, each.direction().name());
                    line.setLong(5, MoneyColumns.amountMinorOf(each.amount()));
                    line.setString(6, MoneyColumns.currencyOf(each.amount()));
                    line.setShort(7, MoneyColumns.scaleOf(each.amount()));
                    line.executeUpdate();
                }
            }
        } catch (SQLException failure) {
            // The line FK and V005's composite currency binding (23503): a line named an
            // unknown account or a foreign currency - the JdbcJournalEntryStore translation,
            // firing at PROPOSAL time so the initiator learns before any second person is
            // asked to read a proposal that could never post. SQLState, never prose.
            if ("23503".equals(failure.getSQLState())) {
                throw new UnknownPostingAccountException(
                        "a proposal line names an unknown ledger account, or a currency"
                                + " foreign to it (proposal " + proposal.id() + ")");
            }
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "inserting adjustment proposal " + proposal.id(), failure));
        }
    }

    @Override
    public Optional<AdjustmentProposal> findById(
            Connection unitOfWork, AdjustmentProposalId id) {
        return read(unitOfWork, id, false);
    }

    @Override
    public Optional<AdjustmentProposal> lockById(
            Connection unitOfWork, AdjustmentProposalId id) {
        return read(unitOfWork, id, true);
    }

    private Optional<AdjustmentProposal> read(
            Connection unitOfWork, AdjustmentProposalId id, boolean forUpdate) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?"
                                // The approval's serialisation point (P2-TSK-015's
                                // lock-then-look): the row lock, then fresh statements.
                                + (forUpdate ? " FOR UPDATE" : ""))) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(unitOfWork, row));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading adjustment proposal " + id, failure));
        }
    }

    @Override
    public boolean decide(
            Connection unitOfWork,
            AdjustmentProposalId id,
            AdjustmentProposalStatus to,
            String decidedBy,
            Instant decidedAt,
            Optional<JournalEntryId> entry) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!to.isTerminal()) {
            throw new IllegalArgumentException(
                    "a decision moves a proposal to a terminal state (INV-LIFE-02)");
        }
        try (PreparedStatement decide =
                unitOfWork.prepareStatement(
                        // The row count is the outcome; status = 'PROPOSED' is the belt
                        // under the caller's lock, so an unlocked caller still cannot
                        // decide twice.
                        "UPDATE " + TABLE
                                + " SET status = ?, decided_by = ?, decided_at = ?,"
                                + " journal_entry_id = ?"
                                + " WHERE id = ? AND status = 'PROPOSED'")) {
            decide.setString(1, to.name());
            decide.setString(2, decidedBy);
            decide.setTimestamp(3, Timestamp.from(decidedAt));
            decide.setObject(4, entry.map(JournalEntryId::value).orElse(null));
            decide.setObject(5, id.value());
            return decide.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("deciding adjustment proposal " + id, failure));
        }
    }

    private AdjustmentProposal rehydrate(Connection unitOfWork, ResultSet row)
            throws SQLException {
        AdjustmentProposalId id =
                AdjustmentProposalId.of(row.getObject("id", UUID.class));
        Timestamp decidedAt = row.getTimestamp("decided_at");
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        return new AdjustmentProposal(
                id,
                AdjustmentProposalStatus.valueOf(row.getString("status")),
                row.getDate("posting_date").toLocalDate(),
                row.getDate("value_date").toLocalDate(),
                row.getString("reference"),
                row.getString("reason"),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                linesOf(unitOfWork, id),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                Optional.ofNullable(entry).map(JournalEntryId::of));
    }

    private List<JournalLine> linesOf(Connection unitOfWork, AdjustmentProposalId id)
            throws SQLException {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT ledger_account_id, direction, amount_minor, currency, scale"
                                + " FROM " + LINES + " WHERE proposal_id = ? ORDER BY seq")) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                List<JournalLine> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(
                            new JournalLine(
                                    LedgerAccountId.of(
                                            rows.getObject("ledger_account_id", UUID.class)),
                                    Direction.valueOf(rows.getString("direction")),
                                    MoneyColumns.read(
                                            rows.getLong("amount_minor"),
                                            rows.getString("currency"),
                                            rows.getShort("scale"))));
                }
                return List.copyOf(lines);
            }
        }
    }
}
