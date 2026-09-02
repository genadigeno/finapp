package com.finapp.platform.audit;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One audited action: who did what, to what, when, why, and what came of it.
 *
 * <p>The seven questions {@code CLAUDE.md} §Security and Audit requires an audit record to
 * answer, made mandatory at construction so that an unanswerable record cannot be built — the
 * same technique {@code EventEnvelope} uses for {@code INV-EVT-03}. Only {@code reason} and
 * {@code changeSummary} are optional, and both are optional for stated reasons rather than
 * because they were hard to supply.
 *
 * <p><strong>What must never be in here.</strong> {@code INV-AUD-02} forbids credentials,
 * tokens, PANs and unnecessary PII in logs, events and responses, and an audit record is read by
 * more people than any of those. This type records *that* an action occurred and by whom; the
 * payload the action carried is not its business. {@code changeSummary} is the field most likely
 * to be misused for it, which is why it is bounded and documented as a summary rather than a
 * diff.
 *
 * @param auditId identity of this record
 * @param actor who performed the action
 * @param occurredAt when the action occurred — a business fact from the caller's injected
 *     {@code Clock}, not the time the row reached the database
 * @param operation the action performed, from the auditable-action registry (P0-TSK-023).
 *     Typed rather than free text, so an action that is not in the registry cannot be recorded
 *     at all — which is what makes Phase 15's completeness verification possible
 * @param targetType what kind of thing was acted on
 * @param targetId which one
 * @param reason why. Required exactly when {@link AuditableAction#requiresReason()} says so,
 *     which is the decision {@code V009} deferred to the domain. Empty is legitimate for the
 *     actions that need none, but a blank string never is, because "no reason was required" and
 *     "a reason was required and nobody gave one" must remain distinguishable
 * @param outcome what came of it, including refusal
 * @param correlationId the flow this action belongs to, joining the record to the request, the
 *     postings and the events of the same operation
 * @param changeSummary what changed, where materially useful. A short summary, never a payload
 *     dump ({@code INV-AUD-02})
 */
public record AuditRecord(
        AuditId auditId,
        Actor actor,
        Instant occurredAt,
        AuditableAction operation,
        String targetType,
        String targetId,
        Optional<String> reason,
        AuditOutcome outcome,
        CorrelationId correlationId,
        Optional<String> changeSummary) {

    /** Matches the {@code _bounded} constraints on {@code platform.audit_record}. */
    public static final int MAX_NAME_LENGTH = 200;

    public static final int MAX_REASON_LENGTH = 1000;
    public static final int MAX_CHANGE_SUMMARY_LENGTH = 4000;

    public AuditRecord {
        Objects.requireNonNull(auditId, "auditId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(reason, "reason must not be null; use Optional.empty()");
        Objects.requireNonNull(changeSummary, "changeSummary must not be null; use Optional.empty()");

        bounded(operation.code(), "operation code", MAX_NAME_LENGTH);
        if (operation.requiresReason() && reason.isEmpty()) {
            // Enforced here rather than trusted at each call site. An action whose justification
            // is the only evidence it was legitimate must not be recordable without one, and the
            // registry is where that decision lives (V009 deferred it to the domain explicitly).
            throw new IllegalArgumentException(
                    "action " + operation.code() + " requires a reason, and none was given");
        }
        targetType = bounded(targetType, "targetType", MAX_NAME_LENGTH);
        targetId = bounded(targetId, "targetId", MAX_NAME_LENGTH);
        reason = boundedOptional(reason, "reason", MAX_REASON_LENGTH);
        changeSummary = boundedOptional(changeSummary, "changeSummary", MAX_CHANGE_SUMMARY_LENGTH);
    }

    private static String bounded(String value, String what, int max) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    what + " must be at most " + max + " characters but was " + value.length());
        }
        return value;
    }

    private static Optional<String> boundedOptional(Optional<String> value, String what, int max) {
        value.ifPresent(present -> bounded(present, what, max));
        return value;
    }
}
