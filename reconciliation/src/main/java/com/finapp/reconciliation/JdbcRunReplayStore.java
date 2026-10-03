package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RunReplayStore} over JDBC (`P8-TSK-022`, ADR-0033: explicit SQL, no ORM). The rule
 * facts, the date window and the fee terms are read through {@link MatchingRules} from each
 * decision's PINNED version - immutable by trigger - so a replay reads exactly what decided.
 */
public final class JdbcRunReplayStore implements RunReplayStore {

    /**
     * A parked original's position account is not a correction-engine input and is not
     * snapshotted; the record requires one, so replay names the nil identifier.
     */
    private static final UUID NOT_SNAPSHOTTED = new UUID(0L, 0L);

    private final MatchingRules rules;

    public JdbcRunReplayStore(MatchingRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    @Override
    public List<DecisionReplay.StoredDecision> storedDecisions(Connection unitOfWork, UUID runId) {
        List<DecisionRow> rows = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT d.id, d.origin, d.rule_set_id, d.rule_priority, d.outcome,"
                                + " d.verdict, d.judged_status, d.judged_minor,"
                                + " d.fingerprint_seen_earlier, d.group_membership_complete,"
                                + " d.fee_gross_minor, d.fee_expected_minor,"
                                + " d.fee_tolerance_minor, i.id AS item_id, i.line_type,"
                                + " i.direction, i.amount_minor, i.currency, i.scale,"
                                + " i.business_date, i.settlement_date, i.value_date,"
                                + " r.settlement_cycle"
                                + " FROM reconciliation.match_decision d"
                                + " JOIN reconciliation.external_item i"
                                + " ON i.id = d.external_item_id"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                                + " WHERE i.run_id = ? OR d.run_id = ?"
                                + " ORDER BY d.decided_at, d.id")) {
            read.setObject(1, runId);
            read.setObject(2, runId);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    rows.add(decisionRow(row));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the run's decisions", failure);
        }
        Map<String, List<MatchingRules.RuleRow>> rulesByVersionAndType = new HashMap<>();
        Map<UUID, Integer> toleranceByVersion = new HashMap<>();
        List<DecisionReplay.StoredDecision> decisions = new ArrayList<>();
        for (DecisionRow row : rows) {
            List<MatchingRules.RuleRow> lineRules =
                    rulesByVersionAndType.computeIfAbsent(
                            row.ruleSetId() + "|" + row.item().lineType(),
                            key -> rules.rulesFor(
                                    unitOfWork, row.ruleSetId(), row.item().lineType()));
            int tolerance =
                    toleranceByVersion.computeIfAbsent(
                            row.ruleSetId(),
                            version -> rules.settlementDateToleranceDays(unitOfWork, version));
            Optional<DecisionReplay.RuleFacts> rule =
                    row.rulePriority()
                            .flatMap(priority ->
                                    lineRules.stream()
                                            .filter(candidate -> candidate.priority() == priority)
                                            .findFirst())
                            .map(found ->
                                    new DecisionReplay.RuleFacts(
                                            found.priority(),
                                            found.keyKind(),
                                            found.expectationKind(),
                                            found.cardinality(),
                                            found.graceHours()));
            boolean anyLanded =
                    lineRules.stream()
                            .anyMatch(candidate -> candidate.cardinality() == Cardinality.ONE_TO_ONE);
            Optional<DecisionReplay.FeeFacts> fee =
                    row.verdict()
                            .filter(verdict -> verdict.engine() == DecisionVerdict.Engine.FEE)
                            .map(verdict ->
                                    new DecisionReplay.FeeFacts(
                                            rules.feeScheduleFor(
                                                    unitOfWork, row.ruleSetId(),
                                                    row.item().lineType(),
                                                    row.item().amount().currency()),
                                            row.feeToleranceMinor().orElse(0L),
                                            row.feeGrossMinor(),
                                            row.feeExpectedMinor().orElse(0L)));
            decisions.add(
                    new DecisionReplay.StoredDecision(
                            row.id(),
                            row.origin(),
                            row.verdict(),
                            row.judgedStatus(),
                            row.judgedMinor(),
                            row.outcome(),
                            row.item(),
                            row.groupDate(),
                            rule,
                            anyLanded,
                            tolerance,
                            candidates(unitOfWork, row.id()),
                            parkedOriginals(unitOfWork, row.id()),
                            row.groupMembershipComplete(),
                            fee,
                            allocations(unitOfWork, row.id())));
        }
        return List.copyOf(decisions);
    }

    private record DecisionRow(
            UUID id,
            DecisionOrigin origin,
            UUID ruleSetId,
            Optional<Integer> rulePriority,
            DecisionOutcome outcome,
            Optional<DecisionVerdict> verdict,
            Optional<JudgedStatus> judgedStatus,
            long judgedMinor,
            Optional<Boolean> groupMembershipComplete,
            Optional<Long> feeGrossMinor,
            Optional<Long> feeExpectedMinor,
            Optional<Long> feeToleranceMinor,
            MatchEngine.ItemFacts item,
            LocalDate groupDate) {}

    private static DecisionRow decisionRow(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").trim());
        LocalDate businessDate = row.getObject("business_date", LocalDate.class);
        Optional<LocalDate> valueDate =
                Optional.ofNullable(row.getObject("value_date", LocalDate.class));
        Optional<String> verdict = Optional.ofNullable(row.getString("verdict"));
        Optional<String> judged = Optional.ofNullable(row.getString("judged_status"));
        Long judgedMinor = row.getObject("judged_minor", Long.class);
        return new DecisionRow(
                row.getObject("id", UUID.class),
                DecisionOrigin.valueOf(row.getString("origin")),
                row.getObject("rule_set_id", UUID.class),
                Optional.ofNullable(row.getObject("rule_priority", Integer.class)),
                DecisionOutcome.valueOf(row.getString("outcome")),
                verdict.map(DecisionVerdict::valueOf),
                judged.map(JudgedStatus::valueOf),
                judgedMinor == null ? 0L : judgedMinor,
                Optional.ofNullable(row.getObject("group_membership_complete", Boolean.class)),
                Optional.ofNullable(row.getObject("fee_gross_minor", Long.class)),
                Optional.ofNullable(row.getObject("fee_expected_minor", Long.class)),
                Optional.ofNullable(row.getObject("fee_tolerance_minor", Long.class)),
                new MatchEngine.ItemFacts(
                        row.getObject("item_id", UUID.class),
                        ExternalLineType.valueOf(row.getString("line_type")),
                        ExpectationDirection.valueOf(row.getString("direction")),
                        Money.ofPersisted(
                                row.getLong("amount_minor"), currency, row.getInt("scale")),
                        businessDate,
                        Optional.ofNullable(row.getObject("settlement_date", LocalDate.class)),
                        Boolean.TRUE.equals(
                                row.getObject("fingerprint_seen_earlier", Boolean.class)),
                        Optional.ofNullable(row.getString("settlement_cycle"))),
                valueDate.orElse(businessDate));
    }

    private static List<MatchEngine.HitFacts> candidates(Connection unitOfWork, UUID decisionId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT c.expectation_id, c.key_kind, c.amount_minor, c.currency,"
                                + " c.scale, c.direction, c.remainder_before_minor,"
                                + " c.opened_at, e.kind, e.expected_by, e.operation_ref,"
                                + " e.settlement_cycle"
                                + " FROM reconciliation.match_candidate c"
                                + " JOIN reconciliation.expectation e ON e.id = c.expectation_id"
                                + " WHERE c.decision_id = ? ORDER BY c.expectation_id")) {
            read.setObject(1, decisionId);
            List<MatchEngine.HitFacts> hits = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    hits.add(
                            new MatchEngine.HitFacts(
                                    row.getObject("expectation_id", UUID.class),
                                    ExpectationKind.valueOf(row.getString("kind")),
                                    ExpectationDirection.valueOf(row.getString("direction")),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(row.getString("currency").trim()),
                                            row.getInt("scale")),
                                    row.getLong("remainder_before_minor"),
                                    row.getTimestamp("opened_at").toInstant(),
                                    row.getObject("expected_by", LocalDate.class),
                                    Optional.ofNullable(row.getString("key_kind"))
                                            .map(KeyKind::valueOf),
                                    row.getString("operation_ref"),
                                    Optional.ofNullable(row.getString("settlement_cycle"))));
                }
            }
            return hits;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the decision's candidates", failure);
        }
    }

    private static List<CorrectionEngine.ParkedOriginal> parkedOriginals(
            Connection unitOfWork, UUID decisionId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT original_item_id, suspense_item_id, break_id, side,"
                                + " remainder_minor, currency, scale"
                                + " FROM reconciliation.match_parked_original"
                                + " WHERE decision_id = ? ORDER BY ordinal")) {
            read.setObject(1, decisionId);
            List<CorrectionEngine.ParkedOriginal> originals = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    originals.add(
                            new CorrectionEngine.ParkedOriginal(
                                    row.getObject("original_item_id", UUID.class),
                                    row.getObject("suspense_item_id", UUID.class),
                                    row.getObject("break_id", UUID.class),
                                    SuspenseSide.valueOf(row.getString("side")),
                                    row.getLong("remainder_minor"),
                                    CurrencyCode.of(row.getString("currency").trim()),
                                    row.getInt("scale"),
                                    NOT_SNAPSHOTTED));
                }
            }
            return originals;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the decision's parked originals", failure);
        }
    }

    private static List<DecisionReplay.Allocation> allocations(
            Connection unitOfWork, UUID decisionId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT expectation_id, amount_minor FROM reconciliation.allocation"
                                + " WHERE decision_id = ? AND reverses_allocation_id IS NULL")) {
            read.setObject(1, decisionId);
            List<DecisionReplay.Allocation> allocations = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    allocations.add(
                            new DecisionReplay.Allocation(
                                    row.getObject("expectation_id", UUID.class),
                                    row.getLong("amount_minor")));
                }
            }
            return allocations;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the decision's allocations", failure);
        }
    }

    @Override
    public void insertReplay(Connection unitOfWork, NewReplay replay) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.run_replay (id, run_id, requested_by,"
                                + " requested_by_type, verdict, replayed, not_replayed,"
                                + " divergences, pending_rematch, first_divergent_decision,"
                                + " at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, replay.id());
            insert.setObject(2, replay.runId());
            insert.setString(3, replay.requestedBy().id());
            insert.setString(4, replay.requestedBy().type().name());
            insert.setString(5, replay.verdict());
            insert.setInt(6, replay.replayed());
            insert.setInt(7, replay.notReplayed());
            insert.setInt(8, replay.divergences());
            insert.setInt(9, replay.pendingRematch());
            insert.setObject(10, replay.firstDivergentDecision().orElse(null));
            insert.setTimestamp(11, Timestamp.from(replay.at()));
            insert.setString(12, replay.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not append the replay", failure);
        }
    }
}
