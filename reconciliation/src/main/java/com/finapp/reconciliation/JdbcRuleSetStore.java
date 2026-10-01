package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link RuleSetStore} over JDBC (ADR-0033: explicit SQL, no mapper). `V012`'s every-writer
 * machine trigger, member trigger, partial uniques and narrowed grant stand beneath every write.
 *
 * <p>The version row and its history carry the CONFIDENTIAL reason ({@code DATA_CLASSIFICATION}
 * §4), so a failure writing either reports its SQLState only — never the driver's message,
 * whose failing-row detail could echo the reason (the {@code JdbcResolutionStore} precedent).
 */
public final class JdbcRuleSetStore implements RuleSetStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS =
            "id, source_id, version, status, proposed_by, decided_by, decided_at, reason,"
                    + " created_at, funding_lag_days, gain_min_age_days, effective_from";

    private static VersionRow row(ResultSet row) throws SQLException {
        return new VersionRow(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getInt("version"),
                RuleSetStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(row.getObject("decided_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                row.getString("reason"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getInt("funding_lag_days"),
                row.getInt("gain_min_age_days"),
                row.getObject("effective_from", LocalDate.class));
    }

    @Override
    public int maxVersion(Connection unitOfWork, UUID sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT COALESCE(MAX(version), 0) AS max_version"
                                + " FROM reconciliation.rule_set WHERE source_id = ?")) {
            read.setObject(1, sourceId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt("max_version");
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the source's highest rule set version", failure);
        }
    }

    @Override
    public Optional<VersionRow> active(Connection unitOfWork, UUID sourceId) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.rule_set"
                        + " WHERE source_id = ? AND status = 'ACTIVE'",
                sourceId,
                "could not read the source's active rule set version");
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, UUID ruleSetId) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.rule_set WHERE id = ? FOR UPDATE",
                ruleSetId,
                "could not lock the rule set version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, UUID sourceId) {
        return one(
                unitOfWork,
                "SELECT " + COLUMNS + " FROM reconciliation.rule_set"
                        + " WHERE source_id = ? AND status = 'ACTIVE' FOR UPDATE",
                sourceId,
                "could not lock the source's active rule set version");
    }

    @Override
    public Set<ExpectationKind> lagKinds(Connection unitOfWork, UUID ruleSetId) {
        Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT expectation_kind FROM reconciliation.rule_set_lag"
                                + " WHERE rule_set_id = ?")) {
            read.setObject(1, ruleSetId);
            try (ResultSet rows = read.executeQuery()) {
                Set<ExpectationKind> kinds = EnumSet.noneOf(ExpectationKind.class);
                while (rows.next()) {
                    kinds.add(ExpectationKind.valueOf(rows.getString("expectation_kind")));
                }
                return Collections.unmodifiableSet(kinds);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the rule set version's lag kinds", failure);
        }
    }

    // ------------------------------------------------------------------ the proposal

    @Override
    public void insertProposal(
            Connection unitOfWork,
            UUID id,
            int version,
            RuleSetProposal proposal,
            LocalDate effectiveFrom,
            Actor proposedBy,
            Instant at,
            CorrelationId correlation) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        // The parent first, at the top level - its xmin is the transaction's own id, the one
        // fact V012's member trigger admits the members by. decided_by and decided_at are left
        // NULL: a version is born undecided (the machine trigger).
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                                + " funding_lag_days, gain_min_age_days, effective_from,"
                                + " proposed_by, reason, created_at, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, proposal.sourceId());
            insert.setInt(3, version);
            insert.setString(4, RuleSetStatus.PROPOSED.name());
            insert.setInt(5, proposal.fundingLagDays());
            insert.setInt(6, proposal.gainMinAgeDays());
            insert.setObject(7, effectiveFrom);
            insert.setString(8, proposedBy.id());
            insert.setString(9, proposal.reason());
            insert.setTimestamp(10, Timestamp.from(at));
            insert.setString(11, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                // rule_set_one_proposed or rule_set_version_once: either way a proposal stands
                // (or just committed) for this source. The unique's detail names the key only.
                throw new RuleSetAdministration.RuleSetProposalPending(failure);
            }
            throw new ReconciliationStorageException(
                    "could not store the rule set proposal (SQLState " + failure.getSQLState()
                            + ")");
        }
        insertLags(unitOfWork, id, proposal.lagDays());
        insertRules(unitOfWork, id, proposal.rules());
        insertTolerances(unitOfWork, id, proposal.tolerances());
        insertFeeSchedules(unitOfWork, id, proposal.feeSchedules());
        insertThresholds(unitOfWork, id, proposal.severityThresholds());
    }

    private static void insertLags(
            Connection unitOfWork, UUID ruleSetId, Map<ExpectationKind, Integer> lagDays) {
        if (lagDays.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                                + " expectation_kind, lag_days) VALUES (?, ?, ?)")) {
            for (Map.Entry<ExpectationKind, Integer> lag : lagDays.entrySet()) {
                insert.setObject(1, ruleSetId);
                insert.setString(2, lag.getKey().name());
                insert.setInt(3, lag.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the proposal's lags", failure);
        }
    }

    private static void insertRules(
            Connection unitOfWork, UUID ruleSetId, List<RuleSetProposal.Rule> rules) {
        if (rules.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                                + " key_kind, expectation_kind, cardinality,"
                                + " operation_anchored, grace_hours)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RuleSetProposal.Rule rule : rules) {
                insert.setObject(1, ruleSetId);
                insert.setInt(2, rule.priority());
                insert.setString(3, rule.lineType().name());
                insert.setString(4, rule.keyKind().orElse(null));
                insert.setString(5, rule.expectationKind().map(Enum::name).orElse(null));
                insert.setString(6, rule.cardinality().name());
                insert.setBoolean(7, rule.operationAnchored());
                insert.setInt(8, rule.graceHours());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the proposal's rules", failure);
        }
    }

    private static void insertTolerances(
            Connection unitOfWork, UUID ruleSetId, List<RuleSetProposal.Tolerance> tolerances) {
        if (tolerances.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                + " currency, absolute_minor, days) VALUES (?, ?, ?, ?, ?)")) {
            for (RuleSetProposal.Tolerance tolerance : tolerances) {
                insert.setObject(1, ruleSetId);
                insert.setString(2, tolerance.comparison());
                insert.setString(3, tolerance.currency().map(CurrencyCode::code).orElse(null));
                insert.setObject(4, tolerance.absoluteMinor().orElse(null), Types.BIGINT);
                insert.setObject(5, tolerance.days().orElse(null), Types.INTEGER);
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the proposal's tolerances", failure);
        }
    }

    private static void insertFeeSchedules(
            Connection unitOfWork, UUID ruleSetId, List<RuleSetProposal.FeeTerms> schedules) {
        if (schedules.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id,"
                                + " line_type, currency, rate, fixed_minor, scale,"
                                + " rounding_policy) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (RuleSetProposal.FeeTerms terms : schedules) {
                insert.setObject(1, ruleSetId);
                insert.setString(2, terms.lineType().name());
                insert.setString(3, terms.currency().code());
                insert.setBigDecimal(4, terms.rate());
                insert.setLong(5, terms.fixedMinor());
                insert.setShort(6, (short) terms.scale());
                insert.setString(7, terms.roundingPolicy());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the proposal's fee schedule", failure);
        }
    }

    private static void insertThresholds(
            Connection unitOfWork, UUID ruleSetId, Map<CurrencyCode, Long> thresholds) {
        if (thresholds.isEmpty()) {
            return;
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                                + " high_value_minor) VALUES (?, ?, ?)")) {
            for (Map.Entry<CurrencyCode, Long> threshold : thresholds.entrySet()) {
                insert.setObject(1, ruleSetId);
                insert.setString(2, threshold.getKey().code());
                insert.setLong(3, threshold.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the proposal's severity thresholds", failure);
        }
    }

    // ------------------------------------------------------------------ the machine

    @Override
    public boolean move(
            Connection unitOfWork,
            UUID id,
            RuleSetStatus from,
            RuleSetStatus to,
            Optional<Actor> decidedBy,
            Instant at) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null; use Optional.empty()");
        Objects.requireNonNull(at, "at must not be null");
        if (!from.canMoveTo(to)) {
            throw new IllegalArgumentException(
                    "not a rule set edge: " + from.name() + " -> " + to.name()
                            + " (ADR-0068 section 8)");
        }
        boolean decision = from == RuleSetStatus.PROPOSED;
        if (decision && decidedBy.isEmpty()) {
            throw new IllegalArgumentException(
                    "a decision names its person: " + from.name() + " -> " + to.name()
                            + " requires decidedBy");
        }
        if (!decision && decidedBy.isPresent()) {
            throw new IllegalArgumentException(
                    "a retirement keeps the activation's decision: decidedBy must be empty");
        }
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        decision
                                ? "UPDATE reconciliation.rule_set SET status = ?,"
                                        + " decided_by = ?, decided_at = ?"
                                        + " WHERE id = ? AND status = ?"
                                : "UPDATE reconciliation.rule_set SET status = ?"
                                        + " WHERE id = ? AND status = ?")) {
            int index = 1;
            update.setString(index++, to.name());
            if (decision) {
                update.setString(index++, decidedBy.get().id());
                update.setTimestamp(index++, Timestamp.from(at));
            }
            update.setObject(index++, id);
            update.setString(index, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            // Never the driver's message: a CHECK refusal's failing row would echo the reason.
            throw new ReconciliationStorageException(
                    "could not move the rule set version (SQLState " + failure.getSQLState()
                            + ")");
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID id,
            Optional<RuleSetStatus> from,
            RuleSetStatus to,
            Actor actor,
            String reason,
            Instant at,
            CorrelationId correlation) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(from, "from must not be null; use Optional.empty()");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.rule_set_event (rule_set_id, from_status,"
                                + " to_status, actor, actor_type, reason, occurred_at,"
                                + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, from.map(Enum::name).orElse(null));
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setString(6, reason);
            insert.setTimestamp(7, Timestamp.from(at));
            insert.setString(8, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            // Never the driver's message: the failing row would echo the reason.
            throw new ReconciliationStorageException(
                    "could not append the rule set version's history (SQLState "
                            + failure.getSQLState() + ")");
        }
    }

    // ------------------------------------------------------------------ the reading

    @Override
    public List<VersionView> versions(Connection unitOfWork, UUID sourceId, int limit) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        Map<UUID, Content> contents = new LinkedHashMap<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM reconciliation.rule_set"
                                + " WHERE source_id = ? ORDER BY version DESC LIMIT ?")) {
            read.setObject(1, sourceId);
            read.setInt(2, limit);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    VersionRow version = row(rows);
                    contents.put(version.id(), new Content(version));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the source's rule set versions", failure);
        }
        if (contents.isEmpty()) {
            return List.of();
        }
        readLags(unitOfWork, contents);
        readRules(unitOfWork, contents);
        readTolerances(unitOfWork, contents);
        readFeeSchedules(unitOfWork, contents);
        readThresholds(unitOfWork, contents);
        return contents.values().stream().map(Content::view).toList();
    }

    /** One version's content, gathered member table by member table. */
    private static final class Content {
        private final VersionRow row;
        private final Map<ExpectationKind, Integer> lagDays =
                new EnumMap<>(ExpectationKind.class);
        private final List<RuleSetProposal.Rule> rules = new ArrayList<>();
        private final List<RuleSetProposal.Tolerance> tolerances = new ArrayList<>();
        private final List<RuleSetProposal.FeeTerms> feeSchedules = new ArrayList<>();
        private final Map<CurrencyCode, Long> severityThresholds = new LinkedHashMap<>();

        private Content(VersionRow row) {
            this.row = row;
        }

        private VersionView view() {
            return new VersionView(
                    row, lagDays, rules, tolerances, feeSchedules, severityThresholds);
        }
    }

    @FunctionalInterface
    private interface MemberReader {
        void read(ResultSet row, Content content) throws SQLException;
    }

    private static void readMembers(
            Connection unitOfWork,
            Map<UUID, Content> contents,
            String sql,
            String what,
            MemberReader reader) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            Array ids = unitOfWork.createArrayOf("uuid", contents.keySet().toArray());
            read.setArray(1, ids);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    reader.read(rows, contents.get(rows.getObject("rule_set_id", UUID.class)));
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the rule set versions' " + what, failure);
        }
    }

    private static void readLags(Connection unitOfWork, Map<UUID, Content> contents) {
        readMembers(
                unitOfWork,
                contents,
                "SELECT rule_set_id, expectation_kind, lag_days FROM reconciliation.rule_set_lag"
                        + " WHERE rule_set_id = ANY (?)",
                "lags",
                (row, content) ->
                        content.lagDays.put(
                                ExpectationKind.valueOf(row.getString("expectation_kind")),
                                row.getInt("lag_days")));
    }

    private static void readRules(Connection unitOfWork, Map<UUID, Content> contents) {
        readMembers(
                unitOfWork,
                contents,
                "SELECT rule_set_id, priority, line_type, key_kind, expectation_kind,"
                        + " cardinality, operation_anchored, grace_hours"
                        + " FROM reconciliation.rule WHERE rule_set_id = ANY (?)"
                        + " ORDER BY rule_set_id, priority",
                "rules",
                (row, content) ->
                        content.rules.add(
                                new RuleSetProposal.Rule(
                                        row.getInt("priority"),
                                        ExternalLineType.valueOf(row.getString("line_type")),
                                        Optional.ofNullable(row.getString("key_kind")),
                                        Optional.ofNullable(row.getString("expectation_kind"))
                                                .map(ExpectationKind::valueOf),
                                        Cardinality.valueOf(row.getString("cardinality")),
                                        row.getBoolean("operation_anchored"),
                                        row.getInt("grace_hours"))));
    }

    private static void readTolerances(Connection unitOfWork, Map<UUID, Content> contents) {
        readMembers(
                unitOfWork,
                contents,
                "SELECT rule_set_id, comparison, currency, absolute_minor, days"
                        + " FROM reconciliation.tolerance WHERE rule_set_id = ANY (?)"
                        + " ORDER BY rule_set_id, comparison, currency NULLS FIRST",
                "tolerances",
                (row, content) ->
                        content.tolerances.add(
                                new RuleSetProposal.Tolerance(
                                        row.getString("comparison"),
                                        Optional.ofNullable(row.getString("currency"))
                                                .map(String::trim)
                                                .map(CurrencyCode::of),
                                        Optional.ofNullable(
                                                row.getObject("absolute_minor", Long.class)),
                                        Optional.ofNullable(
                                                row.getObject("days", Integer.class)))));
    }

    private static void readFeeSchedules(Connection unitOfWork, Map<UUID, Content> contents) {
        readMembers(
                unitOfWork,
                contents,
                "SELECT rule_set_id, line_type, currency, rate, fixed_minor, scale,"
                        + " rounding_policy FROM reconciliation.provider_fee_schedule"
                        + " WHERE rule_set_id = ANY (?)"
                        + " ORDER BY rule_set_id, line_type, currency",
                "fee schedules",
                (row, content) ->
                        content.feeSchedules.add(
                                new RuleSetProposal.FeeTerms(
                                        ExternalLineType.valueOf(row.getString("line_type")),
                                        CurrencyCode.of(row.getString("currency").trim()),
                                        row.getBigDecimal("rate"),
                                        row.getLong("fixed_minor"),
                                        row.getInt("scale"),
                                        row.getString("rounding_policy"))));
    }

    private static void readThresholds(Connection unitOfWork, Map<UUID, Content> contents) {
        readMembers(
                unitOfWork,
                contents,
                "SELECT rule_set_id, currency, high_value_minor"
                        + " FROM reconciliation.severity_threshold"
                        + " WHERE rule_set_id = ANY (?)",
                "severity thresholds",
                (row, content) ->
                        content.severityThresholds.put(
                                CurrencyCode.of(row.getString("currency").trim()),
                                row.getLong("high_value_minor")));
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<VersionRow> one(
            Connection unitOfWork, String sql, UUID id, String what) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet rows = read.executeQuery()) {
                return rows.next() ? Optional.of(row(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(what, failure);
        }
    }
}
