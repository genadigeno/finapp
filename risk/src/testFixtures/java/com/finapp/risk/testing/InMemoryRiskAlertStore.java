package com.finapp.risk.testing;

import com.finapp.platform.security.Actor;
import com.finapp.risk.AlertKey;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertStatus;
import com.finapp.risk.NewAlert;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAlertStore;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.SuppressionReason;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link RiskAlertStore} in memory for the hermetic suites (`P13-TSK-015`) - {@code risk V010}'s arbiters as the
 * statements apply them: the birth {@code ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING} (a second key is no
 * row; a second id throws, as a primary-key collision does), every edge conditional on the state it moves from, the
 * stamps the database clock {@link #now}, which the suite moves. <strong>Not modelled, deliberately</strong>: the
 * persons-only {@code CHECK} and the trigger's frozen facts, and the four-eyes {@code CHECK} beyond what the
 * {@link RiskAlert} value itself refuses (a decision by its proposer) - the domain's own refusals are what the hermetic
 * suites prove; the database's are {@code AlertDatabaseTest}'s (written and owed, C5).
 * {@link #log} records each operation in order, so a suite can hold the raise's protocol (the insert first).
 * Single-threaded.
 */
public final class InMemoryRiskAlertStore implements RiskAlertStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-25T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    private final Map<RiskAlertId, RiskAlert> rows = new LinkedHashMap<>();

    /** Every alert stored, in insert order. */
    public List<RiskAlert> all() {
        return List.copyOf(rows.values());
    }

    /** Puts {@code alert} in place as a writer that bypassed the domain would - a suite's planted state. */
    public void plant(RiskAlert alert) {
        rows.put(alert.id(), alert);
    }

    @Override
    public Optional<RiskAlert> insert(Connection unitOfWork, RiskAlertId id, NewAlert alert) {
        log.add("insert");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(alert, "alert");
        if (rows.containsKey(id)) {
            throw new IllegalStateException("risk_alert_pk: a primary-key collision is never a conflict");
        }
        if (byKey(alert.key()).isPresent()) {
            return Optional.empty();
        }
        RiskAlert born = new RiskAlert(id, alert.kind(), alert.severity(), alert.subject(), alert.rule(),
                alert.window(), alert.policyVersion(), alert.evidence(), AlertStatus.OPEN, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), now, alert.correlation().value(),
                alert.causation().value());
        rows.put(id, born);
        return Optional.of(born);
    }

    @Override
    public Optional<RiskAlert> findByKey(Connection unitOfWork, AlertKey key) {
        log.add("findByKey");
        return byKey(Objects.requireNonNull(key, "key"));
    }

    @Override
    public Optional<RiskAlert> find(Connection unitOfWork, RiskAlertId id) {
        log.add("find");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskAlert> lock(Connection unitOfWork, RiskAlertId id) {
        log.add("lock");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskAlert> attach(Connection unitOfWork, RiskAlertId id, RiskCaseId caseId) {
        log.add("attach");
        RiskAlert row = rows.get(id);
        if (row == null || row.status() != AlertStatus.OPEN) {
            return Optional.empty();
        }
        return put(new RiskAlert(row.id(), row.kind(), row.severity(), row.subject(), row.rule(), row.window(),
                row.policyVersion(), row.evidence(), AlertStatus.ATTACHED, Optional.of(caseId), Optional.of(now),
                row.suppression(), Optional.empty(), row.raisedAt(), row.correlationId(), row.causationId()));
    }

    @Override
    public List<RiskAlert> queued(Connection unitOfWork, int limit) {
        log.add("queued");
        return rows.values().stream()
                .filter(row -> row.status() == AlertStatus.OPEN && !row.kind().operational()
                        && row.suppression().isEmpty())
                .sorted(java.util.Comparator.comparing(RiskAlert::raisedAt)
                        .thenComparing(row -> row.id().value()))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<RiskAlert> propose(Connection unitOfWork, RiskAlertId id, SuppressionReason reasonCode,
            String reason, Actor proposer) {
        log.add("propose");
        RiskAlert row = rows.get(id);
        if (row == null || row.status() != AlertStatus.OPEN || row.suppression().isPresent()) {
            return Optional.empty();
        }
        return put(new RiskAlert(row.id(), row.kind(), row.severity(), row.subject(), row.rule(), row.window(),
                row.policyVersion(), row.evidence(), AlertStatus.OPEN, Optional.empty(), Optional.empty(),
                Optional.of(new RiskAlert.Suppression(reasonCode, reason, proposer.id(), proposer.type(), now)),
                Optional.empty(), row.raisedAt(), row.correlationId(), row.causationId()));
    }

    @Override
    public Optional<RiskAlert> approve(Connection unitOfWork, RiskAlertId id, Actor approver, String reason) {
        log.add("approve");
        RiskAlert row = rows.get(id);
        if (row == null || row.status() != AlertStatus.OPEN || row.suppression().isEmpty()) {
            return Optional.empty();
        }
        return put(new RiskAlert(row.id(), row.kind(), row.severity(), row.subject(), row.rule(), row.window(),
                row.policyVersion(), row.evidence(), AlertStatus.SUPPRESSED, Optional.empty(), Optional.empty(),
                row.suppression(), Optional.of(new RiskAlert.Decision(approver.id(), approver.type(), now, reason)),
                row.raisedAt(), row.correlationId(), row.causationId()));
    }

    @Override
    public Optional<RiskAlert> reject(Connection unitOfWork, RiskAlertId id) {
        log.add("reject");
        RiskAlert row = rows.get(id);
        if (row == null || row.status() != AlertStatus.OPEN || row.suppression().isEmpty()) {
            return Optional.empty();
        }
        return put(new RiskAlert(row.id(), row.kind(), row.severity(), row.subject(), row.rule(), row.window(),
                row.policyVersion(), row.evidence(), AlertStatus.OPEN, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), row.raisedAt(), row.correlationId(), row.causationId()));
    }

    @Override
    public List<RiskAlert> queue(Connection unitOfWork, AlertStatus status, Optional<AlertKind> kind,
            Optional<RiskAlertId> after, UUID excludingParty, int limit) {
        log.add("queue " + status);
        if (limit < 1) {
            throw new IllegalArgumentException("a queue page reads at least one alert: " + limit);
        }
        Comparator<RiskAlert> oldestFirst = Comparator.comparing(RiskAlert::raisedAt)
                .thenComparing(row -> row.id().value());
        Optional<RiskAlert> cursor = after.map(rows::get);
        return rows.values().stream()
                .filter(row -> row.status() == status)
                .filter(row -> kind.isEmpty() || row.kind() == kind.get())
                .filter(row -> !(row.subject().kind() == SubjectKind.PARTY
                        && row.subject().ref().equals(excludingParty.toString())))
                .filter(row -> cursor.isEmpty() || oldestFirst.compare(row, cursor.get()) > 0)
                .sorted(oldestFirst)
                .limit(limit)
                .toList();
    }

    @Override
    public List<RiskAlert> ofCase(Connection unitOfWork, RiskCaseId caseId) {
        log.add("ofCase");
        return rows.values().stream()
                .filter(row -> row.attachedCase().filter(caseId::equals).isPresent())
                .sorted(Comparator.comparing(RiskAlert::raisedAt).thenComparing(row -> row.id().value()))
                .toList();
    }

    private Optional<RiskAlert> byKey(AlertKey key) {
        return rows.values().stream().filter(row -> row.key().equals(key)).findFirst();
    }

    private Optional<RiskAlert> put(RiskAlert alert) {
        rows.put(alert.id(), alert);
        return Optional.of(alert);
    }
}
