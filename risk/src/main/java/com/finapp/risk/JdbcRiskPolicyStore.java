package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link RiskPolicyStore} over plain JDBC (`P13-TSK-002`, ADR-0033; bnpl's {@code JdbcBnplTermsStore}): every time the
 * database's, every move a conditional {@code UPDATE} of the machine row's decision columns under {@code risk V002}'s
 * edge trigger, the content and the history INSERT-only, every failure its operation and SQLSTATE only
 * ({@link RiskStorageException}).
 */
public final class JdbcRiskPolicyStore implements RiskPolicyStore {

    /**
     * risk's administration namespace (`DISTRIBUTED_EXECUTION.md` section 3, reserved by the Phase 12 → 13 transition
     * and registered here, by its first writer): every proposal and decision of one operation's policy serialises on
     * {@code (13, hashtext(operation))}, and a pin whose first read found no {@code ACTIVE} row takes it shared.
     */
    public static final int NAMESPACE = 13;

    /** A0's statement, pinned by {@code RiskPolicyMigrationTest#namespaceThirteenIsPinned}. */
    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", hashtext(?))";

    /** A0 in SHARED mode - the pin's re-read (the A3-I1 rule). Pinned beside {@link #LOCK_SQL}. */
    static final String SHARED_LOCK_SQL = "SELECT pg_advisory_xact_lock_shared(" + NAMESPACE + ", hashtext(?))";

    /** The pin's one read: the {@code ACTIVE} row {@code FOR SHARE} - (R1). */
    static final String SHARE_ACTIVE_SQL =
            "SELECT id FROM risk.risk_policy_version WHERE operation = ? AND status = 'ACTIVE' FOR SHARE";

    private static final String ROW_COLUMNS =
            "id, operation, version, engine, status, proposed_by, proposed_at, decided_by, decided_at, effective_from,"
                    + " effective_to";

    @Override
    public void lockOperation(Connection unitOfWork, RiskOperation operation) {
        try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_SQL)) {
            lock.setString(1, operation.name());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the policy administration lock", failure);
        }
    }

    @Override
    public void lockOperationShared(Connection unitOfWork, RiskOperation operation) {
        try (PreparedStatement lock = unitOfWork.prepareStatement(SHARED_LOCK_SQL)) {
            lock.setString(1, operation.name());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the policy administration lock shared", failure);
        }
    }

    @Override
    public int maxVersion(Connection unitOfWork, RiskOperation operation) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT COALESCE(max(version), 0) FROM risk.risk_policy_version WHERE operation = ?")) {
            select.setString(1, operation.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("reading the latest policy version number", failure);
        }
    }

    @Override
    public void insertProposal(Connection unitOfWork, NewProposal proposal) {
        RiskPolicy policy = proposal.policy();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO risk.risk_policy_version (id, operation, version, engine, status, proposed_by,"
                            + " proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                            + " VALUES (?, ?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)")) {
                insert.setObject(1, proposal.id().value());
                insert.setString(2, policy.operation().name());
                insert.setInt(3, proposal.version());
                insert.setString(4, policy.engine().name());
                insert.setString(5, proposal.proposer().id());
                insert.setString(6, proposal.proposer().type().name());
                insert.setString(7, proposal.reason());
                insert.setString(8, proposal.correlation().value());
                insert.executeUpdate();
            }
            insertContent(unitOfWork, proposal.id(), policy);
            appendEvent(unitOfWork, new HistoryRow(proposal.eventId(), proposal.id(), policy.operation(),
                    Optional.empty(), RiskPolicyVersionStatus.PROPOSED, proposal.proposer(), proposal.reason(),
                    proposal.correlation()));
        } catch (SQLException failure) {
            throw failed("recording a policy version's proposal", failure);
        }
    }

    private static void insertContent(Connection unitOfWork, RiskPolicyVersionId id, RiskPolicy policy)
            throws SQLException {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO risk.risk_policy (policy_version_id, operation, currency, scale, challenge_assurance,"
                        + " override_threshold_minor, hold_validity_hours) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id.value());
            insert.setString(2, policy.operation().name());
            insert.setString(3, policy.currency().code());
            insert.setInt(4, policy.currency().minorUnits());
            insert.setString(5, policy.challengeAssurance().map(Enum::name).orElse(null));
            if (policy.overrideThreshold().isPresent()) {
                insert.setLong(6, policy.overrideThreshold().get().minorUnits());
            } else {
                insert.setNull(6, Types.BIGINT);
            }
            if (policy.holdValidity().isPresent()) {
                insert.setInt(7, (int) policy.holdValidity().get().toHours());
            } else {
                insert.setNull(7, Types.INTEGER);
            }
            insert.executeUpdate();
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                        "INSERT INTO risk.risk_rule (policy_version_id, ordinal, kind, weight, hard_decision)"
                                + " VALUES (?, ?, ?, ?, ?)");
                PreparedStatement parameter = unitOfWork.prepareStatement(
                        "INSERT INTO risk.risk_rule_parameter (policy_version_id, rule_ordinal, name, type, value_count,"
                                + " value_minor, value_bps, value_days, value_hours, value_window, value_subject_kind,"
                                + " value_corridors, value_assurance, value_rating)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RiskRule rule : policy.rules()) {
                insert.setObject(1, id.value());
                insert.setInt(2, rule.ordinal());
                insert.setString(3, rule.kind().name());
                if (rule.weight().isPresent()) {
                    insert.setInt(4, rule.weight().getAsInt());
                } else {
                    insert.setNull(4, Types.INTEGER);
                }
                insert.setString(5, rule.hardDecision().map(Enum::name).orElse(null));
                insert.executeUpdate();
                for (RuleKind.Parameter declared : rule.kind().parameters()) {
                    ParameterValue value = rule.parameters().value(declared.name());
                    parameter.setObject(1, id.value());
                    parameter.setInt(2, rule.ordinal());
                    parameter.setString(3, declared.name());
                    parameter.setString(4, value.type().name());
                    for (int i = 5; i <= 14; i++) {
                        parameter.setNull(i, Types.NULL);
                    }
                    switch (value) {
                        case ParameterValue.Count count -> parameter.setLong(5, count.value());
                        case ParameterValue.MinorAmount amount -> parameter.setLong(6, amount.value());
                        case ParameterValue.BasisPoints bps -> parameter.setInt(7, bps.value());
                        case ParameterValue.Days days -> parameter.setInt(8, days.value());
                        case ParameterValue.Hours hours -> parameter.setInt(9, hours.value());
                        case ParameterValue.Window window -> parameter.setString(10, window.value().name());
                        case ParameterValue.Subject subject -> parameter.setString(11, subject.value().name());
                        case ParameterValue.Corridors corridors -> parameter.setArray(12,
                                unitOfWork.createArrayOf("text", corridors.sorted().toArray(String[]::new)));
                        case ParameterValue.Assurance assurance -> parameter.setString(13, assurance.value().name());
                        case ParameterValue.Rating rating -> parameter.setString(14, rating.value().name());
                    }
                    parameter.executeUpdate();
                }
            }
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO risk.risk_policy_band (policy_version_id, band_kind, ordinal, lower_bound, upper_bound,"
                        + " decision, rating) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int ordinal = 1;
            for (ScoreBand band : policy.scoreBands()) {
                band(insert, id, "SCORE", ordinal++, band.from(), OptionalLong.of(band.to()), band.decision().name(), null);
            }
            ordinal = 1;
            for (RatingBand band : policy.ratingBands()) {
                band(insert, id, "RATING", ordinal++, band.from(), OptionalLong.of(band.to()), null, band.rating().name());
            }
            ordinal = 1;
            for (FailSafeBand band : policy.failSafeBands()) {
                band(insert, id, "FAIL_SAFE", ordinal++, band.fromMinor(), band.toMinor(), band.decision().name(), null);
            }
        }
    }

    private static void band(PreparedStatement insert, RiskPolicyVersionId id, String kind, int ordinal, long lower,
            OptionalLong upper, String decision, String rating) throws SQLException {
        insert.setObject(1, id.value());
        insert.setString(2, kind);
        insert.setInt(3, ordinal);
        insert.setLong(4, lower);
        if (upper.isPresent()) {
            insert.setLong(5, upper.getAsLong());
        } else {
            insert.setNull(5, Types.BIGINT);
        }
        insert.setString(6, decision);
        insert.setString(7, rating);
        insert.executeUpdate();
    }

    @Override
    public Optional<VersionRow> find(Connection unitOfWork, RiskPolicyVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM risk.risk_policy_version WHERE id = ?", id.value(),
                "reading a policy version");
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, RiskPolicyVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM risk.risk_policy_version WHERE id = ? FOR UPDATE",
                id.value(), "locking a policy version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, RiskOperation operation) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM risk.risk_policy_version"
                + " WHERE operation = ? AND status = 'ACTIVE' FOR UPDATE", operation.name(),
                "locking the active policy version");
    }

    @Override
    public boolean retire(Connection unitOfWork, RiskPolicyVersionId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE risk.risk_policy_version SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring a policy version", failure);
        }
    }

    @Override
    public Optional<DecisionStamps> decide(
            Connection unitOfWork, RiskPolicyVersionId id, RiskPolicyVersionStatus status, Actor actor, String reason) {
        if (status != RiskPolicyVersionStatus.ACTIVE && status != RiskPolicyVersionStatus.REJECTED) {
            throw new IllegalArgumentException("a decision activates or rejects");
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE risk.risk_policy_version SET status = ?, decided_by = ?, decided_by_type = ?,"
                        + " decided_at = clock_timestamp(), decision_reason = ?"
                        + " WHERE id = ? AND status = 'PROPOSED' RETURNING decided_at, effective_from")) {
            update.setString(1, status.name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            try (ResultSet row = update.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DecisionStamps(row.getTimestamp(1).toInstant(),
                        Optional.ofNullable(row.getTimestamp(2)).map(Timestamp::toInstant)));
            }
        } catch (SQLException failure) {
            throw failed("deciding a policy version", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow history) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO risk.risk_policy_version_event (id, policy_version_id, operation, from_status, to_status,"
                        + " actor, actor_type, reason, occurred_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, history.eventId());
            insert.setObject(2, history.version().value());
            insert.setString(3, history.operation().name());
            insert.setString(4, history.from().map(Enum::name).orElse(null));
            insert.setString(5, history.to().name());
            insert.setString(6, history.actor().id());
            insert.setString(7, history.actor().type().name());
            insert.setString(8, history.reason());
            insert.setString(9, history.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a policy version's history", failure);
        }
    }

    @Override
    public Optional<RiskPolicyVersion> version(Connection unitOfWork, RiskPolicyVersionId id) {
        try {
            VersionRow version;
            CurrencyCode currency;
            int scale;
            Optional<RiskAssurance> assurance;
            OptionalLong threshold;
            OptionalInt holdHours;
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT " + columns("v") + ", p.currency, p.scale, p.challenge_assurance, p.override_threshold_minor,"
                            + " p.hold_validity_hours FROM risk.risk_policy_version v"
                            + " JOIN risk.risk_policy p ON p.policy_version_id = v.id WHERE v.id = ?")) {
                select.setObject(1, id.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    version = versionRow(row);
                    currency = CurrencyCode.of(row.getString("currency").trim());
                    scale = row.getShort("scale");
                    assurance = Optional.ofNullable(row.getString("challenge_assurance")).map(RiskAssurance::valueOf);
                    long minor = row.getLong("override_threshold_minor");
                    threshold = row.wasNull() ? OptionalLong.empty() : OptionalLong.of(minor);
                    int hours = row.getInt("hold_validity_hours");
                    holdHours = row.wasNull() ? OptionalInt.empty() : OptionalInt.of(hours);
                }
            }
            List<RiskRule> rules = rules(unitOfWork, id);
            List<ScoreBand> scoreBands = new ArrayList<>();
            List<RatingBand> ratingBands = new ArrayList<>();
            List<FailSafeBand> failSafeBands = new ArrayList<>();
            bands(unitOfWork, id, scoreBands, ratingBands, failSafeBands);
            RiskPolicy policy = new RiskPolicy(version.operation(), engine(version.engine()), currency, rules, scoreBands,
                    ratingBands, failSafeBands, assurance,
                    threshold.isPresent() ? Optional.of(Money.ofPersisted(threshold.getAsLong(), currency, scale))
                            : Optional.empty(),
                    holdHours.isPresent() ? Optional.of(Duration.ofHours(holdHours.getAsInt())) : Optional.empty());
            return Optional.of(new RiskPolicyVersion(version, policy));
        } catch (SQLException failure) {
            throw failed("reading a policy version's content", failure);
        }
    }

    private static List<RiskRule> rules(Connection unitOfWork, RiskPolicyVersionId id) throws SQLException {
        Map<Integer, Map<String, ParameterValue>> parameters = new LinkedHashMap<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT rule_ordinal, name, type, value_count, value_minor, value_bps, value_days, value_hours,"
                        + " value_window, value_subject_kind, value_corridors, value_assurance, value_rating"
                        + " FROM risk.risk_rule_parameter WHERE policy_version_id = ? ORDER BY rule_ordinal, name")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    parameters.computeIfAbsent(row.getInt("rule_ordinal"), ordinal -> new LinkedHashMap<>())
                            .put(row.getString("name"), parameter(row));
                }
            }
        }
        List<RiskRule> rules = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT ordinal, kind, weight, hard_decision FROM risk.risk_rule WHERE policy_version_id = ?"
                        + " ORDER BY ordinal")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    int ordinal = row.getInt("ordinal");
                    RuleKind kind = RuleKind.valueOf(row.getString("kind"));
                    int weight = row.getInt("weight");
                    OptionalInt weighted = row.wasNull() ? OptionalInt.empty() : OptionalInt.of(weight);
                    Optional<RiskDecision> hard = Optional.ofNullable(row.getString("hard_decision"))
                            .map(RiskDecision::valueOf);
                    rules.add(new RiskRule(ordinal, kind,
                            RuleParameters.of(kind, parameters.getOrDefault(ordinal, Map.of())), weighted, hard));
                }
            }
        }
        return rules;
    }

    private static ParameterValue parameter(ResultSet row) throws SQLException {
        return switch (RuleKind.ParameterType.valueOf(row.getString("type"))) {
            case COUNT -> new ParameterValue.Count(row.getLong("value_count"));
            case MINOR_AMOUNT -> new ParameterValue.MinorAmount(row.getLong("value_minor"));
            case BASIS_POINTS -> new ParameterValue.BasisPoints(row.getInt("value_bps"));
            case DAYS -> new ParameterValue.Days(row.getInt("value_days"));
            case HOURS -> new ParameterValue.Hours(row.getInt("value_hours"));
            case WINDOW -> new ParameterValue.Window(WindowKind.valueOf(row.getString("value_window")));
            case SUBJECT_KIND -> new ParameterValue.Subject(SubjectKind.valueOf(row.getString("value_subject_kind")));
            case CORRIDOR_SET -> {
                Array array = row.getArray("value_corridors");
                Set<String> codes = new TreeSet<>(List.of((String[]) array.getArray()));
                yield new ParameterValue.Corridors(codes);
            }
            case ASSURANCE -> new ParameterValue.Assurance(RiskAssurance.valueOf(row.getString("value_assurance")));
            case RATING -> new ParameterValue.Rating(RiskRating.valueOf(row.getString("value_rating")));
        };
    }

    private static void bands(Connection unitOfWork, RiskPolicyVersionId id, List<ScoreBand> score,
            List<RatingBand> rating, List<FailSafeBand> failSafe) throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT band_kind, lower_bound, upper_bound, decision, rating FROM risk.risk_policy_band"
                        + " WHERE policy_version_id = ? ORDER BY band_kind, ordinal")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    long lower = row.getLong("lower_bound");
                    long upper = row.getLong("upper_bound");
                    OptionalLong bounded = row.wasNull() ? OptionalLong.empty() : OptionalLong.of(upper);
                    switch (row.getString("band_kind")) {
                        case "SCORE" -> score.add(new ScoreBand((int) lower, (int) bounded.orElseThrow(),
                                RiskDecision.valueOf(row.getString("decision"))));
                        case "RATING" -> rating.add(new RatingBand((int) lower, (int) bounded.orElseThrow(),
                                RiskRating.valueOf(row.getString("rating"))));
                        case "FAIL_SAFE" -> failSafe.add(new FailSafeBand(lower, bounded,
                                RiskDecision.valueOf(row.getString("decision"))));
                        default -> throw new IllegalStateException("a stored band is of a kind this build does not hold");
                    }
                }
            }
        }
    }

    @Override
    public Optional<RiskPolicyVersionId> active(Connection unitOfWork, RiskOperation operation) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM risk.risk_policy_version WHERE operation = ? AND status = 'ACTIVE'")) {
            select.setString(1, operation.name());
            return single(select, "two ACTIVE policy versions for one operation");
        } catch (SQLException failure) {
            throw failed("reading the active policy version", failure);
        }
    }

    @Override
    public Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation) {
        try (PreparedStatement select = unitOfWork.prepareStatement(SHARE_ACTIVE_SQL)) {
            select.setString(1, operation.name());
            return single(select, "two ACTIVE policy versions for one operation");
        } catch (SQLException failure) {
            throw failed("pinning the active policy version", failure);
        }
    }

    @Override
    public Map<RiskOperation, Integer> activeVersions(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT operation, version FROM risk.risk_policy_version WHERE status = 'ACTIVE'");
                ResultSet row = select.executeQuery()) {
            Map<RiskOperation, Integer> versions = new EnumMap<>(RiskOperation.class);
            while (row.next()) {
                versions.put(RiskOperation.valueOf(row.getString(1)), row.getInt(2));
            }
            return versions;
        } catch (SQLException failure) {
            throw failed("reading the active policy versions", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A stored engine version by name - one this build holds (the read refuses otherwise; the activation first). */
    private static RiskEngine engine(String name) {
        for (RiskEngine version : RiskEngine.values()) {
            if (version.name().equals(name)) {
                return version;
            }
        }
        throw new IllegalStateException("a stored policy version names an engine version this build does not hold");
    }

    private static String columns(String alias) {
        return "v.id, v.operation, v.version, v.engine, v.status, v.proposed_by, v.proposed_at, v.decided_by,"
                + " v.decided_at, v.effective_from, v.effective_to".replace("v.", alias + ".");
    }

    private static Optional<VersionRow> row(Connection unitOfWork, String sql, Object key, String what) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(versionRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(what, failure);
        }
    }

    private static VersionRow versionRow(ResultSet row) throws SQLException {
        return new VersionRow(
                RiskPolicyVersionId.of(row.getObject("id", UUID.class)),
                RiskOperation.valueOf(row.getString("operation")),
                row.getInt("version"),
                row.getString("engine"),
                RiskPolicyVersionStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                Optional.ofNullable(row.getString("decided_by")),
                instant(row, "decided_at"),
                instant(row, "effective_from"),
                instant(row, "effective_to"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getTimestamp(column)).map(Timestamp::toInstant);
    }

    private static Optional<RiskPolicyVersionId> single(PreparedStatement select, String twoFound) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            RiskPolicyVersionId id = RiskPolicyVersionId.of(row.getObject(1, UUID.class));
            if (row.next()) {
                throw new IllegalStateException(twoFound);
            }
            return Optional.of(id);
        }
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
