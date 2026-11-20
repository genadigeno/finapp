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
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A merchant's closure by four eyes (`P17-TSK-031`, ADR-0154; finapp-admin-panel ADR-C135 decisions 3 and 4: closure is
 * terminal, so four eyes; suspension and reinstatement stay one person's). A proposal is a new state before the existing
 * effect, and the approval performs that effect - {@link MerchantAdministration#close}, its settled-money judgement and the
 * payable's closing included - in the approval's own transaction. No financial rule changes.
 *
 * <p><strong>Serialization.</strong> The proposer takes the merchant {@code FOR UPDATE}; one open proposal per merchant is
 * judged on the locked row (V019's partial unique index beneath it). The decider takes the proposal {@code FOR UPDATE}, so two
 * approvers, or an approval racing a rejection, produce one winner; the approval then reaches the merchant's lock through
 * {@link MerchantAdministration}. A proposer never waits on a proposal's lock, so no cycle.
 */
@RequiredArgsConstructor
public final class MerchantClosures {

    static final String TARGET_TYPE = "merchant";

    /** A decided proposal and the merchant's status after it. */
    public record Decided(MerchantClosureProposal proposal, MerchantStatus merchantStatus) {}

    @NonNull private final MerchantStore<java.sql.Connection> merchants;
    @NonNull private final MerchantClosureProposalStore<Connection> proposals;
    @NonNull private final MerchantAdministration administration;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Proposes the merchant's closure, by the acting person - empty for an unknown merchant. Refused when the merchant is not
     * {@code ACTIVE} or already has an open proposal; nothing is written then. Whether it is settled is the approval's
     * judgement, on the state then.
     */
    public Optional<MerchantClosureProposal> propose(Connection unitOfWork, MerchantId merchantId, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        requireReason(reason);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<Merchant> locked = merchants.findByIdForUpdate(unitOfWork, merchantId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        if (proposals.findOpenFor(unitOfWork, merchantId).isPresent()) {
            throw new MerchantClosureRefusedException(MerchantClosureRefusedException.Reason.ALREADY_PROPOSED,
                    "the merchant already has an open closure proposal; decide it first");
        }
        MerchantClosureProposal proposed = MerchantClosureProposal.propose(ids, clock, locked.get(), reason, actor,
                correlation.correlationId().value());
        proposals.insert(unitOfWork, proposed);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, proposed.proposedAt(),
                MerchantAuditAction.CLOSURE_PROPOSED, TARGET_TYPE, merchantId.value().toString(), Optional.of(reason),
                AuditOutcome.SUCCEEDED, correlation.correlationId(), Optional.of("proposal=" + proposed.id().value())));
        return Optional.of(proposed);
    }

    /**
     * Approves the proposal: the merchant is closed in this transaction by {@link MerchantAdministration#close} under the
     * approver - whose reason its {@code merchant.MerchantClosed} record carries. Empty for an unknown proposal. Refused when
     * decided already, when the approver proposed it, when the merchant is no longer {@code ACTIVE}, or when it is still owed
     * money or has money in flight ({@link MerchantNotSettledException}); nothing is written then, and the proposal stays open.
     */
    public Optional<Decided> approve(Connection unitOfWork, MerchantClosureProposalId id, String reason) {
        return decide(unitOfWork, id, ClosureProposalStatus.APPROVED, reason);
    }

    /** Rejects the proposal: nothing moves. Empty for an unknown proposal; refused as {@link #approve}, never for the merchant. */
    public Optional<Decided> reject(Connection unitOfWork, MerchantClosureProposalId id, String reason) {
        return decide(unitOfWork, id, ClosureProposalStatus.REJECTED, reason);
    }

    private Optional<Decided> decide(Connection unitOfWork, MerchantClosureProposalId id, ClosureProposalStatus outcome,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        requireReason(reason);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        Optional<MerchantClosureProposal> locked = proposals.lock(unitOfWork, id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MerchantClosureProposal decided = locked.get().decide(outcome, actor, reason, clock,
                correlation.correlationId().value());
        MerchantStatus after;
        if (outcome == ClosureProposalStatus.APPROVED) {
            Merchant current = merchants.findByIdForUpdate(unitOfWork, decided.merchantId())
                    .orElseThrow(() -> new MerchantStorageException("a closure proposal names a merchant that does not exist"));
            if (!current.status().canTransitionTo(MerchantStatus.CLOSED)) {
                // The administration converges on an already-CLOSED merchant; an approval must not report a closure it did
                // not make, and a SUSPENDED merchant is reinstated before it is closed.
                throw new MerchantClosureRefusedException(MerchantClosureRefusedException.Reason.NOT_CLOSABLE,
                        "the merchant is " + current.status() + " and only an ACTIVE merchant can be closed");
            }
            after = administration.close(unitOfWork, decided.merchantId(), reason).status();
        } else {
            after = merchants.findByIdForUpdate(unitOfWork, decided.merchantId())
                    .orElseThrow(() -> new MerchantStorageException("a closure proposal names a merchant that does not exist"))
                    .status();
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, decided.decision().orElseThrow().decidedAt(),
                    MerchantAuditAction.CLOSURE_REJECTED, TARGET_TYPE, decided.merchantId().value().toString(),
                    Optional.of(reason), AuditOutcome.SUCCEEDED, correlation.correlationId(),
                    Optional.of("proposal=" + decided.id().value() + ", proposedBy=" + decided.proposedBy())));
        }
        if (!proposals.decide(unitOfWork, decided)) {
            throw new MerchantStorageException("the proposal moved under its own FOR UPDATE - an invariant is already broken");
        }
        return Optional.of(new Decided(decided, after));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("a closure act requires a reason of 1 to 1000 characters");
        }
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current().orElseThrow(() -> new IllegalStateException(
                "a closure act runs inside the request's correlation scope"));
    }
}
