package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link BreakRegister} over JDBC (ADR-0033: explicit SQL, no mapper).
 *
 * <p>The insert yields to the partial one-open uniques ({@code ON CONFLICT DO NOTHING}) —
 * the every-writer arbiter under ten racing raisers — and a loser reads the standing open
 * break back, recording nothing: no history row, no audit, no event
 * ({@code reconciliation.ReconciliationBreakRaised} and {@code reconciliation.BreakRaised}
 * are acting-only). Severity is assessed here, from the pinned rule set's own
 * {@code severity_threshold} row for the value's currency — same schema, one read, no lock.
 */
@RequiredArgsConstructor
public final class JdbcBreakRegister implements BreakRegister {

    static final String PRODUCER = "reconciliation";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "reconciliation_break";
    static final String RAISED_EVENT_TYPE = "reconciliation.ReconciliationBreakRaised";

    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    @Override
    public Raised raise(Connection unitOfWork, NewBreak newBreak) {
        Severity severity =
                BreakSeverity.assess(
                        newBreak.type(),
                        newBreak.cause(),
                        newBreak.direction(),
                        newBreak.expectationKind(),
                        newBreak.valueAtIssue(),
                        highValueMinor(
                                unitOfWork,
                                newBreak.ruleSetId(),
                                newBreak.valueAtIssue().currency().code()));
        if (insert(unitOfWork, newBreak, severity)) {
            appendRaised(unitOfWork, newBreak, severity);
            auditRaised(unitOfWork, newBreak, severity);
            announceRaised(unitOfWork, newBreak, severity);
            return new Raised(true, newBreak.breakId(), severity);
        }
        return standing(unitOfWork, newBreak);
    }

    private Optional<Long> highValueMinor(
            Connection unitOfWork, UUID ruleSetId, String currency) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT high_value_minor FROM reconciliation.severity_threshold"
                                + " WHERE rule_set_id = ? AND currency = ?")) {
            read.setObject(1, ruleSetId);
            read.setString(2, currency);
            try (ResultSet row = read.executeQuery()) {
                return row.next()
                        ? Optional.of(row.getLong("high_value_minor"))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the severity threshold", failure);
        }
    }

    private boolean insert(Connection unitOfWork, NewBreak newBreak, Severity severity) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break (id, type, cause, status, severity,"
                                + " source_id, rule_set_id, expectation_id, external_item_id,"
                                + " suspense_item_id, run_id, decision_id,"
                                + " value_at_issue_minor, currency, scale,"
                                + " internal_classification, internal_operation_ref,"
                                + " internal_state, follows_break_id, raised_at,"
                                + " status_changed_at, correlation_id)"
                                + " VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT DO NOTHING")) {
            insert.setObject(1, newBreak.breakId());
            insert.setString(2, newBreak.type().name());
            insert.setString(3, newBreak.cause().name());
            insert.setString(4, severity.name());
            insert.setObject(5, newBreak.sourceId());
            insert.setObject(6, newBreak.ruleSetId());
            insert.setObject(7, newBreak.subject().expectationId().orElse(null));
            insert.setObject(8, newBreak.subject().externalItemId().orElse(null));
            insert.setObject(9, newBreak.subject().suspenseItemId().orElse(null));
            insert.setObject(10, newBreak.subject().runId().orElse(null));
            insert.setObject(11, newBreak.subject().decisionId().orElse(null));
            insert.setLong(12, newBreak.valueAtIssue().minorUnits());
            insert.setString(13, newBreak.valueAtIssue().currency().code());
            insert.setInt(14, newBreak.valueAtIssue().scale());
            insert.setString(
                    15,
                    newBreak.internalClassification().map(Enum::name).orElse(null));
            insert.setString(16, newBreak.internalOperationRef().orElse(null));
            insert.setString(17, newBreak.internalState().orElse(null));
            insert.setObject(18, newBreak.followsBreakId().orElse(null));
            insert.setTimestamp(19, Timestamp.from(newBreak.raisedAt()));
            insert.setTimestamp(20, Timestamp.from(newBreak.raisedAt()));
            insert.setString(21, newBreak.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not raise a break", failure);
        }
    }

    private Raised standing(Connection unitOfWork, NewBreak newBreak) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, severity FROM reconciliation.break"
                                + " WHERE type = ? AND status <> 'RESOLVED'");
        Object subjectId;
        if (newBreak.subject().expectationId().isPresent()) {
            sql.append(" AND expectation_id = ?");
            subjectId = newBreak.subject().expectationId().get();
        } else if (newBreak.subject().externalItemId().isPresent()) {
            sql.append(" AND external_item_id = ?");
            subjectId = newBreak.subject().externalItemId().get();
        } else if (newBreak.subject().suspenseItemId().isPresent()) {
            sql.append(" AND suspense_item_id = ?");
            subjectId = newBreak.subject().suspenseItemId().get();
        } else if (newBreak.subject().runId().isPresent()) {
            sql.append(" AND run_id = ?");
            subjectId = newBreak.subject().runId().get();
        } else {
            // The decision-only subject has no partial unique: a decision is written once
            // (ADR-0069 section 4), so its raise never loses.
            throw new ReconciliationStorageException(
                    "a decision-subject raise cannot converge: decisions are written once");
        }
        try (PreparedStatement read = unitOfWork.prepareStatement(sql.toString())) {
            read.setString(1, newBreak.type().name());
            read.setObject(2, subjectId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    throw new ReconciliationStorageException(
                            "the raise lost to a unique but no open break stands: another"
                                    + " constraint refused it");
                }
                return new Raised(
                        false,
                        row.getObject("id", UUID.class),
                        Severity.valueOf(row.getString("severity")));
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the standing break", failure);
        }
    }

    private void appendRaised(Connection unitOfWork, NewBreak newBreak, Severity severity) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_event (break_id, event_type, actor,"
                                + " actor_type, reason, detail, occurred_at, correlation_id)"
                                + " VALUES (?, 'RAISED', ?, ?, NULL, ?, ?, ?)")) {
            insert.setObject(1, newBreak.breakId());
            insert.setString(2, newBreak.actor().id());
            insert.setString(3, newBreak.actor().type().name());
            insert.setString(
                    4,
                    newBreak.type().name() + ":" + newBreak.cause().name() + ":"
                            + severity.name());
            insert.setTimestamp(5, Timestamp.from(newBreak.raisedAt()));
            insert.setString(6, newBreak.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the break's history", failure);
        }
    }

    private void auditRaised(Connection unitOfWork, NewBreak newBreak, Severity severity) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        newBreak.actor(),
                        newBreak.raisedAt(),
                        ReconciliationAuditAction.BREAK_RAISED,
                        TARGET_TYPE,
                        newBreak.breakId().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        newBreak.correlation(),
                        // Identifiers and enumerated names only - never an amount
                        // (INV-AUD-02).
                        Optional.of(
                                "type=" + newBreak.type().name()
                                        + ", cause=" + newBreak.cause().name()
                                        + ", severity=" + severity.name()
                                        + ", sourceId=" + newBreak.sourceId())));
    }

    private void announceRaised(Connection unitOfWork, NewBreak newBreak, Severity severity) {
        EventPayload payload =
                EventPayload.of()
                        .with("breakId", newBreak.breakId().toString())
                        .with("type", newBreak.type().name())
                        .with("cause", newBreak.cause().name())
                        .with("severity", severity.name())
                        // The source rides as its UUID (the P8-TSK-008 recorded stance):
                        // EventPayload's vocabulary is identifiers and enumerated names,
                        // and a dotted source code is neither.
                        .with("sourceId", newBreak.sourceId().toString());
        newBreak.subject()
                .expectationId()
                .ifPresent(id -> payload.with("expectationId", id.toString()));
        newBreak.subject()
                .externalItemId()
                .ifPresent(id -> payload.with("externalItemId", id.toString()));
        newBreak.subject()
                .suspenseItemId()
                .ifPresent(id -> payload.with("suspenseItemId", id.toString()));
        newBreak.subject().runId().ifPresent(id -> payload.with("runId", id.toString()));
        newBreak.subject()
                .decisionId()
                .ifPresent(id -> payload.with("decisionId", id.toString()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        RAISED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        BreakId.of(newBreak.breakId()),
                        TARGET_TYPE,
                        newBreak.raisedAt(),
                        PRODUCER,
                        newBreak.correlation(),
                        // A sweep's flow is a root: its own id stands in as the cause (the
                        // SettlementFileEvents precedent).
                        CausationId.of(newBreak.correlation().value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
