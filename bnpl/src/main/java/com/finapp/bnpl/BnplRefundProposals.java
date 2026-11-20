package com.finapp.bnpl;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The operator's BNPL refund above the four-eyes threshold, by four eyes (`P17-TSK-116`, ADR-0154 decisions 1 and 4's shape;
 * finapp-admin-panel ADR-C135 decisions 3 and 4). The operator door's refund, judged above the threshold in T-ref under
 * B5, becomes a proposal here; its approval runs the existing {@link BnplRefunds#byOperator} with {@link #approval} as the
 * step inside T-ref, right after the refund row - so the decision and the refund commit together - and its rejection
 * moves nothing. No financial rule changes: the same F5/F5a entry by the same body, one step later.
 *
 * <p><strong>The approval locks the proposal first</strong> ({@link #lockForApproval}; ADR-0154 decision 1: the decider
 * locks the proposal, then reaches the subject through the existing effect), so a second approver, or an approval racing a
 * rejection, waits and is answered {@code ALREADY_DECIDED} - never a refund refusal of a race it lost. No proposer, T-ref
 * or T-dsp takes a proposal's lock, so the order proposal → purchase (B5) closes no cycle.
 *
 * <p><strong>No reservation at the proposal</strong> (ADR-0154 decision 6, the owner's standing ruling of 2026-11-20): the
 * approval's T-ref judges the bound, an open dispute and the obligation's state under its own locks on the state then;
 * refused there, nothing is written and the proposal stays open - for a later approval or a rejection.
 */
@RequiredArgsConstructor
public final class BnplRefundProposals {

    static final String TARGET_TYPE = BnplRefunds.PURCHASE_TARGET;

    @NonNull private final BnplRefundProposalStore<Connection> proposals;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Proposes the refund the door judged above the threshold, by the acting person. */
    public BnplRefundProposal propose(Connection unitOfWork, BnplPurchaseId purchaseId, Money amount, String reason,
            BnplRefundRequiresApprovalException above, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purchaseId, "purchaseId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(above, "above must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplReasons.reasoned(reason);
        if (proposals.findOpenFor(unitOfWork, purchaseId).isPresent()) {
            throw new BnplRefundProposal.Refused(BnplRefundProposal.Refused.Reason.ALREADY_PROPOSED,
                    "the purchase already has an open refund proposal; decide it first");
        }
        BnplRefundProposal proposed = BnplRefundProposal.propose(ids, clock, purchaseId, amount, above.cumulative(),
                above.threshold().version(), reasoned, actor, correlation.value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                BnplAuditAction.REFUND_PROPOSED, TARGET_TYPE, purchaseId.value().toString(), Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal=" + proposed.id().value()
                        + ", thresholdVersion=" + above.threshold().version())));
        return proposed;
    }

    /** The proposal, unlocked. */
    public Optional<BnplRefundProposal> find(Connection unitOfWork, BnplRefundProposal.Id id) {
        return proposals.find(unitOfWork, id);
    }

    /**
     * The proposal locked {@code FOR UPDATE} at the start of its approval's transaction and judged decidable by the acting
     * person - refused when decided already or when they proposed it, before the refund is attempted. Empty for an unknown
     * proposal. One exception, the lapsed claim's retry: a proposal this person approved already is answered, so T-ref's
     * refund arbiter ({@code UNIQUE (merchant_id, requested_by, refund_key)}) replays the refund born under their key - and
     * under any other key the step's decision refuses it, rolling that refund back.
     */
    public Optional<BnplRefundProposal> lockForApproval(Connection unitOfWork, BnplRefundProposal.Id id, Actor actor) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Optional<BnplRefundProposal> locked = proposals.lock(unitOfWork, id);
        locked.ifPresent(proposal -> {
            boolean approvedByThem = proposal.status() == BnplRefundProposal.Status.APPROVED
                    && proposal.decision().filter(decision -> decision.decidedBy().equals(actor.id())).isPresent();
            if (!approvedByThem) {
                proposal.requireDecidableBy(BnplRefundProposal.Status.APPROVED, actor);
            }
        });
        return locked;
    }

    /**
     * The approval of {@code id} as T-ref's step: admitted under B5 after the key's arbiter - refused there, before
     * self-dealing and the bound, when decided already or proposed by the acting person - then decided {@code APPROVED},
     * naming the refund T-ref just wrote, a refusal there rolling the refund back with it.
     */
    public BnplRefunds.ApprovalStep approval(BnplRefundProposal.Id id, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplReasons.reasoned(reason);
        return new BnplRefunds.ApprovalStep() {
            /** Before anything is judged: decided already, or the approver proposed it - refused (ADR-0154 decision 4). */
            @Override
            public void admit(Connection unitOfWork) {
                lockedProposal(unitOfWork, id).requireDecidableBy(BnplRefundProposal.Status.APPROVED, actor);
            }

            @Override
            public void within(Connection unitOfWork, UUID refund) {
                BnplRefundProposal decided = lockedProposal(unitOfWork, id).decide(BnplRefundProposal.Status.APPROVED, actor,
                        reasoned, clock, correlation.value(), Optional.of(refund));
                if (!proposals.decide(unitOfWork, decided)) {
                    throw new BnplStorageException(
                            "the proposal moved under its own FOR UPDATE - an invariant is already broken");
                }
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor,
                        decided.decision().orElseThrow().decidedAt(), BnplAuditAction.REFUND_PROPOSAL_APPROVED, TARGET_TYPE,
                        decided.purchaseId().value().toString(), Optional.of(reasoned), AuditOutcome.SUCCEEDED, correlation,
                        Optional.of("proposal=" + id.value() + ", refund=" + refund + ", proposedBy="
                                + decided.proposedBy())));
            }
        };
    }

    private BnplRefundProposal lockedProposal(Connection unitOfWork, BnplRefundProposal.Id id) {
        return proposals.lock(unitOfWork, id).orElseThrow(() -> new BnplStorageException(
                "an approved refund proposal vanished under its own approval"));
    }

    /** Rejects the proposal: nothing moves. Empty for an unknown proposal; refused when decided or by its proposer. */
    public Optional<BnplRefundProposal> reject(Connection unitOfWork, BnplRefundProposal.Id id, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplReasons.reasoned(reason);
        Optional<BnplRefundProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        BnplRefundProposal decided = locked.get().decide(BnplRefundProposal.Status.REJECTED, actor, reasoned, clock,
                correlation.value(), Optional.empty());
        if (!proposals.decide(unitOfWork, decided)) {
            throw new BnplStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                BnplAuditAction.REFUND_PROPOSAL_REJECTED, TARGET_TYPE, decided.purchaseId().value().toString(),
                Optional.of(reasoned), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("proposal=" + id.value() + ", proposedBy=" + decided.proposedBy())));
        return Optional.of(decided);
    }
}
