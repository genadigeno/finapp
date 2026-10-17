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
 * A merchant offers BNPL only after two people say so (`P12-TSK-006`; OQ-8, owner accepted default under B8:
 * a four-eyes operator act; {@code INV-AUD-04}). An operator proposes; a <em>different</em> operator approves; anyone,
 * the proposer included, may reject a proposal; and any one operator may withdraw the enablement in force - stopping BNPL
 * reduces risk, so it is one person's act, as a merchant's suspension is ({@code MerchantAdministration}).
 *
 * <p><strong>Ten proposers, one proposal</strong>: `V010`'s one-live index (proposed or active, per merchant) refuses the
 * other nine behind a savepoint ({@link Pending}). <strong>An approval and a rejection racing leave exactly one</strong>:
 * every decision takes the row {@code FOR UPDATE}, judges the state and the person under it, and writes conditionally;
 * the loser finds the winner's committed decision ({@link NotOpen}). The same person's retry of their own decision
 * converges. The approver is judged distinct from the proposer three times, each rank blind in a different direction:
 * here, in the approval statement's own {@code proposed_by <> ?}, and by `V010`'s {@code CHECK}. Every instant on the row
 * is the database's, written after the lock (`V010`'s trigger).
 *
 * <p><strong>A refused self-approval is evidence</strong>: {@link #approve} throws {@link SelfApprovalRefused}, the act
 * rolls back whole (its idempotency claim with it), and the caller records the attempt {@code FAILED} through
 * {@link #recordRefusal} in a transaction of its own - the Phase 11 gate's R4, the bnpl and lending precedent.
 *
 * <p>What an enablement grants is read by {@link BnplPricing#standing}, under the merchant row's and the enablement row's
 * {@code FOR SHARE}; this class decides nothing about pricing.
 */
@RequiredArgsConstructor
public final class BnplEnablements {

    /** The audit record's target type: the aggregate's own name (the payout destination precedent). */
    public static final String TARGET_TYPE = "merchant.BnplEnablement";

    @NonNull private final BnplEnablementStore<Connection> store;
    @NonNull private final MerchantStore<Connection> merchants;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A decision's outcome: the enablement as it now stands, and whether the same person's retry converged. */
    public record Decided(BnplEnablement enablement, boolean replayed) {}

    // ------------------------------------------------------------------ refusals

    /** The merchant already has a live enablement ({@code 409 merchant.BnplEnablementPending}). */
    public static final class Pending extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Pending() {
            super("a BNPL enablement is already proposed or active for this merchant");
        }
    }

    /** The enablement has moved on from the state the decision needs ({@code 409 merchant.BnplEnablementNotOpen}). */
    public static final class NotOpen extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotOpen(BnplEnablementStatus status) {
            super("the BNPL enablement is not open to this decision: it is " + status.name());
        }
    }

    /** No enablement under the merchant answers to the identifier - unknown and another merchant's alike. */
    public static final class Unknown extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Unknown() {
            super("no BNPL enablement answers to the identifier");
        }
    }

    /** The proposer tried to approve their own enablement ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(BnplEnablement enablement) {
            super("a BNPL enablement is approved by someone other than its proposer; the proposer may reject it");
            this.target = enablement.id().value().toString();
            this.summary = "enablement=" + enablement.id() + ", merchant=" + enablement.merchantId()
                    + ", act=bnpl-enablement:approve";
        }

        /** The enablement the approver proposed - the refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names: the enablement, its merchant and the act. */
        public String summary() {
            return summary;
        }
    }

    // ------------------------------------------------------------------ acts

    /**
     * Proposes enabling BNPL at {@code merchantId} - {@code PROPOSED}, nothing offered.
     *
     * @throws UnknownMerchantException no merchant answers to the identifier
     * @throws MerchantClosedException the merchant is closed - a relationship that ended takes no new business
     * @throws Pending the merchant already has a live enablement
     * @throws IllegalArgumentException for a missing, blank or unbounded reason
     */
    public BnplEnablement propose(
            Connection unitOfWork, MerchantId merchantId, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        String reasoned = BnplEnablement.reasoned(reason);
        Merchant merchant = merchants.findById(unitOfWork, merchantId).orElseThrow(UnknownMerchantException::new);
        if (merchant.status() == MerchantStatus.CLOSED) {
            throw new MerchantClosedException();
        }
        BnplEnablement proposed;
        try {
            proposed = store.insertProposal(unitOfWork, BnplEnablementId.next(ids), merchantId, actor.id(), reasoned);
        } catch (BnplEnablementStore.LivePresent live) {
            throw new Pending();
        }
        record(unitOfWork, actor, MerchantAuditAction.BNPL_ENABLEMENT_PROPOSED, proposed, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, "enablement=" + proposed.id() + ", merchant=" + merchantId);
        return proposed;
    }

    /**
     * Approves a proposal as a second person: {@code PROPOSED -> ACTIVE}. Lock-then-look: the row {@code FOR UPDATE},
     * then the state and the person judged, then the conditional write.
     *
     * @throws Unknown no such enablement under the merchant
     * @throws NotOpen it is no longer {@code PROPOSED} (decided by somebody else, or differently)
     * @throws SelfApprovalRefused the approver proposed it - nothing written; the caller records the attempt
     * @throws MerchantClosedException the merchant closed since the proposal
     */
    public Decided approve(Connection unitOfWork, MerchantId merchantId, BnplEnablementId id, String reason,
            Actor actor, CorrelationId correlation) {
        String reasoned = BnplEnablement.reasoned(reason);
        BnplEnablement before = locked(unitOfWork, merchantId, id, actor, correlation);
        if (before.status() == BnplEnablementStatus.ACTIVE
                && before.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(before, true);
        }
        if (before.status() != BnplEnablementStatus.PROPOSED) {
            throw new NotOpen(before.status());
        }
        if (before.proposedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(before);
        }
        Merchant merchant = merchants.findById(unitOfWork, merchantId).orElseThrow(UnknownMerchantException::new);
        if (merchant.status() == MerchantStatus.CLOSED) {
            throw new MerchantClosedException();
        }
        BnplEnablement approved = store.decide(unitOfWork, id, BnplEnablementStatus.ACTIVE, actor.id(), reasoned)
                .orElseThrow(() -> new MerchantStorageException(
                        "a locked BNPL enablement's approval found another writer's state"));
        record(unitOfWork, actor, MerchantAuditAction.BNPL_ENABLEMENT_APPROVED, approved, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, "enablement=" + id + ", merchant=" + merchantId);
        return new Decided(approved, false);
    }

    /**
     * Rejects a proposal - any holder, the proposer included (a proposer's change of mind is a rejection):
     * {@code PROPOSED -> REJECTED}, terminal. The same person's retry converges.
     *
     * @throws Unknown no such enablement under the merchant
     * @throws NotOpen it is no longer {@code PROPOSED}
     */
    public Decided reject(Connection unitOfWork, MerchantId merchantId, BnplEnablementId id, String reason,
            Actor actor, CorrelationId correlation) {
        String reasoned = BnplEnablement.reasoned(reason);
        BnplEnablement before = locked(unitOfWork, merchantId, id, actor, correlation);
        if (before.status() == BnplEnablementStatus.REJECTED
                && before.decidedBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(before, true);
        }
        if (before.status() != BnplEnablementStatus.PROPOSED) {
            throw new NotOpen(before.status());
        }
        BnplEnablement rejected = store.decide(unitOfWork, id, BnplEnablementStatus.REJECTED, actor.id(), reasoned)
                .orElseThrow(() -> new MerchantStorageException(
                        "a locked BNPL enablement's rejection found another writer's state"));
        record(unitOfWork, actor, MerchantAuditAction.BNPL_ENABLEMENT_REJECTED, rejected, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, "enablement=" + id + ", merchant=" + merchantId + ", byProposer="
                        + before.proposedBy().equals(actor.id()));
        return new Decided(rejected, false);
    }

    /**
     * Withdraws the enablement in force - one operator's risk-reducing act: {@code ACTIVE -> WITHDRAWN}, terminal. It
     * waits for every purchase holding the row {@code FOR SHARE} as its standing read, so none is admitted after it
     * commits; purchases already financed are untouched. The same person's retry converges.
     *
     * @throws Unknown no such enablement under the merchant
     * @throws NotOpen it is not {@code ACTIVE}
     */
    public Decided withdraw(Connection unitOfWork, MerchantId merchantId, BnplEnablementId id, String reason,
            Actor actor, CorrelationId correlation) {
        String reasoned = BnplEnablement.reasoned(reason);
        BnplEnablement before = locked(unitOfWork, merchantId, id, actor, correlation);
        if (before.status() == BnplEnablementStatus.WITHDRAWN
                && before.withdrawnBy().filter(actor.id()::equals).isPresent()) {
            return new Decided(before, true);
        }
        if (before.status() != BnplEnablementStatus.ACTIVE) {
            throw new NotOpen(before.status());
        }
        BnplEnablement withdrawn = store.withdraw(unitOfWork, id, actor.id(), reasoned)
                .orElseThrow(() -> new MerchantStorageException(
                        "a locked BNPL enablement's withdrawal found another writer's state"));
        record(unitOfWork, actor, MerchantAuditAction.BNPL_ENABLEMENT_WITHDRAWN, withdrawn, Optional.of(reasoned),
                AuditOutcome.SUCCEEDED, correlation, "enablement=" + id + ", merchant=" + merchantId);
        return new Decided(withdrawn, false);
    }

    /**
     * Records a refused self-approval {@code FAILED} on {@code unitOfWork} - the caller's own transaction, after the act
     * rolled back, so the attempt is kept (the Phase 11 gate's R4). No reason, no amount ({@code INV-AUD-02}).
     */
    public void recordRefusal(
            Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(refused, "refused must not be null");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                MerchantAuditAction.BNPL_ENABLEMENT_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(),
                AuditOutcome.FAILED, correlation,
                Optional.of(refused.summary() + " refused - the approver proposed it")));
    }

    // ------------------------------------------------------------------ reads

    /** The enablement under its merchant, or empty. */
    public Optional<BnplEnablement> find(Connection unitOfWork, MerchantId merchantId, BnplEnablementId id) {
        return store.find(unitOfWork, merchantId, id);
    }

    /** The merchant's live enablement - proposed or active - or empty. */
    public Optional<BnplEnablement> live(Connection unitOfWork, MerchantId merchantId) {
        return store.live(unitOfWork, merchantId);
    }

    // ------------------------------------------------------------------ plumbing

    private BnplEnablement locked(Connection unitOfWork, MerchantId merchantId, BnplEnablementId id, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        return store.lock(unitOfWork, merchantId, id).orElseThrow(Unknown::new);
    }

    private void record(Connection unitOfWork, Actor actor, MerchantAuditAction action, BnplEnablement subject,
            Optional<String> reason, AuditOutcome outcome, CorrelationId correlation, String summary) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                subject.id().value().toString(),
                // The operator's own words, verbatim (INV-AUD-03).
                reason, outcome, correlation, Optional.of(summary)));
    }
}
