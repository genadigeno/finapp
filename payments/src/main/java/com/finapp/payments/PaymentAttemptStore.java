package com.finapp.payments;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link PaymentAttempt} ({@code P5-TSK-009}, over {@code V003}).
 *
 * <p><strong>Every outcome is a conditional transition carrying exactly its payload</strong>
 * ({@code UPDATE … WHERE id AND status = from}, row count the answer): the arbiter for the
 * outcome races ADR-0046 names — a synchronous response, a webhook and a sweeper resolving one
 * operation are the same harmless race, exactly one write landing. {@code false} is
 * convergence, never an error; the caller retains its evidence regardless
 * ({@code INV-HIST-02}).
 */
public interface PaymentAttemptStore<T> {

    void insert(T unitOfWork, PaymentAttempt attempt);

    /**
     * The intent's attempt — one in Phase 5 (ADR-0045 §4); when N arrive with Phase 7's
     * routing, the newest. Empty when nothing was ever dispatched.
     */
    Optional<PaymentAttempt> findForIntent(T unitOfWork, PaymentIntentId intent);

    /** The attempt by its own identifier — the capture command's read ({@code P5-TSK-010}). */
    Optional<PaymentAttempt> findById(T unitOfWork, PaymentAttemptId attempt);

    /**
     * The attempt, locked {@code FOR UPDATE} — the refund bound's lock-then-look
     * (`P5-TSK-015`): the sibling sum {@link RefundStore#sumNonFailedFor} reads is current
     * only under this lock, because two dispatchers summing without it is the `P2-TSK-015`
     * write-skew shape the schema trigger refuses for everyone else. Taken BEFORE the wallet
     * account's lock, always — the attempt→account order every money path shares, so the
     * refund dispatch and the capture outcome cannot deadlock each other.
     */
    Optional<PaymentAttempt> lockById(T unitOfWork, PaymentAttemptId attempt);

    /**
     * The sweeper's candidates (`P5-TSK-014`, ADR-0046 §4): attempts in a resolvable state
     * whose state age has passed its bound — {@code *_DISPATCHED} past {@code dispatchedBefore}
     * (birth for {@code AUTH_DISPATCHED}, the transition row for the capture's), and
     * {@code *_UNKNOWN} past {@code unknownBefore}. Bounded ({@code limit}) and oldest first,
     * the relay's batching posture. Stale answers are harmless: every resolution re-reads and
     * applies conditionally.
     */
    List<PaymentAttempt> findSweepable(
            T unitOfWork, Instant dispatchedBefore, Instant unknownBefore, int limit);

    /**
     * {@code AUTHORIZED} attempts that have rested there since at or before {@code
     * authorizedBefore} — oldest first, at most {@code limit} (the Phase 6 → 7 transition).
     *
     * <p>Every Phase 5 and 6 payment captures what it authorizes, and only the HTTP surface
     * chained the capture: an authorization resolved by the sweeper or by a webhook, or one whose
     * instance crashed between its commit and the chain, rested in {@code AUTHORIZED} with no
     * resolver and no gauge until the customer happened to retry. The sweep now chains these
     * captures ({@link PaymentCapture}, which converges), and the gauge counts them.
     */
    List<PaymentAttempt> findStrandedAuthorizations(
            T unitOfWork, Instant authorizedBefore, int limit);

    /**
     * How many attempts are stuck right now, and how long the oldest has waited (`P5-TSK-017`,
     * {@code INV-LIFE-03}'s operational face): every {@code *_UNKNOWN}, and — since the Phase 6
     * → 7 transition, in the payout's shape (`P6-TSK-013`) — every {@code *_DISPATCHED} and
     * {@code AUTHORIZED} past the sweep's own {@code dispatchedBound}. A dispatch whose instance
     * crashed mid-call, or an authorization nothing captured, is exactly as stuck as an unknown
     * one, and counting only the unknown left both invisible whenever the sweep was down.
     *
     * <p>Age is measured the sweeper's way — the latest transition row, with birth as the
     * fallback — so the gauge and the resolver cannot disagree about what "stuck" means.
     * Counts and seconds only, never an amount ({@code INV-AUD-02}).
     */
    UnknownReading unknownReading(T unitOfWork, java.time.Duration dispatchedBound);

    /** A count of stuck operations and the oldest one's wait in seconds. */
    record UnknownReading(long active, long oldestAgeSeconds) {}

    /**
     * The attempt one of whose minted operation references is {@code reference} — the webhook
     * door's attribution read (`P5-TSK-012`). The identifier presented is one the platform
     * handed the provider before anything was sent ({@code INV-PAY-04}), and the HMAC is
     * verified before this read runs — the {@code SIGNED_CALLBACK} reasoning; empty is the
     * unattributable webhook {@code V005} explicitly admits.
     */
    Optional<PaymentAttempt> findByOperationReference(
            T unitOfWork, ProviderIdempotencyReference reference);

    /**
     * {@code AUTHORIZED → CAPTURE_DISPATCHED}, the capture's idempotency reference minted by
     * this act and stored before anything is sent ({@code INV-PAY-04}, ADR-0046).
     */
    boolean dispatchCapture(
            T unitOfWork, PaymentAttemptId attempt, ProviderIdempotencyReference reference);

    /** {@code from → CAPTURED}, the capture pair arriving with the transition (ADR-0048). */
    boolean capture(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money capturedAmount);

    /** {@code CAPTURE_DISPATCHED → CAPTURE_UNKNOWN} — the second {@code INV-LIFE-03} state. */
    boolean markCaptureUnknown(T unitOfWork, PaymentAttemptId attempt);

    /**
     * {@code from → VOID_DISPATCHED}, the void's idempotency reference arriving with the
     * transition (`P7-TSK-004`, {@code INV-PAY-04}): from {@code AUTHORIZED} (a cancellation,
     * an operator) or a capture stage (the declined-capture redirect).
     */
    boolean dispatchVoid(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderIdempotencyReference reference);

    /** {@code from → VOIDED}, the provider's acknowledgement arriving with the transition. */
    boolean voided(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference);

    /** {@code VOID_DISPATCHED → VOID_UNKNOWN} ({@code INV-LIFE-03}). */
    boolean markVoidUnknown(T unitOfWork, PaymentAttemptId attempt);

    /** {@code from → AUTHORIZED}, the issuer's promise arriving with the transition. */
    boolean authorize(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference providerReference,
            Money authorizedAmount);

    /** {@code from → FAILED} with the mapped reason ({@code INV-PAY-03}). */
    boolean fail(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentFailureReason reason);

    /** {@code AUTH_DISPATCHED → AUTH_UNKNOWN} — ambiguity committed honestly ({@code INV-LIFE-03}). */
    boolean markAuthUnknown(T unitOfWork, PaymentAttemptId attempt);

    /** The append-only history row — actor model per {@code V006}. */
    void recordTransition(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            PaymentAttemptStatus to,
            Actor actor,
            Instant occurredAt);

    // ------------------------------------------------------- the push model (P7-TSK-009)

    /**
     * The push attempt OUR end-to-end reference names — the instant callback's and the
     * inquiry's attribution read, the {@link #findByOperationReference} reasoning on the
     * second vocabulary: the reference was minted and stored before anything was sent
     * ({@code INV-PAY-04}), the signature is verified before this runs, and empty is the
     * unattributable confirmation ADR-0062 §5 parks.
     */
    Optional<PaymentAttempt> findByEndToEndReference(T unitOfWork, EndToEndReference reference);

    /**
     * The push attempt already holding the scheme's transaction reference — the
     * cross-attempt claim pre-check (`P7-TSK-009`, the `V015` acquirer-reference reasoning):
     * one scheme execution credits one attempt, and a confirmation naming a reference some
     * OTHER row already stored is an integration break to record, never a second credit.
     */
    Optional<PaymentAttempt> findBySchemeReference(T unitOfWork, ProviderReference reference);

    /**
     * The initiation handle, stored once ({@code WHERE status = 'AWAITING_PAYER' AND
     * authorization_handle IS NULL}): the row count arbitrates the callback-vs-sweep and
     * sweep-vs-sweep races, and a loser converges — the scheme's dedupe means the handle it
     * held was this one. The one bind-side {@code expose()} of the handle, registered.
     */
    boolean openInitiation(T unitOfWork, PaymentAttemptId attempt, Sensitive<String> handle);

    /**
     * {@code from → EXECUTED}, the scheme's pair arriving with the transition (`P7-TSK-009`,
     * Phase 8's keys) — the push model's completing conditional, whichever resolver carries it.
     */
    boolean execute(
            T unitOfWork,
            PaymentAttemptId attempt,
            PaymentAttemptStatus from,
            ProviderReference schemeReference,
            Optional<String> settlementCycle);

    /**
     * {@code AWAITING_PAYER → FAILED} <strong>only while no handle is stored</strong> — the
     * refused-connection and refused-initiation conclusions' own conditional (ADR-0062 §3
     * adapted): a row that holds a handle has an initiation the payer can still complete,
     * so no unavailability verdict may fail it, whichever instance concluded first.
     */
    boolean failHandleless(T unitOfWork, PaymentAttemptId attempt, PaymentFailureReason reason);

    /**
     * The initiation permit stamped forward, conditionally ({@code last_dispatched_at <=
     * expected}, resolvable only): the sweep's wire-noise arbiter — the loser skips the
     * scheme call this tick. Never a money guard (`P7-TSK-009`; the aggregate door says why).
     */
    boolean renewInitiationPermit(
            T unitOfWork, PaymentAttemptId attempt, Instant expected, Instant renewed);

    /**
     * The pay-in sweep's candidates (`P7-TSK-009`, ADR-0062 §5): push rows resting
     * {@code AWAITING_PAYER} whose last outbound contact is at or before
     * {@code contactedBefore} — oldest first, bounded. The payer PSP's clock decides the
     * outcome; this bound only paces how often we ask.
     */
    /**
     * The attempts standing in {@code status} with {@code id > after}, in id order, at most
     * {@code limit} — the opening-position backfill's page (`P8-TSK-007`, ADR-0067 §8):
     * {@code CAPTURED} and {@code EXECUTED} are the completed clearing operations whose
     * expectations history never opened. Cross-owner by design — an administered read under
     * {@code RECONCILIATION_ADMINISTER}, composed in {@code app} — and lock-free: a
     * completed row's copied facts are frozen, and the backfill's converge is the register's
     * uniques, never a lock here.
     */
    List<PaymentAttempt> pageByStatus(
            T unitOfWork, PaymentAttemptStatus status, java.util.UUID after, int limit);

    List<PaymentAttempt> findResolvableInitiations(
            T unitOfWork, Instant contactedBefore, int limit);

    /**
     * The pay-in ageing gauge's reading ({@code INV-REC-05}'s sibling discipline, the
     * `P7-TSK-002` exclusion honoured with its own gauge): how many initiations await the
     * payer, and the oldest wait in seconds — the server's clock, never an instance's.
     */
    UnknownReading awaitingReading(T unitOfWork);
}
