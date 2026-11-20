package com.finapp.merchant;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An operator payout above the four-eyes threshold, by four eyes (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decisions 3 and 4).
 * The operator's payout, judged above the threshold under the merchant's lock, becomes a proposal here; its approval runs the
 * existing {@link MerchantPayouts} with {@link #approval} as the step inside the payout's dispatch transaction - so the
 * decision and the dispatch commit together - and its rejection moves nothing. No financial rule changes: the same hold, the
 * same payout row, the same posting at its outcome, one step later.
 *
 * <p><strong>No reservation at the proposal</strong> (ADR-0154, the owner's standing ruling of 2026-11-20): the approval's
 * payout judges the payable's available funds under its own locks on the state then; refused there, nothing is
 * written and the proposal stays open - for a later approval or a rejection.
 */
@RequiredArgsConstructor
public final class PayoutProposals {

    static final String TARGET_TYPE = "merchant";

    @NonNull private final PayoutProposalStore<Connection> proposals;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** Proposes the payout the door judged above the threshold, by the acting person. */
    public PayoutProposal propose(Connection unitOfWork, MerchantId merchantId, Money amount, String reason,
            PayoutRequiresApprovalException above) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        if (proposals.findOpenFor(unitOfWork, merchantId).isPresent()) {
            throw new PayoutProposal.Refused(PayoutProposal.Refused.Reason.ALREADY_PROPOSED,
                    "the merchant already has an open payout proposal; decide it first");
        }
        PayoutProposal proposed = PayoutProposal.propose(ids, clock, merchantId, amount,
                above.threshold().version(), reason, actor, correlation.correlationId().value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                MerchantAuditAction.PAYOUT_PROPOSED, TARGET_TYPE, merchantId.value().toString(), Optional.of(reason),
                AuditOutcome.SUCCEEDED, correlation.correlationId(), Optional.of("proposal=" + proposed.id().value()
                        + ", thresholdVersion=" + above.threshold().version())));
        return proposed;
    }

    /** The proposal, unlocked: what its approval asks the payout command to do. */
    public Optional<PayoutProposal> find(Connection unitOfWork, PayoutProposal.Id id) {
        return proposals.find(unitOfWork, id);
    }

    /** A proposal as its decider reads it (`P17-TSK-124`): the proposal and the merchant it would pay, as they stand. */
    public record Read(PayoutProposal proposal, Merchant merchant) {}

    /**
     * The proposal and its merchant, unlocked - what its decider reads before deciding (`P17-TSK-124`). Empty for an unknown
     * proposal. Nothing is written here: the caller records the serving in this unit of work.
     */
    public Optional<Read> read(Connection unitOfWork, PayoutProposal.Id id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return proposals.find(unitOfWork, id).map(proposal -> new Read(proposal, merchants.findById(unitOfWork,
                proposal.merchantId()).orElseThrow(() -> new MerchantStorageException(
                        "a payout proposal names a merchant that does not exist"))));
    }

    /**
     * The approval of {@code id} as the payout command's step: inside its dispatch transaction, the proposal locked, decided
     * {@code APPROVED} by the acting person - refused when decided already or when they proposed it, which rolls the dispatch
     * back - and naming the payout the command just wrote.
     */
    public MerchantPayouts.ApprovalStep approval(PayoutProposal.Id id, String reason) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return new MerchantPayouts.ApprovalStep() {
            @Override
            public void admit(Connection unitOfWork) {
                proposals.lock(unitOfWork, id).orElseThrow(() -> new MerchantStorageException(
                        "an approved payout proposal vanished under its own approval"))
                        .decidableAs(PayoutProposal.Status.APPROVED, SecurityContext.require());
            }

            @Override
            public void within(Connection unitOfWork, MerchantPayoutId payout) {
                Actor actor = SecurityContext.require();
                Correlation correlation = resolvedCorrelation();
                PayoutProposal locked = proposals.lock(unitOfWork, id).orElseThrow(() -> new MerchantStorageException(
                        "an approved payout proposal vanished under its own approval"));
                PayoutProposal decided = locked.decide(PayoutProposal.Status.APPROVED, actor, reason, clock,
                        correlation.correlationId().value(), Optional.of(payout));
                if (!proposals.decide(unitOfWork, decided)) {
                    throw new MerchantStorageException(
                            "the proposal moved under its own FOR UPDATE - an invariant is already broken");
                }
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor,
                        decided.decision().orElseThrow().decidedAt(), MerchantAuditAction.PAYOUT_PROPOSAL_APPROVED,
                        TARGET_TYPE, decided.merchantId().value().toString(), Optional.of(reason), AuditOutcome.SUCCEEDED,
                        correlation.correlationId(), Optional.of("proposal=" + id.value() + ", payout=" + payout.value()
                                + ", proposedBy=" + decided.proposedBy())));
            }
        };
    }

    /** Rejects the proposal: nothing moves. Empty for an unknown proposal; refused when decided or by its proposer. */
    public Optional<PayoutProposal> reject(Connection unitOfWork, PayoutProposal.Id id, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<PayoutProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PayoutProposal decided = locked.get().decide(PayoutProposal.Status.REJECTED, actor, reason, clock,
                correlation.correlationId().value(), Optional.empty());
        if (!proposals.decide(unitOfWork, decided)) {
            throw new MerchantStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                MerchantAuditAction.PAYOUT_PROPOSAL_REJECTED, TARGET_TYPE, decided.merchantId().value().toString(),
                Optional.of(reason), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                Optional.of("proposal=" + id.value() + ", proposedBy=" + decided.proposedBy())));
        return Optional.of(decided);
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                "a payout proposal act runs inside the request's correlation scope"));
    }
}
