package com.finapp.crossborder;

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
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Corridors change only forward, and only under four eyes (`P9-TSK-015`, ADR-0080 section 4, D26;
 * {@code INV-AUD-04}, {@code INV-HIST-04}): a holder of {@code CROSSBORDER_ADMINISTER} proposes a whole
 * new version, frozen from {@code PROPOSED}; a different holder activates it - retiring its predecessor
 * in the SAME transaction, so there is at most one {@code ACTIVE} version at every statement - or anyone
 * rejects it, the proposer included (a withdrawal offers nothing). The pricing policy's shape
 * ({@code fx.PricingPolicyAdministration}), on crossborder's own tables.
 *
 * <p><strong>No seed exemption</strong>: no version is seeded by migration, so v1's activation has no
 * predecessor to retire and is held to the same two-person rule - {@code crossborder V002}'s four-eyes
 * {@code CHECK} carries no exception for it. <strong>The build is judged twice</strong>: every candidate
 * rail must be declared and cover the corridor's (country, D), and every required datum be one the
 * platform holds, at proposal AND at approval - a build that dropped a rail between the two acts never
 * activates a corridor nothing can carry.
 *
 * <p>Each command runs in the caller's unit of work. A decision locks the version row first and, for an
 * activation, the {@code ACTIVE} row second - one order for every writer. Decisions carry no key: the
 * version's one-way machine is the idempotency - the same person's retry converges, anyone else gets
 * {@link ProposalNotPending}.
 */
@RequiredArgsConstructor
public final class CorridorPolicyAdministration {

    /** The event every activation publishes - one fact, named once, in two registries. */
    static final String ACTIVATED_EVENT = "crossborder.CorridorPolicyActivated";

    static final String PRODUCER = "crossborder";
    static final int EVENT_VERSION = 1;

    /** The audit record's target type for a corridor policy version. */
    static final String TARGET_TYPE = "crossborder_corridor_policy";

    @NonNull private final CorridorPolicyStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    /** The corridor rails the running build declares - a corridor may name only these. */
    @NonNull private final CorridorDirectory directory;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(CorridorPolicyId id, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for v1, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            CorridorPolicyId id,
            int version,
            CorridorPolicyStatus status,
            Optional<CorridorPolicyId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No corridor policy version has this id. */
    public static final class CorridorPolicyNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CorridorPolicyNotFound() {
            super("no corridor policy version has this identifier");
        }
    }

    /** The version was already decided - by somebody else, or differently - or retired. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(CorridorPolicyStatus status) {
            super("the corridor policy version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("a corridor policy version is activated by someone other than its proposer; the"
                    + " proposer may reject it to withdraw it");
        }
    }

    /** A proposal already awaits a decision, or a racing proposal took the version number. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ProposalPending(Throwable cause) {
            super("a proposed corridor policy version already awaits a decision", cause);
        }
    }

    /** The proposal, or a decision's reason, is not well formed; the message names the defect. */
    public static final class CorridorPolicyInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CorridorPolicyInvalid(String defect) {
            super(defect);
        }
    }

    /** A corridor names a rail the build does not declare, or one not covering its (country, D). */
    public static final class RailNotDeclared extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RailNotDeclared(String rail, CorridorKey corridor) {
            super("rail " + rail + " is not declared by this build as a corridor rail covering " + corridor);
        }
    }

    /** A corridor requires data the platform does not hold (ADR-0080 section 4). */
    public static final class RequiredDataUnsatisfiable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RequiredDataUnsatisfiable(RequiredData datum, CorridorKey corridor) {
            super("corridor " + corridor + " requires " + datum.name() + ", which the platform does not hold");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: judged ({@link CorridorPolicyProposal#validate}), numbered
     * {@code max + 1}, written {@code PROPOSED} with every corridor, its history row and the reasoned
     * audit record. Nothing is offered by it and nothing already decided changes.
     *
     * @throws CorridorPolicyInvalid for a malformed proposal
     * @throws RailNotDeclared for an undeclared or non-covering rail
     * @throws RequiredDataUnsatisfiable for data the platform does not hold
     * @throws ProposalPending when a proposal already awaits a decision (the caller's transaction is
     *     then aborted and must roll back)
     */
    public Proposed propose(
            Connection unitOfWork,
            CorridorPolicyProposal proposal,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        proposal.validate(directory);
        int version = store.maxVersion(unitOfWork) + 1;
        CorridorPolicyId id = CorridorPolicyId.next(ids);
        store.insertProposal(unitOfWork, id, version, proposal, actor.id(), now);
        store.appendEvent(unitOfWork, id, Optional.empty(), CorridorPolicyStatus.PROPOSED, actor.id(), proposal.reason(), now);
        audit(unitOfWork, actor, now, CrossborderAuditAction.CORRIDOR_POLICY_PROPOSED, id, proposal.reason(),
                "version=" + version + ", corridors=" + proposal.corridors().size(), correlation);
        return new Proposed(id, version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under the version's row lock the stored
     * corridors are judged against the build again; then, under the {@code ACTIVE} row's lock, the
     * predecessor (if any) is retired FIRST and the proposal moves {@code PROPOSED -> ACTIVE} naming its
     * approver; both history rows, the reasoned audit record and {@code crossborder.CorridorPolicyActivated}
     * follow, all in the caller's transaction. The same person's retry converges.
     *
     * @throws CorridorPolicyNotFound when no version has this id
     * @throws ProposalNotPending when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws RailNotDeclared when the build no longer declares a rail covering a corridor
     * @throws RequiredDataUnsatisfiable when a corridor requires data the platform does not hold
     * @throws CorridorPolicyInvalid for a malformed reason
     */
    public Decided approve(
            Connection unitOfWork,
            CorridorPolicyId id,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        CorridorPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(CorridorPolicyNotFound::new);
        if (row.status() == CorridorPolicyStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != CorridorPolicyStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        CrossborderReasons.refuse(reason, CorridorPolicyInvalid::new);
        CorridorPolicyProposal.judge(
                store.version(unitOfWork, row.id()).orElseThrow(CorridorPolicyNotFound::new).corridors(), directory);
        Optional<CorridorPolicyStore.VersionRow> predecessor = store.lockActive(unitOfWork);
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id(), now)) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        if (!store.decide(unitOfWork, row.id(), CorridorPolicyStatus.PROPOSED, CorridorPolicyStatus.ACTIVE,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        predecessor.ifPresent(
                retired -> store.appendEvent(
                        unitOfWork, retired.id(), Optional.of(CorridorPolicyStatus.ACTIVE), CorridorPolicyStatus.RETIRED,
                        actor.id(),
                        CrossborderReasons.bounded("retired by version " + row.version() + "'s activation: " + reason),
                        now));
        store.appendEvent(unitOfWork, row.id(), Optional.of(CorridorPolicyStatus.PROPOSED), CorridorPolicyStatus.ACTIVE,
                actor.id(), reason, now);
        audit(unitOfWork, actor, now, CrossborderAuditAction.CORRIDOR_POLICY_ACTIVATED, row.id(), reason,
                "activated=v" + row.version()
                        + predecessor.map(p -> ", retired=v" + p.version()).orElse(", retired=none"),
                correlation);
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACTIVATED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        row.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("version", Integer.toString(row.version()))
                        .with("retiredPolicyId", predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.version(), CorridorPolicyStatus.ACTIVE,
                predecessor.map(CorridorPolicyStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included (a withdrawal
     * offers nothing, so the four-eyes rule does not bind it). Terminal; frees the policy for a new
     * proposal. The same person's retry converges.
     */
    public Decided reject(
            Connection unitOfWork,
            CorridorPolicyId id,
            Actor actor,
            String reason,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        CorridorPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(CorridorPolicyNotFound::new);
        if (row.status() == CorridorPolicyStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != CorridorPolicyStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        CrossborderReasons.refuse(reason, CorridorPolicyInvalid::new);
        if (!store.decide(unitOfWork, row.id(), CorridorPolicyStatus.PROPOSED, CorridorPolicyStatus.REJECTED,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, row.id(), Optional.of(CorridorPolicyStatus.PROPOSED), CorridorPolicyStatus.REJECTED,
                actor.id(), reason, now);
        audit(unitOfWork, actor, now, CrossborderAuditAction.CORRIDOR_POLICY_REJECTED, row.id(), reason,
                "rejected=v" + row.version() + ", byProposer=" + row.proposedBy().equals(actor.id()), correlation);
        return new Decided(row.id(), row.version(), CorridorPolicyStatus.REJECTED, Optional.empty(), false);
    }

    /** Versions newest first, at most {@code limit}, each with its corridors. */
    public List<CorridorPolicyStore.VersionView> versions(Connection unitOfWork, int limit) {
        return store.versions(unitOfWork, limit);
    }

    /** The {@code ACTIVE} version with its corridors, if one exists. */
    public Optional<CorridorPolicyStore.VersionView> active(Connection unitOfWork) {
        return store.active(unitOfWork);
    }

    // ------------------------------------------------------------------ plumbing

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            CrossborderAuditAction action,
            CorridorPolicyId id,
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
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
