package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link ResolutionStore} over JDBC (ADR-0033: explicit SQL, no mapper). `V007`'s every-writer
 * transition trigger and narrowed grant stand beneath every write here.
 */
public final class JdbcResolutionStore implements ResolutionStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS =
            "id, break_id, kind, status, reason_code, four_eyes, proposed_amount_minor,"
                    + " currency, scale, residual_version, target_account_id, offset_item_id,"
                    + " chosen_expectation_id, rule_set_id, adjustment_proposal_id,"
                    + " journal_entry_id, proposed_by, proposed_at, decided_by";

    private static ResolutionRow row(ResultSet row) throws SQLException {
        return new ResolutionRow(
                row.getObject("id", UUID.class),
                row.getObject("break_id", UUID.class),
                ResolutionKind.valueOf(row.getString("kind")),
                ResolutionStatus.valueOf(row.getString("status")),
                ResolutionReasonCode.valueOf(row.getString("reason_code")),
                row.getBoolean("four_eyes"),
                row.getLong("proposed_amount_minor"),
                row.getString("currency").trim(),
                row.getInt("scale"),
                row.getLong("residual_version"),
                Optional.ofNullable(row.getObject("target_account_id", UUID.class)),
                Optional.ofNullable(row.getObject("offset_item_id", UUID.class)),
                Optional.ofNullable(row.getObject("chosen_expectation_id", UUID.class)),
                row.getObject("rule_set_id", UUID.class),
                Optional.ofNullable(row.getObject("adjustment_proposal_id", UUID.class)),
                Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)),
                row.getString("proposed_by"),
                JdbcBreakInquiries.instant(row, "proposed_at"),
                Optional.ofNullable(row.getString("decided_by")));
    }

    @Override
    public void insert(Connection unitOfWork, NewResolution resolution) {
        boolean decided = resolution.status() != ResolutionStatus.PROPOSED;
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.resolution (id, break_id, kind, status,"
                                + " reason_code, narrative, four_eyes, proposed_amount_minor,"
                                + " currency, scale, residual_version, target_account_id,"
                                + " offset_item_id, chosen_expectation_id, rule_set_id,"
                                + " adjustment_proposal_id, proposed_by, proposed_by_type,"
                                + " proposed_at, decided_by, decided_by_type, decided_at,"
                                + " created_at, status_changed_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, resolution.id());
            insert.setObject(2, resolution.breakId());
            insert.setString(3, resolution.kind().name());
            insert.setString(4, resolution.status().name());
            insert.setString(5, resolution.reasonCode().name());
            insert.setString(6, resolution.narrative());
            insert.setBoolean(7, resolution.fourEyes());
            insert.setLong(8, resolution.proposedAmount().minorUnits());
            insert.setString(9, resolution.proposedAmount().currency().code());
            insert.setInt(10, resolution.proposedAmount().scale());
            insert.setLong(11, resolution.residualVersion());
            insert.setObject(12, resolution.targetAccountId().orElse(null));
            insert.setObject(13, resolution.offsetItemId().orElse(null));
            insert.setObject(14, resolution.chosenExpectationId().orElse(null));
            insert.setObject(15, resolution.ruleSetId());
            insert.setObject(16, resolution.adjustmentProposalId().orElse(null));
            insert.setString(17, resolution.proposedBy().id());
            insert.setString(18, resolution.proposedBy().type().name());
            insert.setTimestamp(19, Timestamp.from(resolution.at()));
            insert.setString(20, decided ? resolution.proposedBy().id() : null);
            insert.setString(21, decided ? resolution.proposedBy().type().name() : null);
            insert.setTimestamp(22, decided ? Timestamp.from(resolution.at()) : null);
            insert.setTimestamp(23, Timestamp.from(resolution.at()));
            insert.setTimestamp(24, Timestamp.from(resolution.at()));
            insert.setString(25, resolution.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new OneLiveProposal(failure);
            }
            // Never the driver's message: a CHECK violation may echo the narrative.
            throw new ReconciliationStorageException(
                    "could not store the resolution (SQLState " + failure.getSQLState() + ")");
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID resolutionId,
            Optional<ResolutionStatus> from,
            ResolutionStatus to,
            Actor actor,
            Optional<String> reason,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.resolution_event (resolution_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, resolutionId);
            insert.setString(2, from.map(Enum::name).orElse(null));
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, reason.orElse(null));
            insert.setTimestamp(7, Timestamp.from(at));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the resolution's history (SQLState "
                            + failure.getSQLState() + ")");
        }
    }

    @Override
    public Optional<ResolutionRow> byId(Connection unitOfWork, UUID resolutionId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.resolution WHERE id = ?",
                resolutionId,
                JdbcResolutionStore::row);
    }

    @Override
    public Optional<ResolutionRow> lockById(Connection unitOfWork, UUID resolutionId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.resolution WHERE id = ? FOR UPDATE",
                resolutionId,
                JdbcResolutionStore::row);
    }

    @Override
    public Optional<ResolutionRow> lockProposedOf(Connection unitOfWork, UUID breakId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.resolution WHERE break_id = ?"
                        + " AND status = 'PROPOSED' FOR UPDATE",
                breakId,
                JdbcResolutionStore::row);
    }

    @Override
    public boolean decide(
            Connection unitOfWork,
            UUID resolutionId,
            ResolutionStatus to,
            Actor decidedBy,
            Instant at,
            Optional<UUID> journalEntryId,
            Optional<UUID> decisionId,
            Optional<UUID> parkId) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.resolution SET status = ?, decided_by = ?,"
                                + " decided_by_type = ?, decided_at = ?, status_changed_at = ?,"
                                + " journal_entry_id = COALESCE(?, journal_entry_id),"
                                + " decision_id = COALESCE(?, decision_id),"
                                + " park_id = COALESCE(?, park_id)"
                                + " WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy.id());
            update.setString(3, decidedBy.type().name());
            update.setTimestamp(4, Timestamp.from(at));
            update.setTimestamp(5, Timestamp.from(at));
            update.setObject(6, journalEntryId.orElse(null));
            update.setObject(7, decisionId.orElse(null));
            update.setObject(8, parkId.orElse(null));
            update.setObject(9, resolutionId);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not decide the resolution (SQLState " + failure.getSQLState() + ")",
                    failure);
        }
    }

    // ------------------------------------------------------------------ the break

    @Override
    public boolean moveBreak(
            Connection unitOfWork, UUID breakId, BreakStatus from, BreakStatus to, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET status = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, breakId);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not move the break", failure);
        }
    }

    @Override
    public boolean resolveBreak(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            ResolutionKind kind,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at = ?,"
                                + " status_changed_at = ? WHERE id = ? AND status <> 'RESOLVED'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, breakId);
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not resolve the break", failure);
        }
        try (PreparedStatement history =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_event (break_id, event_type, actor,"
                                + " actor_type, reason, detail, occurred_at, correlation_id)"
                                + " VALUES (?, 'RESOLVED', ?, ?, NULL, ?, ?, ?)")) {
            history.setObject(1, breakId);
            history.setString(2, actor.id());
            history.setString(3, actor.type().name());
            history.setString(4, "resolution=" + resolutionId + ", kind=" + kind.name());
            history.setTimestamp(5, Timestamp.from(at));
            history.setString(6, correlation.value());
            history.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the break's history", failure);
        }
        return true;
    }

    // ------------------------------------------------------------------ the subject

    @Override
    public Optional<ExpectationHolding> lockExpectation(Connection unitOfWork, UUID expectationId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT id, direction, status, amount_minor - allocated_minor - resolved_minor"
                        + " AS remainder, currency, scale, ledger_account_id"
                        + " FROM reconciliation.expectation WHERE id = ? FOR UPDATE",
                expectationId,
                row ->
                        new ExpectationHolding(
                                row.getObject("id", UUID.class),
                                ExpectationDirection.valueOf(row.getString("direction")),
                                ExpectationStatus.valueOf(row.getString("status")),
                                row.getLong("remainder"),
                                row.getString("currency").trim(),
                                row.getInt("scale"),
                                row.getObject("ledger_account_id", UUID.class)));
    }

    @Override
    public List<RemainderSibling> lockRemainderSiblings(
            Connection unitOfWork, UUID expectationId, UUID exceptBreakId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id, status FROM reconciliation.break WHERE expectation_id = ?"
                                + " AND type IN ('AMOUNT_MISMATCH', 'MISSING_EXTERNAL')"
                                + " AND status <> 'RESOLVED' AND id <> ?"
                                + " ORDER BY id FOR UPDATE")) {
            read.setObject(1, expectationId);
            read.setObject(2, exceptBreakId);
            try (ResultSet rows = read.executeQuery()) {
                List<RemainderSibling> siblings = new java.util.ArrayList<>();
                while (rows.next()) {
                    siblings.add(
                            new RemainderSibling(
                                    rows.getObject("id", UUID.class),
                                    BreakStatus.valueOf(rows.getString("status"))));
                }
                return List.copyOf(siblings);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not lock the remainder's other breaks", failure);
        }
    }

    private static final String SUSPENSE_COLUMNS =
            "SELECT id, break_id, external_item_id, side, status,"
                    + " amount_minor - released_minor AS unreleased, currency, scale,"
                    + " position_account_id, opened_on FROM reconciliation.suspense_item";

    private static SuspenseHolding suspense(ResultSet row) throws SQLException {
        return new SuspenseHolding(
                row.getObject("id", UUID.class),
                row.getObject("break_id", UUID.class),
                Optional.ofNullable(row.getObject("external_item_id", UUID.class)),
                SuspenseSide.valueOf(row.getString("side")),
                SuspenseItemStatus.valueOf(row.getString("status")),
                row.getLong("unreleased"),
                row.getString("currency").trim(),
                row.getInt("scale"),
                row.getObject("position_account_id", UUID.class),
                row.getObject("opened_on", LocalDate.class));
    }

    @Override
    public List<SuspenseHolding> lockOpenSuspenseOf(Connection unitOfWork, UUID breakId) {
        return JdbcBreakInquiries.list(
                unitOfWork,
                SUSPENSE_COLUMNS + " WHERE break_id = ? AND status IN ('OPEN',"
                        + " 'PARTIALLY_RELEASED') AND released_minor < amount_minor"
                        + " ORDER BY id FOR UPDATE",
                breakId,
                JdbcResolutionStore::suspense);
    }

    @Override
    public Optional<SuspenseHolding> lockSuspenseItem(Connection unitOfWork, UUID suspenseItemId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                SUSPENSE_COLUMNS + " WHERE id = ? FOR UPDATE",
                suspenseItemId,
                JdbcResolutionStore::suspense);
    }

    @Override
    public Optional<UUID> breakOfSuspenseItem(Connection unitOfWork, UUID suspenseItemId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT break_id FROM reconciliation.suspense_item WHERE id = ?",
                suspenseItemId,
                row -> row.getObject("break_id", UUID.class));
    }

    @Override
    public boolean gainEligible(Connection unitOfWork, UUID suspenseItemId, UUID ruleSetId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT (current_date - s.opened_on) >= r.gain_min_age_days"
                                + " FROM reconciliation.suspense_item s,"
                                + " reconciliation.rule_set r WHERE s.id = ? AND r.id = ?")) {
            read.setObject(1, suspenseItemId);
            read.setObject(2, ruleSetId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not judge the gain's minimum age", failure);
        }
    }

    @Override
    public boolean resolveExpectation(
            Connection unitOfWork,
            UUID expectationId,
            long amountMinor,
            UUID resolutionId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        if (!resolveExpectationRow(unitOfWork, expectationId, amountMinor, at)) {
            return false;
        }
        try (PreparedStatement history =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.expectation_event (expectation_id,"
                                + " event_type, detail, actor, actor_type, occurred_at,"
                                + " correlation_id) VALUES (?, 'RESOLVED', ?, ?, ?, ?, ?)")) {
            history.setObject(1, expectationId);
            history.setString(2, "resolution=" + resolutionId);
            history.setString(3, actor.id());
            history.setString(4, actor.type().name());
            history.setTimestamp(5, Timestamp.from(at));
            history.setString(6, correlation.value());
            history.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the expectation's history", failure);
        }
        return true;
    }

    private static boolean resolveExpectationRow(
            Connection unitOfWork, UUID expectationId, long amountMinor, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.expectation SET resolved_minor = resolved_minor + ?,"
                                + " status = 'RESOLVED_BY_ADJUSTMENT', status_changed_at = ?"
                                + " WHERE id = ? AND status IN ('OPEN', 'PARTIALLY_SETTLED')"
                                + " AND amount_minor - allocated_minor - resolved_minor = ?")) {
            update.setLong(1, amountMinor);
            update.setTimestamp(2, Timestamp.from(at));
            update.setObject(3, expectationId);
            update.setLong(4, amountMinor);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the expectation", failure);
        }
    }

    @Override
    public Map<UUID, KeyKind> storedCandidatesOf(Connection unitOfWork, UUID externalItemId) {
        Map<UUID, KeyKind> candidates = new TreeMap<>();
        for (Map.Entry<UUID, KeyKind> candidate :
                JdbcBreakInquiries.list(
                        unitOfWork,
                        "SELECT c.expectation_id, c.key_kind"
                                + " FROM reconciliation.match_candidate c"
                                + " JOIN reconciliation.match_decision d"
                                + " ON d.id = c.decision_id"
                                + " WHERE d.external_item_id = ?"
                                + " ORDER BY d.decided_at, c.expectation_id",
                        externalItemId,
                        row ->
                                Map.entry(
                                        row.getObject("expectation_id", UUID.class),
                                        KeyKind.valueOf(row.getString("key_kind"))))) {
            candidates.putIfAbsent(candidate.getKey(), candidate.getValue());
        }
        return Map.copyOf(candidates);
    }

    @Override
    public Optional<ExpectationDirection> itemDirection(
            Connection unitOfWork, UUID externalItemId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT direction FROM reconciliation.external_item WHERE id = ?",
                externalItemId,
                row -> ExpectationDirection.valueOf(row.getString("direction")));
    }

    @Override
    public Optional<UUID> runOfItem(Connection unitOfWork, UUID externalItemId) {
        return JdbcBreakInquiries.one(
                unitOfWork,
                "SELECT run_id FROM reconciliation.external_item WHERE id = ?",
                externalItemId,
                row -> row.getObject("run_id", UUID.class));
    }
}
