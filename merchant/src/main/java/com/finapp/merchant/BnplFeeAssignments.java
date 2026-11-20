package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A merchant's BNPL fee-schedule assignment by four eyes (`P17-TSK-116`, ADR-0154's shape; finapp-admin-panel ADR-C135
 * decision 3: merchant fee assignment is financial configuration). The door's assignment becomes a proposal here - judged
 * first by {@link BnplPricing#wouldAssign}, {@link BnplPricing#assign}'s own rules on an unlocked read, so a request that
 * would be refused is refused at once, and one that changes nothing converges with nothing proposed. Its approval performs
 * {@link BnplPricing#assign} <strong>unchanged</strong> under the approver - every rule judged again under the merchant's
 * {@code FOR UPDATE}, merchant V010's trigger for every writer - with the decision in the same transaction; a rejection
 * changes nothing.
 *
 * <p><strong>Serialization</strong> (ADR-0154 decision 1): the decider locks the proposal, then reaches the merchant through
 * the existing effect; a proposer takes no lock (V021's partial unique index admits one open proposal per merchant and
 * product), so the order proposal → merchant closes no cycle. Two approvers, or an approval racing a rejection: one winner.
 * <strong>No reservation, no expiry</strong> (decision 6): refused at approval - the schedule gained a retaining version, the
 * merchant closed - nothing is written and the proposal stays open.
 */
@RequiredArgsConstructor
public final class BnplFeeAssignments {

    static final String TARGET_TYPE = BnplPricing.ASSIGNMENT_TARGET_TYPE;

    @NonNull private final BnplFeeAssignmentProposalStore<Connection> proposals;
    @NonNull private final BnplPricing pricing;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The door's answer: a proposal, or nothing to propose because the pointer is there already. */
    public record Proposed(Optional<BnplFeeAssignmentProposal> proposal) {
        public Proposed {
            Objects.requireNonNull(proposal, "proposal");
        }
    }

    /**
     * Proposes pointing the merchant's {@code product} at {@code scheduleId}, by {@code actor} - or converges, proposing
     * nothing, when the pointer is there already.
     *
     * @throws UnknownMerchantException no merchant answers to the identifier
     * @throws UnknownFeeScheduleException no schedule does
     * @throws FeeCurrencyMismatchException the schedule does not price the merchant's settlement currency
     * @throws BnplPricing.ScheduleNotReturning a version of the schedule retains its fee
     * @throws BnplFeeAssignmentProposal.Refused {@code ALREADY_PROPOSED}: one is open for the product
     */
    public Proposed propose(Connection unitOfWork, MerchantId merchantId, MerchantBnplProduct product,
            FeeScheduleId scheduleId, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplEnablement.reasoned(reason);
        if (!pricing.wouldAssign(unitOfWork, merchantId, product, scheduleId)) {
            return new Proposed(Optional.empty());
        }
        BnplFeeAssignmentProposal proposed = BnplFeeAssignmentProposal.propose(ids, clock, merchantId, product, scheduleId,
                reasoned, actor, correlation.value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                MerchantAuditAction.BNPL_FEE_ASSIGNMENT_PROPOSED, TARGET_TYPE, merchantId.value().toString(),
                Optional.of(reasoned), AuditOutcome.SUCCEEDED, correlation, Optional.of("proposal=" + proposed.id().value()
                        + ", product=" + product.name() + ", to=" + scheduleId)));
        return new Proposed(Optional.of(proposed));
    }

    /**
     * Approves the proposal by {@code actor}: locked, judged decidable, {@link BnplPricing#assign} performed under the
     * approver, the decision written beside it. Empty for an unknown proposal.
     *
     * @throws BnplFeeAssignmentProposal.Refused decided already, or by its proposer
     */
    public Optional<BnplFeeAssignmentProposal> approve(Connection unitOfWork, BnplFeeAssignmentProposal.Id id, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplEnablement.reasoned(reason);
        Optional<BnplFeeAssignmentProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        BnplFeeAssignmentProposal proposal = locked.get();
        proposal.requireDecidableBy(BnplFeeAssignmentProposal.Status.APPROVED, actor);
        pricing.assign(unitOfWork, proposal.merchantId(), proposal.product(), proposal.feeScheduleId(),
                proposal.proposalReason(), actor, correlation);
        BnplFeeAssignmentProposal decided = proposal.decide(BnplFeeAssignmentProposal.Status.APPROVED, actor, reasoned, clock,
                correlation.value());
        decided(unitOfWork, decided, MerchantAuditAction.BNPL_FEE_ASSIGNMENT_APPROVED, reasoned, actor, correlation);
        return Optional.of(decided);
    }

    /**
     * Rejects the proposal by {@code actor}: nothing changes. Empty for an unknown proposal.
     *
     * @throws BnplFeeAssignmentProposal.Refused decided already, or by its proposer
     */
    public Optional<BnplFeeAssignmentProposal> reject(Connection unitOfWork, BnplFeeAssignmentProposal.Id id, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplEnablement.reasoned(reason);
        Optional<BnplFeeAssignmentProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        BnplFeeAssignmentProposal decided = locked.get().decide(BnplFeeAssignmentProposal.Status.REJECTED, actor, reasoned,
                clock, correlation.value());
        decided(unitOfWork, decided, MerchantAuditAction.BNPL_FEE_ASSIGNMENT_REJECTED, reasoned, actor, correlation);
        return Optional.of(decided);
    }

    private void decided(Connection unitOfWork, BnplFeeAssignmentProposal decided, MerchantAuditAction action,
            String reasoned, Actor actor, CorrelationId correlation) {
        if (!proposals.decide(unitOfWork, decided)) {
            throw new MerchantStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                action, TARGET_TYPE, decided.merchantId().value().toString(), Optional.of(reasoned), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("proposal=" + decided.id().value() + ", product=" + decided.product().name()
                        + ", proposedBy=" + decided.proposedBy())));
    }
}
