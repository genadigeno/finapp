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
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The price changes only forward, and only under four eyes (`P9-TSK-007`, ADR-0075 §7, D26;
 * {@code INV-AUD-04}, {@code INV-HIST-04}, {@code INV-MON-03}): a holder of {@code FX_ADMINISTER}
 * proposes a whole new version, frozen from {@code PROPOSED}; a different holder activates it -
 * retiring its predecessor in the SAME transaction, so there is at most one {@code ACTIVE} version
 * at every statement and exactly one after the first activation - or anyone rejects it, the
 * proposer included (a withdrawal changes no price).
 *
 * <p><strong>No seed exemption</strong>: no version is seeded by migration, so v1's activation has
 * no predecessor to retire and is held to the same two-person rule as every later one -
 * {@code fx V004}'s four-eyes {@code CHECK} carries no exception for it.
 *
 * <p>Each command runs in the caller's unit of work, which owns the transaction. A decision locks
 * the version row first and, for an activation, the {@code ACTIVE} row second - one order for every
 * writer. Decisions carry no key: the version's one-way machine is the idempotency - the same
 * person's retry converges on what they already decided and writes nothing, anyone else gets
 * {@link ProposalNotPending}.
 */
@RequiredArgsConstructor
public final class PricingPolicyAdministration {

    /** The event every activation publishes - one fact, named once, in two registries. */
    static final String ACTIVATED_EVENT = "fx.PricingPolicyActivated";

    static final String PRODUCER = "fx";
    static final int EVENT_VERSION = 1;

    /** The audit record's target type for a pricing policy version. */
    static final String TARGET_TYPE = "fx_pricing_policy";

    @NonNull private final PricingPolicyStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;

    /** The provider codes the running build declares - a policy may name only these. */
    @NonNull private final Set<String> declaredProviders;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(PricingPolicyId id, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for
     * v1, a rejection, and a converged retry), {@code replayed} when the same person's retry
     * converged.
     */
    public record Decided(
            PricingPolicyId id,
            int version,
            PricingPolicyStatus status,
            Optional<PricingPolicyId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No pricing policy version has this id. */
    public static final class PricingPolicyNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PricingPolicyNotFound() {
            super("no pricing policy version has this identifier");
        }
    }

    /** The version was already decided - by somebody else, or differently - or retired. */
    public static final class ProposalNotPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalNotPending(PricingPolicyStatus status) {
            super("the pricing policy version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("a pricing policy version is activated by someone other than its proposer; the"
                    + " proposer may reject it to withdraw it");
        }
    }

    /** A proposal already awaits a decision, or a racing proposal took the version number. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ProposalPending(Throwable cause) {
            super("a proposed pricing policy version already awaits a decision", cause);
        }
    }

    /** The proposal, or a decision's reason, is not well formed; the message names the defect. */
    public static final class PricingPolicyInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public PricingPolicyInvalid(String defect) {
            super(defect);
        }
    }

    /** A proposal names a provider the running build does not declare. */
    public static final class ProviderNotDeclared extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProviderNotDeclared(String code) {
            super("the provider " + code + " is not declared by this build");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: judged ({@link PricingPolicyProposal#validate}), numbered
     * {@code max + 1}, written {@code PROPOSED} with every pair, its history row and the reasoned
     * audit record. Nothing is priced by it and nothing already decided changes.
     *
     * @throws PricingPolicyInvalid for a malformed proposal
     * @throws ProviderNotDeclared for an undeclared provider code
     * @throws ProposalPending when a proposal already awaits a decision (the caller's transaction
     *     is then aborted and must roll back)
     */
    public Proposed propose(
            Connection unitOfWork,
            PricingPolicyProposal proposal,
            Actor actor,
            Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(proposal, "proposal must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        proposal.validate(declaredProviders);
        int version = store.maxVersion(unitOfWork) + 1;
        PricingPolicyId id = PricingPolicyId.next(ids);
        store.insertProposal(unitOfWork, id, version, proposal, actor.id(), now);
        store.appendEvent(
                unitOfWork, id, Optional.empty(), PricingPolicyStatus.PROPOSED, actor.id(),
                proposal.reason(), now);
        audit(unitOfWork, actor, now, FxAuditAction.PRICING_POLICY_PROPOSED, id, proposal.reason(),
                "version=" + version + ", pairs=" + proposal.pairs().size()
                        + ", openQuoteCap=" + proposal.openQuoteCap(),
                correlation);
        return new Proposed(id, version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under the version's row lock and
     * then the {@code ACTIVE} row's, the predecessor (if any) is retired FIRST, then the proposal
     * moves {@code PROPOSED -> ACTIVE} naming its approver; both history rows, the reasoned audit
     * record and {@code fx.PricingPolicyActivated} follow, all in the caller's transaction. The
     * same person's retry converges.
     *
     * @throws PricingPolicyNotFound when no version has this id
     * @throws ProposalNotPending when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws PricingPolicyInvalid for a malformed reason
     */
    public Decided approve(
            Connection unitOfWork,
            PricingPolicyId id,
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
        PricingPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(PricingPolicyNotFound::new);
        if (row.status() == PricingPolicyStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != PricingPolicyStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        FxReasons.refuse(reason, PricingPolicyInvalid::new);
        Optional<PricingPolicyStore.VersionRow> predecessor = store.lockActive(unitOfWork);
        if (predecessor.isPresent()) {
            if (!store.retire(unitOfWork, predecessor.get().id(), now)) {
                throw new IllegalStateException(
                        "the locked ACTIVE version moved under its own row lock: the FOR UPDATE"
                                + " protocol was bypassed");
            }
        }
        if (!store.decide(unitOfWork, row.id(), PricingPolicyStatus.PROPOSED, PricingPolicyStatus.ACTIVE,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was"
                            + " bypassed");
        }
        predecessor.ifPresent(
                retired ->
                        store.appendEvent(
                                unitOfWork, retired.id(), Optional.of(PricingPolicyStatus.ACTIVE),
                                PricingPolicyStatus.RETIRED, actor.id(),
                                retirementReason(row.version(), reason), now));
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(PricingPolicyStatus.PROPOSED),
                PricingPolicyStatus.ACTIVE, actor.id(), reason, now);
        audit(unitOfWork, actor, now, FxAuditAction.PRICING_POLICY_ACTIVATED, row.id(), reason,
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
                        .with("retiredPolicyId",
                                predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.version(), PricingPolicyStatus.ACTIVE,
                predecessor.map(PricingPolicyStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included (a
     * withdrawal changes no price, so the four-eyes rule does not bind it). Terminal; frees the
     * policy for a new proposal. The same person's retry converges.
     *
     * @throws PricingPolicyNotFound when no version has this id
     * @throws ProposalNotPending when the version is no longer {@code PROPOSED}
     * @throws PricingPolicyInvalid for a malformed reason
     */
    public Decided reject(
            Connection unitOfWork,
            PricingPolicyId id,
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
        PricingPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(PricingPolicyNotFound::new);
        if (row.status() == PricingPolicyStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != PricingPolicyStatus.PROPOSED) {
            throw new ProposalNotPending(row.status());
        }
        FxReasons.refuse(reason, PricingPolicyInvalid::new);
        if (!store.decide(unitOfWork, row.id(), PricingPolicyStatus.PROPOSED, PricingPolicyStatus.REJECTED,
                actor.id(), reason, now)) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was"
                            + " bypassed");
        }
        store.appendEvent(
                unitOfWork, row.id(), Optional.of(PricingPolicyStatus.PROPOSED),
                PricingPolicyStatus.REJECTED, actor.id(), reason, now);
        audit(unitOfWork, actor, now, FxAuditAction.PRICING_POLICY_REJECTED, row.id(), reason,
                "rejected=v" + row.version() + ", byProposer=" + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.version(), PricingPolicyStatus.REJECTED, Optional.empty(), false);
    }

    /** Versions newest first, at most {@code limit}, each with its pairs. */
    public List<PricingPolicyStore.VersionView> versions(Connection unitOfWork, int limit) {
        return store.versions(unitOfWork, limit);
    }

    // ------------------------------------------------------------------ plumbing

    /** The predecessor's history reason, bounded as {@code pricing_policy_event} requires. */
    static String retirementReason(int activatedVersion, String reason) {
        String full = "retired by version " + activatedVersion + "'s activation: " + reason;
        if (full.length() <= FxReasons.MAX_LENGTH) {
            return full;
        }
        int end = FxReasons.MAX_LENGTH;
        if (Character.isHighSurrogate(full.charAt(end - 1))) {
            end--;
        }
        return full.substring(0, end);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            FxAuditAction action,
            PricingPolicyId id,
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
