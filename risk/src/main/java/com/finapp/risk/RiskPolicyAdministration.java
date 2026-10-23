package com.finapp.risk;

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
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Risk policy versions change only forward, and only under four eyes (`P13-TSK-002`; ADR-0109 decisions 5, 6;
 * {@code RISK_LIFECYCLES.md} section 4.1; {@code INV-RSK-05}, {@code INV-RSK-07}, {@code INV-AUD-04},
 * {@code INV-HIST-04}): a holder of {@code RISK_POLICY_ADMINISTER} proposes a whole new version for an operation - its
 * {@link RiskPolicy} already judged complete and within its kinds' schemas; a DIFFERENT holder, from a
 * {@code MULTI_FACTOR} session (the door's rank), activates it, retiring the operation's predecessor in the SAME
 * transaction, so an operation has at most one {@code ACTIVE} version at every instant, the effective periods meet,
 * and an evaluation's pin never reads "neither"; anyone, the proposer included, may reject it. bnpl's
 * {@code BnplTermsAdministration}, with the Phase 12 → 13 gate's A3-I1 rule applied forward ({@link RiskPolicies}).
 *
 * <p><strong>The lock order</strong> (A0 to A2): every act takes the operation's administration lock first (advisory
 * namespace {@code 13}, {@code hashtext(operation)} - T-pol, before any version row); a decision then locks the version
 * row {@code FOR UPDATE} and acts only from {@code PROPOSED}, and an activation locks the {@code ACTIVE} row last - so it
 * waits for every evaluation holding the predecessor {@code FOR SHARE} as its pin, and none of them ever pins a version
 * retiring under it. <strong>Ten proposers, ten versions</strong> numbered without a gap ({@code max + 1} under A0).
 * <strong>Ten activators, one activation</strong> ({@link PolicyStale} for the others; the partial unique over
 * {@code ACTIVE} the backstop). Activation is monotonic in version number: a proposal numbered below the version in
 * force is {@link PolicyStale} (superseded), so the retire-beside-successor trigger's "later successor" rank always
 * holds. An activation and a rejection racing leave exactly one. The same person's retry of their own decision
 * converges. Every decision instant is the database's, written after the locks ({@code clock_timestamp()}).
 *
 * <p><strong>Never activated by a migration</strong> (C8): nothing seeds a version; production starts with none, and
 * every seam answers its no-policy fail-safe until two persons activate one and the runbook's gate is passed.
 */
@RequiredArgsConstructor
public final class RiskPolicyAdministration {

    /** The event every activation publishes - operation and version only. */
    public static final String ACTIVATED_EVENT = "risk.RiskPolicyActivated";

    static final String PRODUCER = "risk";
    static final int EVENT_VERSION = 1;

    /** The audit record's and the event's target type. */
    public static final String TARGET_TYPE = "risk_policy_version";

    @NonNull private final RiskPolicyStore store;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** A proposal's outcome: the new version, {@code PROPOSED}. */
    public record Proposed(RiskPolicyVersionId id, RiskOperation operation, int version) {}

    /**
     * A decision's outcome; {@code retired} names the predecessor an activation retired (empty for the first, a
     * rejection, and a converged retry), {@code replayed} when the same person's retry converged.
     */
    public record Decided(
            RiskPolicyVersionId id,
            RiskOperation operation,
            int version,
            RiskPolicyVersionStatus status,
            Optional<RiskPolicyVersionId> retired,
            boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** No policy version has this id. */
    public static final class PolicyNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyNotFound() {
            super("no risk policy version answers this request");
        }
    }

    /** The version is no longer {@code PROPOSED} - decided by somebody else, or differently - or is superseded. */
    public static final class PolicyStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyStale(String detail) {
            super(detail);
        }
    }

    /** The proposer tried to activate their own version ({@code INV-AUD-04}, {@code INV-RSK-07}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(RiskPolicyVersionId id, RiskOperation operation, int version) {
            super("a risk policy version is activated by someone other than its proposer; the proposer may reject it");
            this.target = id.value().toString();
            this.summary = "operation=" + operation.name() + ", version=v" + version + ", act=policy:activate";
        }

        /** The proposal the activator proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the proposal and the act - never a threshold or a weight. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ propose

    /**
     * Proposes a whole new version: {@code policy} already judged ({@link RiskPolicy}'s construction) before anything
     * is locked or written; then the operation locked (A0), numbered {@code max + 1}, written {@code PROPOSED} with its
     * content, its history row and the reasoned audit record.
     *
     * @throws RiskReasons.ReasonRequired for a missing, unbounded or instrument-bearing reason
     */
    public Proposed propose(Connection unitOfWork, RiskPolicy policy, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        store.lockOperation(unitOfWork, policy.operation());
        int version = store.maxVersion(unitOfWork, policy.operation()) + 1;
        RiskPolicyVersionId id = RiskPolicyVersionId.next(ids);
        store.insertProposal(unitOfWork, new RiskPolicyStore.NewProposal(id, version, policy, actor, reasoned, correlation,
                ids.next()));
        audit(unitOfWork, actor, RiskAuditAction.POLICY_PROPOSED, id, Optional.of(reasoned),
                "operation=" + policy.operation().name() + ", version=v" + version + ", rules=" + policy.rules().size(),
                correlation);
        return new Proposed(id, policy.operation(), version);
    }

    // ------------------------------------------------------------------ activate

    /**
     * Activates a pending version - a different person's act. Under A0, then the version's row lock (A1), then the
     * {@code ACTIVE} row's (A2), the predecessor (if any) is retired FIRST and the proposal moves
     * {@code PROPOSED -> ACTIVE} naming its activator; the effective period is the database's - the predecessor's end and
     * the successor's start one instant, written after the locks and clamped so a database clock that steps back neither
     * refuses the activation nor overlaps two periods. Both history rows, the reasoned audit records (the predecessor's
     * retirement audited in the activator's name) and {@code risk.RiskPolicyActivated} commit with it.
     *
     * @throws PolicyNotFound when no version has this id
     * @throws PolicyStale when the version is no longer {@code PROPOSED}, or is numbered below the version in force
     * @throws SelfApprovalRefused when the activator proposed it ({@code INV-AUD-04})
     * @throws RiskPolicy.PolicyInvalid when the version names an engine version this build no longer holds
     */
    public Decided activate(
            Connection unitOfWork, RiskPolicyVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskPolicyStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == RiskPolicyVersionStatus.ACTIVE && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.operation(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != RiskPolicyVersionStatus.PROPOSED) {
            throw new PolicyStale("the risk policy version is no longer awaiting a decision: it is " + row.status().name());
        }
        if (row.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row.id(), row.operation(), row.version());
        }
        if (!holdsEngine(row.engine())) {
            throw new RiskPolicy.PolicyInvalid("the version is written for " + row.engine()
                    + ", an engine version this build does not hold; propose it anew for the current engine");
        }
        Optional<RiskPolicyStore.VersionRow> predecessor = store.lockActive(unitOfWork, row.operation());
        if (predecessor.isPresent() && predecessor.get().version() > row.version()) {
            throw new PolicyStale("the risk policy version is superseded: v" + predecessor.get().version()
                    + " of " + row.operation().name() + " is in force; propose a later version");
        }
        if (predecessor.isPresent() && !store.retire(unitOfWork, predecessor.get().id())) {
            throw new IllegalStateException(
                    "the locked ACTIVE version moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
        store.decide(unitOfWork, row.id(), RiskPolicyVersionStatus.ACTIVE, actor, reasoned)
                .orElseThrow(() -> new IllegalStateException(
                        "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed"))
                .effectiveFrom()
                .orElseThrow(() -> new IllegalStateException("an activation is stamped with its effective start"));
        predecessor.ifPresent(retired -> {
            store.appendEvent(unitOfWork, new RiskPolicyStore.HistoryRow(ids.next(), retired.id(), retired.operation(),
                    Optional.of(RiskPolicyVersionStatus.ACTIVE), RiskPolicyVersionStatus.RETIRED, actor,
                    RiskReasons.bounded("retired by version " + row.version() + "'s activation: " + reasoned), correlation));
            audit(unitOfWork, actor, RiskAuditAction.POLICY_RETIRED, retired.id(), Optional.empty(),
                    "operation=" + retired.operation().name() + ", retired=v" + retired.version() + ", successor=v"
                            + row.version(),
                    correlation);
        });
        store.appendEvent(unitOfWork, new RiskPolicyStore.HistoryRow(ids.next(), row.id(), row.operation(),
                Optional.of(RiskPolicyVersionStatus.PROPOSED), RiskPolicyVersionStatus.ACTIVE, actor, reasoned,
                correlation));
        audit(unitOfWork, actor, RiskAuditAction.POLICY_ACTIVATED, row.id(), Optional.of(reasoned),
                "operation=" + row.operation().name() + ", activated=v" + row.version()
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
                // The operation and the version only - never a threshold, a weight or a person (INV-RSK-08).
                EventPayload.of()
                        .with("operation", row.operation().name())
                        .with("version", Integer.toString(row.version()))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
        return new Decided(row.id(), row.operation(), row.version(), RiskPolicyVersionStatus.ACTIVE,
                predecessor.map(RiskPolicyStore.VersionRow::id), false);
    }

    // ------------------------------------------------------------------ reject

    /**
     * Rejects a pending version - a reasoned act by any holder, the proposer included. Terminal; a correction is a
     * rejection and a new proposal. The same person's retry converges.
     *
     * @throws PolicyNotFound when no version has this id
     * @throws PolicyStale when the version is no longer {@code PROPOSED}
     */
    public Decided reject(
            Connection unitOfWork, RiskPolicyVersionId id, Actor actor, String reason, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        String reasoned = RiskReasons.reasoned(reason);
        RiskPolicyStore.VersionRow row = locked(unitOfWork, id);
        if (row.status() == RiskPolicyVersionStatus.REJECTED && row.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(row.id(), row.operation(), row.version(), row.status(), Optional.empty(), true);
        }
        if (row.status() != RiskPolicyVersionStatus.PROPOSED) {
            throw new PolicyStale("the risk policy version is no longer awaiting a decision: it is " + row.status().name());
        }
        if (store.decide(unitOfWork, row.id(), RiskPolicyVersionStatus.REJECTED, actor, reasoned).isEmpty()) {
            throw new IllegalStateException(
                    "the locked proposal was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        store.appendEvent(unitOfWork, new RiskPolicyStore.HistoryRow(ids.next(), row.id(), row.operation(),
                Optional.of(RiskPolicyVersionStatus.PROPOSED), RiskPolicyVersionStatus.REJECTED, actor, reasoned,
                correlation));
        audit(unitOfWork, actor, RiskAuditAction.POLICY_REJECTED, row.id(), Optional.of(reasoned),
                "operation=" + row.operation().name() + ", rejected=v" + row.version() + ", byProposer="
                        + row.proposedBy().equals(actor.id()),
                correlation);
        return new Decided(row.id(), row.operation(), row.version(), RiskPolicyVersionStatus.REJECTED, Optional.empty(),
                false);
    }

    /**
     * Records a refused self-approval {@code FAILED} ({@link RiskAuditAction#SELF_APPROVAL_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the act rolled back, so the attempt is kept
     * ({@code INV-RSK-07}).
     */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        SelfApprovalRefusals.record(audit, ids, clock, unitOfWork, actor, TARGET_TYPE, refused.target(), refused.summary(),
                correlation);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The version read under its operation's lock (A0) and then locked itself (A1): read once to learn the operation
     * (frozen), the operation locked, the row locked - the state the decision acts on is the one every committed
     * decision left.
     */
    private RiskPolicyStore.VersionRow locked(Connection unitOfWork, RiskPolicyVersionId id) {
        RiskPolicyStore.VersionRow first = store.find(unitOfWork, id).orElseThrow(PolicyNotFound::new);
        store.lockOperation(unitOfWork, first.operation());
        return store.lock(unitOfWork, id).orElseThrow(PolicyNotFound::new);
    }

    private static boolean holdsEngine(String name) {
        for (RiskEngine engine : RiskEngine.values()) {
            if (engine.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            RiskAuditAction action,
            RiskPolicyVersionId id,
            Optional<String> reason,
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
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
