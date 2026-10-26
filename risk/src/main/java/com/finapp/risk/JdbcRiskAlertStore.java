package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@link RiskAlertStore} over {@code risk V010}'s {@code risk_alert} (`P13-TSK-015`; ADR-0033): the birth one statement,
 * {@code INSERT … ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING RETURNING} - the named constraint, so a
 * primary-key collision is never swallowed; every edge one conditional {@code UPDATE … WHERE status = 'OPEN' …
 * RETURNING}, the trigger judging it again and stamping the instants on the database clock. Risk's own table only - no
 * other schema is named ({@code INV-RSK-02}). A failure is named by its operation and SQLSTATE only, never the driver's
 * message (which could quote a subject or a reason).
 */
public final class JdbcRiskAlertStore implements RiskAlertStore {

    static final String COLUMNS = "id, kind, severity, subject_kind, subject_ref, rule_code, window_kind, window_start,"
            + " policy_version_id, evidence_signal_ids, evidence_assessment_ids, evidence_journal_entry_ids, status,"
            + " attached_case_id, attached_at, suppression_reason_code, suppression_reason, suppression_proposed_by,"
            + " suppression_proposed_by_type, suppression_proposed_at, suppression_decided_by,"
            + " suppression_decided_by_type, suppression_decided_at, suppression_decision_reason, raised_at,"
            + " correlation_id, causation_id";

    /** The birth - one statement; {@code raised_at} is the trigger's {@code clock_timestamp()} whatever is passed. */
    static final String INSERT = "INSERT INTO risk.risk_alert (id, kind, severity, subject_kind, subject_ref, rule_code,"
            + " window_kind, window_start, policy_version_id, evidence_signal_ids, evidence_assessment_ids,"
            + " evidence_journal_entry_ids, status, raised_at, correlation_id, causation_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', clock_timestamp(), ?, ?)"
            + " ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING RETURNING " + COLUMNS;

    static final String FIND_BY_KEY = "SELECT " + COLUMNS + " FROM risk.risk_alert"
            + " WHERE subject_ref = ? AND rule_code = ? AND window_start = ?"
            + " AND fact_signal_id IS NOT DISTINCT FROM ?::uuid";

    static final String ATTACH = "UPDATE risk.risk_alert SET status = 'ATTACHED', attached_case_id = ?"
            + " WHERE id = ? AND status = 'OPEN' RETURNING " + COLUMNS;

    /**
     * `-016`'s drain: the open alerts of a case-opening kind with no standing proposal, oldest first - on the queue's
     * partial index, no lock (the fold takes the case, then the alert).
     */
    static final String QUEUED = "SELECT " + COLUMNS + " FROM risk.risk_alert WHERE status = 'OPEN'"
            + " AND kind NOT IN ('UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE')"
            + " AND suppression_proposed_by IS NULL ORDER BY raised_at, id LIMIT ?";

    static final String PROPOSE = "UPDATE risk.risk_alert SET suppression_reason_code = ?, suppression_reason = ?,"
            + " suppression_proposed_by = ?, suppression_proposed_by_type = ?"
            + " WHERE id = ? AND status = 'OPEN' AND suppression_proposed_by IS NULL RETURNING " + COLUMNS;

    /** The approval - deliberately without a "not the proposer" predicate: four eyes is the domain's and the CHECK's. */
    static final String APPROVE = "UPDATE risk.risk_alert SET status = 'SUPPRESSED', suppression_decided_by = ?,"
            + " suppression_decided_by_type = ?, suppression_decision_reason = ?"
            + " WHERE id = ? AND status = 'OPEN' AND suppression_proposed_by IS NOT NULL RETURNING " + COLUMNS;

    static final String REJECT = "UPDATE risk.risk_alert SET suppression_reason_code = NULL, suppression_reason = NULL,"
            + " suppression_proposed_by = NULL, suppression_proposed_by_type = NULL"
            + " WHERE id = ? AND status = 'OPEN' AND suppression_proposed_by IS NOT NULL RETURNING " + COLUMNS;

    /**
     * `P13-TSK-018`'s queue: one status, oldest first by the immutable key, the reader's own {@code PARTY}-subject alerts
     * left out; the kind and the cursor appended by {@link #queueStatement} - this class's constant text only, never
     * input.
     */
    static final String QUEUE = "SELECT " + COLUMNS + " FROM risk.risk_alert WHERE status = ?"
            + " AND NOT (subject_kind = 'PARTY' AND subject_ref = ?)";

    static final String QUEUE_KIND = " AND kind = ?";

    /** After the cursor's key - read from the cursor's own row, so the instant is never round-tripped through Java. */
    static final String QUEUE_AFTER = " AND (raised_at, id) > (SELECT a.raised_at, a.id FROM risk.risk_alert a"
            + " WHERE a.id = ?)";

    static final String QUEUE_ORDER = " ORDER BY raised_at, id LIMIT ?";

    /** A case file's alerts, oldest first - on {@code risk V014}'s {@code risk_alert_of_case}. */
    static final String OF_CASE = "SELECT " + COLUMNS + " FROM risk.risk_alert WHERE attached_case_id = ?"
            + " ORDER BY raised_at, id";

    @Override
    public Optional<RiskAlert> insert(Connection unitOfWork, RiskAlertId id, NewAlert alert) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(alert, "alert");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT)) {
            int i = 0;
            insert.setObject(++i, id.value());
            insert.setString(++i, alert.kind().name());
            insert.setString(++i, alert.severity().name());
            insert.setString(++i, alert.subject().kind().name());
            insert.setString(++i, alert.subject().ref());
            insert.setString(++i, alert.rule().code());
            insert.setString(++i, alert.window().kind().name());
            insert.setTimestamp(++i, Timestamp.from(alert.window().start()));
            insert.setObject(++i, alert.policyVersion().map(version -> version.value()).orElse(null), Types.OTHER);
            insert.setArray(++i, uuids(unitOfWork, alert.evidence().signals(), RiskSignalId::value));
            insert.setArray(++i, uuids(unitOfWork, alert.evidence().assessments(), RiskAssessmentId::value));
            insert.setArray(++i, uuids(unitOfWork, alert.evidence().journalEntries(), Function.identity()));
            insert.setString(++i, alert.correlation().value());
            insert.setString(++i, alert.causation().value());
            return single(insert);
        } catch (SQLException failure) {
            throw failed("raising a risk alert", failure);
        }
    }

    @Override
    public Optional<RiskAlert> findByKey(Connection unitOfWork, AlertKey key) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(key, "key");
        try (PreparedStatement select = unitOfWork.prepareStatement(FIND_BY_KEY)) {
            select.setString(1, key.subjectRef());
            select.setString(2, key.ruleCode());
            select.setTimestamp(3, Timestamp.from(key.windowStart()));
            // risk V018: the born-once unique holds the alert's one signal for a kind keyed by it, NULL for the rest.
            select.setObject(4, key.factSignal().orElse(null), Types.OTHER);
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a risk alert by its key", failure);
        }
    }

    @Override
    public Optional<RiskAlert> find(Connection unitOfWork, RiskAlertId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_alert WHERE id = ?")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("reading a risk alert", failure);
        }
    }

    @Override
    public Optional<RiskAlert> lock(Connection unitOfWork, RiskAlertId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_alert WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw failed("locking a risk alert", failure);
        }
    }

    @Override
    public Optional<RiskAlert> attach(Connection unitOfWork, RiskAlertId id, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        try (PreparedStatement update = unitOfWork.prepareStatement(ATTACH)) {
            update.setObject(1, caseId.value());
            update.setObject(2, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("attaching a risk alert", failure);
        }
    }

    @Override
    public List<RiskAlert> queued(Connection unitOfWork, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (limit < 1) {
            throw new IllegalArgumentException("a drain reads at least one alert: " + limit);
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(QUEUED)) {
            select.setInt(1, limit);
            List<RiskAlert> queued = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    queued.add(alert(row));
                }
            }
            return List.copyOf(queued);
        } catch (SQLException failure) {
            throw failed("reading the queued risk alerts", failure);
        }
    }

    @Override
    public Optional<RiskAlert> propose(Connection unitOfWork, RiskAlertId id, SuppressionReason reasonCode,
            String reason, Actor proposer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(proposer, "proposer");
        try (PreparedStatement update = unitOfWork.prepareStatement(PROPOSE)) {
            update.setString(1, reasonCode.name());
            update.setString(2, reason);
            update.setString(3, proposer.id());
            update.setString(4, proposer.type().name());
            update.setObject(5, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("proposing a risk alert's suppression", failure);
        }
    }

    @Override
    public Optional<RiskAlert> approve(Connection unitOfWork, RiskAlertId id, Actor approver, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(APPROVE)) {
            update.setString(1, approver.id());
            update.setString(2, approver.type().name());
            update.setString(3, reason);
            update.setObject(4, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("approving a risk alert's suppression", failure);
        }
    }

    @Override
    public Optional<RiskAlert> reject(Connection unitOfWork, RiskAlertId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(REJECT)) {
            update.setObject(1, id.value());
            return single(update);
        } catch (SQLException failure) {
            throw failed("rejecting a risk alert's suppression", failure);
        }
    }

    @Override
    public List<RiskAlert> queue(Connection unitOfWork, AlertStatus status, Optional<AlertKind> kind,
            Optional<RiskAlertId> after, UUID excludingParty, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(excludingParty, "excludingParty");
        if (limit < 1) {
            throw new IllegalArgumentException("a queue page reads at least one alert: " + limit);
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(queueStatement(kind.isPresent(),
                after.isPresent()))) {
            int i = 0;
            select.setString(++i, status.name());
            select.setString(++i, excludingParty.toString());
            if (kind.isPresent()) {
                select.setString(++i, kind.get().name());
            }
            if (after.isPresent()) {
                select.setObject(++i, after.get().value());
            }
            select.setInt(++i, limit);
            return many(select);
        } catch (SQLException failure) {
            throw failed("reading the risk alert queue", failure);
        }
    }

    /** The queue's statement - {@link #QUEUE} with the kind and the cursor's predicates as asked, then the order. */
    static String queueStatement(boolean kind, boolean after) {
        return QUEUE + (kind ? QUEUE_KIND : "") + (after ? QUEUE_AFTER : "") + QUEUE_ORDER;
    }

    @Override
    public List<RiskAlert> ofCase(Connection unitOfWork, RiskCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(caseId, "caseId");
        try (PreparedStatement select = unitOfWork.prepareStatement(OF_CASE)) {
            select.setObject(1, caseId.value());
            return many(select);
        } catch (SQLException failure) {
            throw failed("reading a risk case's alerts", failure);
        }
    }

    // -----------------------------------------------------------------

    private static List<RiskAlert> many(PreparedStatement statement) throws SQLException {
        List<RiskAlert> alerts = new ArrayList<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                alerts.add(alert(row));
            }
        }
        return List.copyOf(alerts);
    }

    private static <T> Array uuids(Connection unitOfWork, List<T> references, Function<T, UUID> value)
            throws SQLException {
        return unitOfWork.createArrayOf("uuid", references.stream().map(value).toArray());
    }

    private static Optional<RiskAlert> single(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            return row.next() ? Optional.of(alert(row)) : Optional.empty();
        }
    }

    private static RiskAlert alert(ResultSet row) throws SQLException {
        Optional<RiskAlert.Suppression> suppression = Optional.ofNullable(row.getString("suppression_proposed_by"))
                .isEmpty()
                ? Optional.empty()
                : Optional.of(new RiskAlert.Suppression(
                        SuppressionReason.valueOf(row.getString("suppression_reason_code")),
                        row.getString("suppression_reason"),
                        row.getString("suppression_proposed_by"),
                        ActorType.valueOf(row.getString("suppression_proposed_by_type")),
                        row.getTimestamp("suppression_proposed_at").toInstant()));
        Optional<RiskAlert.Decision> decision = Optional.ofNullable(row.getString("suppression_decided_by")).isEmpty()
                ? Optional.empty()
                : Optional.of(new RiskAlert.Decision(
                        row.getString("suppression_decided_by"),
                        ActorType.valueOf(row.getString("suppression_decided_by_type")),
                        row.getTimestamp("suppression_decided_at").toInstant(),
                        row.getString("suppression_decision_reason")));
        return new RiskAlert(
                RiskAlertId.of(row.getObject("id", UUID.class)),
                AlertKind.valueOf(row.getString("kind")),
                AlertSeverity.valueOf(row.getString("severity")),
                new SignalSubject(SubjectKind.valueOf(row.getString("subject_kind")), row.getString("subject_ref")),
                new AlertRule(row.getString("rule_code")),
                AlertWindow.stored(WindowKind.valueOf(row.getString("window_kind")),
                        row.getTimestamp("window_start").toInstant()),
                Optional.ofNullable(row.getObject("policy_version_id", UUID.class)).map(RiskPolicyVersionId::of),
                new AlertEvidence(
                        references(row, "evidence_signal_ids").stream().map(RiskSignalId::of).toList(),
                        references(row, "evidence_assessment_ids").stream().map(RiskAssessmentId::of).toList(),
                        references(row, "evidence_journal_entry_ids")),
                AlertStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getObject("attached_case_id", UUID.class)).map(RiskCaseId::of),
                instant(row, "attached_at"),
                suppression,
                decision,
                row.getTimestamp("raised_at").toInstant(),
                row.getString("correlation_id"),
                row.getString("causation_id"));
    }

    private static List<UUID> references(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        List<UUID> references = new ArrayList<>();
        for (Object reference : (Object[]) array.getArray()) {
            references.add((UUID) reference);
        }
        return List.copyOf(references);
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        Timestamp at = row.getTimestamp(column);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
