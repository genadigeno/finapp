package com.finapp.fx;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The FX kill switch, asymmetric by design (`P9-TSK-007`, the lifecycle document §3.10,
 * {@code INV-AUD-04}): <strong>disabling</strong> a pair or a provider takes one person with a
 * reason, at once - making money stop moving must never wait for a second person;
 * <strong>enabling</strong> is a proposal a different person approves, the enabling fact appended
 * in the approval's transaction. Availability is an append-only fact per change, never a mutable
 * flag; every writer of one subject serialises on advisory namespace 7, so each decides against
 * the newest fact.
 *
 * <p>Each command runs in the caller's unit of work. The disable is idempotent through the facts -
 * disabling an unavailable subject writes nothing - and the decisions through the request's
 * one-way machine, the same person's retry converging.
 */
@RequiredArgsConstructor
public final class FxAvailability {

    /** The event a disable or an approved enabling publishes. */
    static final String CHANGED_EVENT = "fx.FxAvailabilityChanged";

    static final String TARGET_TYPE = "fx_availability";

    @NonNull private final AvailabilityStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    // ------------------------------------------------------------------ outcomes

    /** A disable's outcome: whether this call wrote the fact (false when already unavailable). */
    public record Disabled(AvailabilitySubject subject, boolean changed) {}

    /** An enable proposal's outcome. */
    public record EnableProposed(UUID requestId, AvailabilitySubject subject) {}

    /** An enable decision's outcome; {@code replayed} when the same person's retry converged. */
    public record EnableDecided(
            UUID requestId,
            AvailabilitySubject subject,
            AvailabilityStore.EnableRequestStatus status,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No enable request has this id. */
    public static final class EnableRequestNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EnableRequestNotFound() {
            super("no enable request has this identifier");
        }
    }

    /** The request was already decided. */
    public static final class EnableRequestNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EnableRequestNotPending(AvailabilityStore.EnableRequestStatus status) {
            super("the enable request is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to approve their own enabling ({@code INV-AUD-04}). */
    public static final class EnableSelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EnableSelfApprovalRefused() {
            super("an enabling is approved by someone other than its proposer; the proposer may"
                    + " reject it to withdraw it");
        }
    }

    /** An enable proposal already awaits a decision for the subject. */
    public static final class EnablePending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public EnablePending(Throwable cause) {
            super("an enable request already awaits a decision for this subject", cause);
        }
    }

    /** Enabling was proposed for a subject that is already available. */
    public static final class AlreadyAvailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AlreadyAvailable() {
            super("the subject is already available: there is nothing to enable");
        }
    }

    /** A malformed reason; the message names the defect. */
    public static final class AvailabilityInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AvailabilityInvalid(String defect) {
            super(defect);
        }
    }

    // ------------------------------------------------------------------ disable

    /**
     * Disables {@code subject} - one person, at once. Under the subject's lock: when it is
     * available, an unavailable fact is appended with the reasoned audit record and
     * {@code fx.FxAvailabilityChanged}; when it already is not, nothing is written.
     */
    public Disabled disable(
            Connection unitOfWork,
            AvailabilitySubject subject,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        FxReasons.refuse(reason, AvailabilityInvalid::new);
        store.lockSubject(unitOfWork, subject);
        if (!store.isAvailable(unitOfWork, subject)) {
            return new Disabled(subject, false);
        }
        store.insertFact(unitOfWork, subject, false, actor.id(), reason, now, Optional.empty());
        audit(unitOfWork, actor, now, FxAuditAction.AVAILABILITY_DISABLED, subject, reason,
                "subject=" + subject.kind() + ":" + subject.subject(), correlation);
        announce(unitOfWork, subject, false, now, correlation);
        return new Disabled(subject, true);
    }

    // ------------------------------------------------------------------ enable

    /**
     * Proposes enabling {@code subject}. Nothing is enabled until a different person approves.
     *
     * @throws AlreadyAvailable when the subject is available now
     * @throws EnablePending when a proposal already awaits a decision (the caller's transaction is
     *     then aborted)
     */
    public EnableProposed proposeEnable(
            Connection unitOfWork,
            AvailabilitySubject subject,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        FxReasons.refuse(reason, AvailabilityInvalid::new);
        store.lockSubject(unitOfWork, subject);
        if (store.isAvailable(unitOfWork, subject)) {
            throw new AlreadyAvailable();
        }
        UUID requestId = ids.next();
        store.insertRequest(unitOfWork, requestId, subject, actor.id(), reason, now);
        audit(unitOfWork, actor, now, FxAuditAction.AVAILABILITY_ENABLE_PROPOSED, subject, reason,
                "subject=" + subject.kind() + ":" + subject.subject() + ", request=" + requestId, correlation);
        return new EnableProposed(requestId, subject);
    }

    /**
     * Approves an enable request - a different person's act. Under the request's row lock and then
     * the subject's: the request moves {@code PROPOSED -> APPROVED} naming its approver and, when the
     * subject is still unavailable, the enabling fact is appended naming the request, with the
     * reasoned audit record and {@code fx.FxAvailabilityChanged}. The same person's retry converges.
     *
     * @throws EnableRequestNotFound when no request has this id
     * @throws EnableRequestNotPending when it is no longer {@code PROPOSED}
     * @throws EnableSelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     */
    public EnableDecided approveEnable(
            Connection unitOfWork,
            UUID requestId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        AvailabilityStore.RequestRow row =
                store.lockRequest(unitOfWork, requestId).orElseThrow(EnableRequestNotFound::new);
        if (row.status() == AvailabilityStore.EnableRequestStatus.APPROVED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new EnableDecided(row.id(), row.subject(), row.status(), true);
        }
        if (row.status() != AvailabilityStore.EnableRequestStatus.PROPOSED) {
            throw new EnableRequestNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new EnableSelfApprovalRefused();
        }
        FxReasons.refuse(reason, AvailabilityInvalid::new);
        store.lockSubject(unitOfWork, row.subject());
        if (!store.decideRequest(unitOfWork, row.id(), AvailabilityStore.EnableRequestStatus.APPROVED,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked enable request was decided by another writer: the FOR UPDATE protocol"
                            + " was bypassed");
        }
        boolean changed = !store.isAvailable(unitOfWork, row.subject());
        if (changed) {
            store.insertFact(unitOfWork, row.subject(), true, actor.id(), reason, now, Optional.of(row.id()));
        }
        audit(unitOfWork, actor, now, FxAuditAction.AVAILABILITY_ENABLED, row.subject(), reason,
                "subject=" + row.subject().kind() + ":" + row.subject().subject() + ", request="
                        + row.id() + ", changed=" + changed,
                correlation);
        if (changed) {
            announce(unitOfWork, row.subject(), true, now, correlation);
        }
        return new EnableDecided(row.id(), row.subject(), AvailabilityStore.EnableRequestStatus.APPROVED, false);
    }

    /**
     * Rejects an enable request - any holder, the proposer included (a withdrawal enables nothing).
     * The same person's retry converges.
     */
    public EnableDecided rejectEnable(
            Connection unitOfWork,
            UUID requestId,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        AvailabilityStore.RequestRow row =
                store.lockRequest(unitOfWork, requestId).orElseThrow(EnableRequestNotFound::new);
        if (row.status() == AvailabilityStore.EnableRequestStatus.REJECTED
                && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new EnableDecided(row.id(), row.subject(), row.status(), true);
        }
        if (row.status() != AvailabilityStore.EnableRequestStatus.PROPOSED) {
            throw new EnableRequestNotPending(row.status());
        }
        FxReasons.refuse(reason, AvailabilityInvalid::new);
        if (!store.decideRequest(unitOfWork, row.id(), AvailabilityStore.EnableRequestStatus.REJECTED,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked enable request was decided by another writer: the FOR UPDATE protocol"
                            + " was bypassed");
        }
        audit(unitOfWork, actor, now, FxAuditAction.AVAILABILITY_ENABLE_REJECTED, row.subject(), reason,
                "subject=" + row.subject().kind() + ":" + row.subject().subject() + ", request=" + row.id()
                        + ", byProposer=" + row.proposedBy().equals(actor.id()),
                correlation);
        return new EnableDecided(row.id(), row.subject(), AvailabilityStore.EnableRequestStatus.REJECTED, false);
    }

    /** Whether {@code subject} is available now. */
    public boolean isAvailable(Connection unitOfWork, AvailabilitySubject subject) {
        return store.isAvailable(unitOfWork, subject);
    }

    // ------------------------------------------------------------------ plumbing

    private void announce(
            Connection unitOfWork, AvailabilitySubject subject, boolean available, Instant now,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        CHANGED_EVENT,
                        PricingPolicyAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        new AvailabilityEventId(ids.next()),
                        TARGET_TYPE,
                        now,
                        PricingPolicyAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("subjectKind", subject.kind().name())
                        .with("subject", subject.subject())
                        .with("available", Boolean.toString(available))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            FxAuditAction action,
            AvailabilitySubject subject,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        TARGET_TYPE,
                        subject.kind() + ":" + subject.subject(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }

    /** The availability change's aggregate identity on the envelope (each change is its own fact). */
    private static final class AvailabilityEventId extends EntityId {
        AvailabilityEventId(UUID value) {
            super(value);
        }
    }
}
