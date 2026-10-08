package com.finapp.credit;

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
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Credit policies change only forward, and only under four eyes (`P10-TSK-012`; ADR-0086 sections 4-8;
 * {@code INV-CRD-05}, {@code INV-CRD-10}, {@code INV-HIST-04}, {@code INV-AUD-04}): a holder of
 * {@code CREDIT_POLICY_ADMINISTER} proposes a whole new version for a product - complete, its rules born with it and
 * frozen; a different holder activates it, retiring the product's predecessor in the SAME transaction, so a product has
 * at most one {@code ACTIVE} version at every statement and its effective periods meet; or anyone rejects it, the
 * proposer included. {@link ScorecardAdministration}'s protocol, verbatim in shape.
 *
 * <p><strong>Ten proposers, one proposal.</strong> A proposal takes the product's advisory lock (namespace 10, on
 * {@code hashtext(product)}) first and then looks for a pending version; the partial unique over {@code PROPOSED} is the
 * backstop. A product's name hashing with a scorecard family's only serialises the two administrations a moment longer -
 * never a wrong answer. <strong>Ten approvers, one activation.</strong> A decision locks the version row
 * {@code FOR UPDATE} and acts only from {@code PROPOSED}; the others find it decided ({@link PolicyStale}). An activation
 * locks the {@code ACTIVE} row second - one order for every writer - and so waits for every decision holding the
 * predecessor {@code FOR SHARE} as its pin (ADR-0086 section 7). The same person's retry of their own decision converges.
 *
 * <p><strong>Completeness is judged before anything is written</strong>: the {@link CreditPolicy} the proposal carries
 * was constructed, so it is well typed and has a fallback for every source kind it reads - an incomplete policy never
 * reaches a decision. No seed exemption: {@code credit V008} seeds each product's v1 as a proposal by
 * {@code migration:V008}; a person activates it, held to the same four-eyes {@code CHECK} as every version.
 */
@RequiredArgsConstructor
public final class CreditPolicyAdministration {

    /** The event every activation publishes. */
    static final String ACTIVATED_EVENT = "credit.CreditPolicyVersionActivated";

    static final String PRODUCER = "credit";
    static final int EVENT_VERSION = 1;

    /** The audit record's target type for a credit policy version. */
    static final String TARGET_TYPE = "credit_policy_version";

    /** A reason's bound. */
    static final int REASON_BOUND = 1000;

    @NonNull private final CreditPolicyStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(CreditPolicyVersionId id, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            CreditPolicyVersionId id,
            int version,
            CreditPolicyStatus status,
            Optional<CreditPolicyVersionId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No credit policy version has this id, or no version was active for the product at the instant. */
    public static final class PolicyNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyNotFound() {
            super("no credit policy version answers this request");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently. */
    public static final class PolicyStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyStale(CreditPolicyStatus status) {
            super("the credit policy version is no longer awaiting a decision: it is " + status.name());
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("a credit policy version is activated by someone other than its proposer; the proposer may reject it");
        }
    }

    /** A version of the product already awaits a decision. */
    public static final class ProposalPending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ProposalPending() {
            super("a proposed credit policy version already awaits a decision for this product");
        }
    }

    /** A decision or a proposal carries no reason, or one past its bound. */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired() {
            super("a reason of 1 to " + REASON_BOUND + " characters is required");
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: the policy already judged complete ({@link CreditPolicy}'s construction), the
     * product locked, numbered {@code max + 1}, written {@code PROPOSED} with every rule, its history row and the
     * reasoned audit record.
     *
     * @throws ProposalPending when a version of the product already awaits a decision
     * @throws ReasonRequired for a missing or unbounded reason
     */
    public Proposed propose(
            Connection unitOfWork, CreditPolicy policy, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        store.lockProduct(unitOfWork, policy.product());
        if (store.proposalPending(unitOfWork, policy.product())) {
            throw new ProposalPending();
        }
        int version = store.maxVersion(unitOfWork, policy.product()) + 1;
        CreditPolicyVersionId id = CreditPolicyVersionId.next(ids);
        store.insertProposal(unitOfWork, id, version, policy, actor.id(), reasoned);
        store.appendEvent(unitOfWork, ids.next(), id, Optional.empty(), CreditPolicyStatus.PROPOSED, actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.POLICY_VERSION_PROPOSED, id, reasoned,
                "product=" + policy.product().name() + ", version=" + version + ", rules=" + policy.rules().size()
                        + ", sources=" + policy.sourceKinds(),
                correlation);
        return new Proposed(id, version);
    }

    // ------------------------------------------------------------------ approve

    /**
     * Activates a pending version - a different person's act. Under the version's row lock, then the {@code ACTIVE}
     * row's, the predecessor (if any) is retired FIRST and the proposal moves {@code PROPOSED -> ACTIVE} naming its
     * approver; the effective period is the database's - the predecessor's end and the successor's start one
     * {@code transaction_timestamp()}. Both history rows, the reasoned audit record and
     * {@code credit.CreditPolicyVersionActivated} commit with it.
     *
     * @throws PolicyNotFound when no version has this id
     * @throws PolicyStale when the version is no longer {@code PROPOSED}
     * @throws SelfApprovalRefused when the approver proposed it ({@code INV-AUD-04})
     * @throws ReasonRequired for a missing or unbounded reason
     */
    public Decided approve(
            Connection unitOfWork, CreditPolicyVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        CreditPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(PolicyNotFound::new);
        if (row.status() == CreditPolicyStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != CreditPolicyStatus.PROPOSED) {
            throw new PolicyStale(row.status());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        Optional<CreditPolicyStore.VersionRow> predecessor = store.lockActive(unitOfWork, row.product());
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        Instant effectiveFrom = store.decide(unitOfWork, row.id(), CreditPolicyStatus.ACTIVE, actor.id(), reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"))
                .orElseThrow(() -> new IllegalStateException("an activation is stamped with its effective start"));
        predecessor.ifPresent(retired -> store.appendEvent(unitOfWork, ids.next(), retired.id(),
                Optional.of(CreditPolicyStatus.ACTIVE), CreditPolicyStatus.RETIRED, actor.id(),
                bounded("retired by version " + row.version() + "'s activation: " + reasoned)));
        store.appendEvent(unitOfWork, ids.next(), row.id(), Optional.of(CreditPolicyStatus.PROPOSED),
                CreditPolicyStatus.ACTIVE, actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.POLICY_VERSION_ACTIVATED, row.id(), reasoned,
                "product=" + row.product().name() + ", activated=v" + row.version()
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
                        clock.instant(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // Never the rules or a threshold - they stay inside credit and the operator surface.
                EventPayload.of()
                        .with("product", row.product().name())
                        .with("version", Integer.toString(row.version()))
                        .with("effectiveFromEpochMilli", Long.toString(effectiveFrom.toEpochMilli()))
                        .with("predecessorId", predecessor.map(p -> p.id().value().toString()).orElse("none"))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.version(), CreditPolicyStatus.ACTIVE,
                predecessor.map(CreditPolicyStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included. Terminal; frees the product for
     * a new proposal - a correction is a rejection and a new proposal. The same person's retry converges.
     */
    public Decided reject(
            Connection unitOfWork, CreditPolicyVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = reasoned(reason);
        CreditPolicyStore.VersionRow row = store.lock(unitOfWork, id).orElseThrow(PolicyNotFound::new);
        if (row.status() == CreditPolicyStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != CreditPolicyStatus.PROPOSED) {
            throw new PolicyStale(row.status());
        }
        if (store.decide(unitOfWork, row.id(), CreditPolicyStatus.REJECTED, actor.id(), reasoned).isEmpty()) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, ids.next(), row.id(), Optional.of(CreditPolicyStatus.PROPOSED),
                CreditPolicyStatus.REJECTED, actor.id(), reasoned);
        audit(unitOfWork, actor, CreditAuditAction.POLICY_VERSION_REJECTED, row.id(), reasoned,
                "product=" + row.product().name() + ", rejected=v" + row.version() + ", byProposer="
                        + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.version(), CreditPolicyStatus.REJECTED, Optional.empty(), false);
    }

    // ------------------------------------------------------------------ read

    /**
     * The product's policy version active at {@code at} - answered from the effective periods alone
     * ({@code INV-CRD-05}); with no instant, the version {@code ACTIVE} now, by status, so no application clock decides.
     * A decision never relies on this read: it pins its version.
     *
     * @throws PolicyNotFound when no version was (or is) active
     */
    public CreditPolicyStore.PolicyVersion activeAt(Connection unitOfWork, CreditProduct product, Optional<Instant> at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(at, "at");
        CreditPolicyVersionId id = (at.isPresent() ? store.activeAt(unitOfWork, product, at.get())
                        : store.active(unitOfWork, product))
                .orElseThrow(PolicyNotFound::new);
        return store.policy(unitOfWork, id).orElseThrow(PolicyNotFound::new);
    }

    // ------------------------------------------------------------------ plumbing

    private static String reasoned(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > REASON_BOUND) {
            throw new ReasonRequired();
        }
        return reason;
    }

    private static String bounded(String reason) {
        return reason.length() <= REASON_BOUND ? reason : reason.substring(0, REASON_BOUND);
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            CreditAuditAction action,
            CreditPolicyVersionId id,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        clock.instant(),
                        action,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
