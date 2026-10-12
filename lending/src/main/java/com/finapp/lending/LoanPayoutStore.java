package com.finapp.lending;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for path X's payout (`P11-TSK-015`, {@code lending V009}): the machine row, every edge conditional on the
 * state - and, for a claim's step, the permit - its caller judged; every instant the database's. The owner's read is
 * owner-scoped in its statement.
 */
public interface LoanPayoutStore {

    /** Births the payout {@code PENDING} in the disbursement's transaction; the database stamps its birth and permit. */
    void insert(Connection unitOfWork, UUID id, LoanId loan, UUID paymentMethod, LedgerAccountId wallet, HoldId hold,
            CorrelationId correlation);

    /**
     * Claims at most {@code limit} payouts due a step - {@code PENDING} ones moved to {@code DISPATCHING}, lapsed
     * {@code DISPATCHING} ones re-driven, {@code DISPATCHED} ones due an observation - in ONE statement: each permit
     * re-stamped {@code permit} ahead on the database clock, rows another claimer holds skipped ({@code FOR UPDATE SKIP
     * LOCKED}), oldest permit first. Answers each claim with the permit it stamped - the step's token.
     */
    List<Claimed> claimDue(Connection unitOfWork, int limit, Duration permit);

    /** The loan's payout, unlocked. */
    Optional<LoanPayout> find(Connection unitOfWork, LoanId loan);

    /** The loan's payout {@code FOR UPDATE} (L5). */
    Optional<LoanPayout> lock(Connection unitOfWork, LoanId loan);

    /** {@code DISPATCHING -> DISPATCHED} naming payments' withdrawal, conditional on the claim's permit; whether moved. */
    boolean dispatched(Connection unitOfWork, LoanId loan, Instant permit, UUID withdrawal);

    /**
     * {@code DISPATCHING -> NOT_DISPATCHED} for {@code reason}, conditional on the claim's permit; the conclusion instant
     * the database stamped, or empty when another step moved it first.
     */
    Optional<Instant> notDispatched(Connection unitOfWork, LoanId loan, Instant permit, NotDispatchedReason reason);

    /** Stamps an observation of payments' undecided withdrawal on a {@code DISPATCHED} payout; whether it was. */
    boolean observed(Connection unitOfWork, LoanId loan);

    /**
     * {@code DISPATCHED -> outcome} ({@code PAID_OUT} or {@code FAILED}) with payments' conclusion instant; the instant
     * as the database held it (within the payout's life on its clock), or empty when another step moved it first.
     */
    Optional<Instant> concluded(Connection unitOfWork, LoanId loan, PayoutStatus outcome, Instant paymentsConcludedAt);

    /** {@code party}'s loan's payout status, owner-scoped in the statement; empty for none (path W, a line, not theirs). */
    Optional<PayoutStatus> statusOwnedBy(Connection unitOfWork, LoanId loan, UUID party);

    /**
     * How long the oldest payout not yet concluded - {@code PENDING}, {@code DISPATCHING} or {@code DISPATCHED} - has held the
     * borrower's funds, from its birth, on the database clock; empty when none is open. A payout stuck before payments
     * ever took it ages here as surely as one payments holds {@code UNKNOWN}.
     */
    Optional<Duration> oldestUnconcludedAge(Connection unitOfWork);

    /** One claimed payout: its loan, its state after the claim, and the permit the claim stamped. */
    record Claimed(LoanId loan, PayoutStatus status, Instant permit) {

        public Claimed {
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(permit, "permit must not be null");
        }
    }
}
