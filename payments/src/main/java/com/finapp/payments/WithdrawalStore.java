package com.finapp.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Withdrawal} (`P7-TSK-008`) — the {@code MerchantPayoutStore}
 * protocols, clause for clause, because the machine is that machine.
 *
 * <p>Every surface read carries the customer in its statement (ADR-0031): another customer's
 * withdrawal and an unknown one are the same absence. The resolvers' lock-first read is
 * internal and unscoped by design — the sweep crosses customers, and each candidate is
 * re-judged under its own lock. Every write is conditional on the state its caller judged,
 * so racing resolvers converge.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface WithdrawalStore<T> {

    /**
     * What a birth's insert stamped on the database clock: the send permit (never older than the
     * database's clock, `X-TSK-013` - what the first-send rule later compares the locked row's
     * with) and, for a {@code HELD} birth, the held window (`P13-TSK-009`: {@code held_at} the
     * statement's instant, never before the birth; {@code held_until} that plus the validity).
     */
    record Stored(Instant sendPermit, Optional<Withdrawal.HeldWindow> held) {
        public Stored {
            java.util.Objects.requireNonNull(sendPermit, "sendPermit must not be null");
            java.util.Objects.requireNonNull(held, "held must not be null");
        }
    }

    /** Stores a freshly judged withdrawal - {@code DISPATCHED}, {@code HELD} or
     * {@code FAILED(RISK_REFUSED)} (`P13-TSK-009`) - with the client key its takeover converges
     * on ({@code (customer_id, dispatch_key)} unique — ADR-0057 §5's shape per customer).
     * Returns what the database stamped. */
    Stored insert(T unitOfWork, Withdrawal born, String dispatchKey);

    /**
     * Stores a freshly dispatched SYSTEM withdrawal on its instructor's recorded instruction
     * (`P11-TSK-015`, payments {@code V032}): the instruction is its dispatch key and its
     * convergence target, one withdrawal per instruction. Returns what the database stamped.
     */
    Stored insertInstructed(T unitOfWork, Withdrawal dispatched, String instructionReference);

    /**
     * The release's edge (`P13-TSK-009`): {@code HELD → DISPATCHED}, conditional on the row still
     * being {@code HELD} AND its {@code held_until} not yet reached on the database's clock, with
     * the door's {@code key} recorded and the first real send permit the database's own instant
     * (strictly after the birth permit), returned as stored - and its history row stamped
     * {@code at}. Empty when the hold's validity has lapsed (the caller locked the row, so a lapse
     * is the only reason in the door's flow): the expiry is then the policy's outcome.
     */
    Optional<Instant> releaseHeld(T unitOfWork, Withdrawal before, String key, Instant at);

    /**
     * The expiry leg's claim (`P13-TSK-009`): ONE {@code HELD} withdrawal whose
     * {@code held_until} is at or before the database's clock, oldest first, locked
     * {@code FOR UPDATE SKIP LOCKED} - a row a door holds is skipped, never waited on, and
     * re-judged on a later tick. {@code passedOver} - the rows whose expiry already failed this tick
     * - are not claimed again, so one row's failure never starves the rest. Empty when none is due.
     */
    Optional<Withdrawal> claimExpiredHold(T unitOfWork, java.util.Set<WithdrawalId> passedOver);

    /**
     * The system withdrawal carrying {@code instructionReference}, unlocked (`P11-TSK-015`):
     * the system entry's takeover target, and the instructor's plain read of its outcome - the
     * instruction scopes the read as the customer scopes the person's.
     */
    Optional<Withdrawal> findByInstruction(T unitOfWork, String instructionReference);

    /**
     * When the withdrawal reached its terminal state - the instant its history recorded for
     * the move into {@code COMPLETED} or {@code FAILED}, stamped from the resolver's clock - or
     * empty while it awaits the scheme's word (`P11-TSK-015`: lending's interest starts there).
     */
    Optional<Instant> concludedAt(T unitOfWork, WithdrawalId id);

    /** The customer's withdrawal, unlocked — the read surface's one query (one 404). */
    Optional<Withdrawal> findOwned(T unitOfWork, WithdrawalId id, UUID customerId);

    /** The withdrawal, locked for the rest of the transaction — the resolvers' read. */
    Optional<Withdrawal> findForUpdate(T unitOfWork, WithdrawalId id);

    /** The withdrawal a client key dispatched for the customer — the takeover's
     * convergence target. */
    Optional<Withdrawal> findByDispatchKey(T unitOfWork, UUID customerId, String dispatchKey);

    /**
     * The withdrawal carrying OUR end-to-end reference, or empty (the Phase 7 -&gt; 8
     * transition): the instant callback door recognises a withdrawal's own confirmation
     * echoed back to it - evidence only, never a parking of value that went out.
     */
    Optional<Withdrawal> findByEndToEndReference(T unitOfWork, EndToEndReference reference);

    /**
     * Commits a fresh send permit, conditional on the withdrawal still awaiting the scheme's
     * word and on its permit being the one {@code before} carries — empty when a resolver or
     * another renewal got there first, and then nothing may be sent. The permit is the
     * database's own instant, strictly forward, returned as stored (`X-TSK-013`).
     */
    Optional<Instant> renewSendPermit(T unitOfWork, Withdrawal before);

    /**
     * Moves the withdrawal {@code before → after}, conditional on {@code before}'s status,
     * with its history row — stamped {@code at}, from the caller's injected clock — in the
     * same transaction. {@code false} when another writer moved it first. Writes the outcome
     * columns {@code after} carries, the failure detail and a door's key included
     * (`P13-TSK-009`: a denial's and an expiry's edge out of {@code HELD}).
     */
    boolean transition(T unitOfWork, Withdrawal before, Withdrawal after, Instant at);

    /**
     * The inquiry sweep's candidates: {@code DISPATCHED} rows whose send permit is at or
     * before {@code dispatchedBefore}, and {@code UNKNOWN} rows that entered that state at
     * or before {@code unknownBefore}, oldest first. Unlocked, across every customer, by
     * design: each candidate is re-judged under its own lock.
     */
    /**
     * The {@code COMPLETED} withdrawals with {@code id > after}, in id order, at most
     * {@code limit} — the opening-position backfill's page (`P8-TSK-007`, ADR-0067 §8;
     * the attempt store's {@code pageByStatus} classification sentence, verbatim).
     */
    List<Withdrawal> pageCompleted(T unitOfWork, UUID after, int limit);

    List<Withdrawal> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * How many withdrawals wait past their due for the scheme's word, and how long the oldest has
     * waited (`P7-TSK-015`) — the stuck-withdrawal gauges' one read, the payout's shape verbatim:
     * {@link #findSweepable}'s candidacy with the {@code UNKNOWN} bound at zero. Every
     * {@code UNKNOWN} withdrawal counts, and every {@code DISPATCHED} one whose send permit is
     * older than the sweep's own {@code dispatchedBound} — before which it is mid-question. Each
     * is a customer's money behind a standing hold. A count and an age, never an amount.
     */
    PaymentAttemptStore.UnknownReading unknownReading(
            T unitOfWork, java.time.Duration dispatchedBound);
}
