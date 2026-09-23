package com.finapp.checkout;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A merchant's short-lived <em>offer</em> to a customer to pay (`P6-TSK-006`, ADR-0053).
 *
 * <h2>An offer, not an order</h2>
 *
 * <p>The tempting collapse ADR-0053 names first: a session that expires unpaid produces
 * <strong>no {@link Order} at all</strong>. An "order" that can expire is not a fact, and the
 * platform does not manufacture commercial facts out of silence. The order exists only when
 * money has landed, and it exists forever after.
 *
 * <h2>Expiry: a state the sweeper earns, and a clock this aggregate checks anyway</h2>
 *
 * <p>{@code EXPIRED} is produced by a sweeper (`P6-TSK-008`), never by a filter pretending to be
 * a state — that is what makes it countable, audited and present in history (ADR-0053 §4). But a
 * sweeper runs on its own cadence, so between the deadline and the sweep a row still reads
 * {@code OPEN}. {@link #confirm} therefore refuses once {@link #expiresAt} has passed, whatever
 * the row says.
 *
 * <p>These are not two answers to one question. The <em>state</em> is what makes expiry a fact;
 * the <em>clock</em> is what makes the refusal timely. Without the state, expiry is invisible;
 * without the check, a sweeper one minute behind is a minute in which money lands on a dead
 * offer.
 *
 * <h2>Landed money always wins</h2>
 *
 * <p>{@link #completeLate} is the {@code EXPIRED → COMPLETED_LATE} edge ({@code INV-MER-06}): a
 * capture that arrives after the clock ran out still produces the order and still credits the
 * merchant. Auto-reversing it would mean starting a <em>second</em> money movement, with its own
 * failure modes, to undo a first one that succeeded — turning a timing artefact into financial
 * risk. A merchant who does not want the late order refunds it through the human-decided path.
 *
 * <h2>The price is pinned here</h2>
 *
 * <p>{@link #feeScheduleVersionRef} is fixed when the offer is made, because that is when the
 * customer sees the amount and the merchant is quoted its terms. {@code P6-TSK-007} copies it
 * onto the payment's own pin, and the capture prices under it ({@code INV-MER-03},
 * {@code INV-HIST-04}) — so a schedule version created while a customer is at the payment page
 * reprices nothing.
 *
 * <p>Immutable; every transition returns a new instance.
 */
public final class CheckoutSession {

    /** Generated into `V002`'s {@code CHECK}; one definition. */
    public static final int MAX_LINE_SUMMARY_LENGTH = 1000;

    private final CheckoutSessionId id;
    private final UUID merchantRef;
    private final Money amount;
    private final String lineSummary;
    private final UUID feeScheduleVersionRef;
    private final Sensitive<String> tokenHash;
    private final String algorithm;
    private final Optional<UUID> paymentIntentRef;
    private final CheckoutSessionStatus status;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final Instant statusChangedAt;

    private CheckoutSession(
            CheckoutSessionId id,
            UUID merchantRef,
            Money amount,
            String lineSummary,
            UUID feeScheduleVersionRef,
            Sensitive<String> tokenHash,
            String algorithm,
            Optional<UUID> paymentIntentRef,
            CheckoutSessionStatus status,
            Instant expiresAt,
            Instant createdAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantRef = Objects.requireNonNull(merchantRef, "merchantRef must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            // A zero-amount checkout asserts nothing and a negative one is a refund wearing a
            // purchase's clothes.
            throw new IllegalArgumentException("a checkout session's amount must be positive");
        }
        this.lineSummary = boundedSummary(lineSummary);
        this.feeScheduleVersionRef =
                Objects.requireNonNull(
                        feeScheduleVersionRef, "feeScheduleVersionRef must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        this.paymentIntentRef =
                Objects.requireNonNull(paymentIntentRef, "paymentIntentRef must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            // An offer that expires before it exists is not an offer.
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
        if (statusChangedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("statusChangedAt must not precede createdAt");
        }
    }

    /**
     * A new offer, {@code OPEN}. The only door — no edge targets {@code OPEN}, at the aggregate
     * as at the schema, so birth is the one way in (the {@code PaymentIntent} shape).
     */
    public static CheckoutSession open(
            IdGenerator ids,
            Clock clock,
            UUID merchantRef,
            Money amount,
            String lineSummary,
            UUID feeScheduleVersionRef,
            CheckoutSessionToken token,
            Instant expiresAt) {
        Objects.requireNonNull(token, "token must not be null");
        Instant now = Instant.now(clock);
        return new CheckoutSession(
                CheckoutSessionId.next(ids),
                merchantRef,
                amount,
                lineSummary,
                feeScheduleVersionRef,
                token.hash(),
                CheckoutSessionToken.ALGORITHM,
                Optional.empty(),
                CheckoutSessionStatus.OPEN,
                expiresAt,
                now,
                now);
    }

    /** Reconstitutes from storage. Applies the coherence; a corrupt row is refused at read. */
    public static CheckoutSession rehydrate(
            CheckoutSessionId id,
            UUID merchantRef,
            Money amount,
            String lineSummary,
            UUID feeScheduleVersionRef,
            Sensitive<String> tokenHash,
            String algorithm,
            Optional<UUID> paymentIntentRef,
            CheckoutSessionStatus status,
            Instant expiresAt,
            Instant createdAt,
            Instant statusChangedAt) {
        return new CheckoutSession(
                id,
                merchantRef,
                amount,
                lineSummary,
                feeScheduleVersionRef,
                tokenHash,
                algorithm,
                paymentIntentRef,
                status,
                expiresAt,
                createdAt,
                statusChangedAt);
    }

    // ----------------------------------------------------------------- the machine

    /**
     * The customer committed: {@code OPEN → PAYMENT_PENDING}, attaching the payment intent this
     * session opened.
     *
     * <p>Refuses on <strong>two</strong> grounds, and they are different questions: the machine
     * refuses a session that has already left {@code OPEN}, and the clock refuses one whose
     * deadline has passed while the sweeper has not yet arrived. See the class javadoc.
     *
     * @throws CheckoutSessionExpiredException if the deadline has passed
     * @throws IllegalCheckoutSessionTransitionException if the session has left {@code OPEN}
     */
    public CheckoutSession confirm(Clock clock, UUID intentRef) {
        Objects.requireNonNull(intentRef, "intentRef must not be null");
        if (hasExpired(clock)) {
            throw new CheckoutSessionExpiredException();
        }
        CheckoutSession moved = transitioned(CheckoutSessionStatus.PAYMENT_PENDING, clock);
        return moved.withIntent(intentRef);
    }

    /** The capture landed in time: {@code PAYMENT_PENDING → COMPLETED}. */
    public CheckoutSession complete(Clock clock) {
        return transitioned(CheckoutSessionStatus.COMPLETED, clock);
    }

    /**
     * The capture landed after the clock ran out: {@code EXPIRED → COMPLETED_LATE}
     * ({@code INV-MER-06}). A separate state from {@link #complete} so the honest condition is
     * countable rather than laundered into the ordinary one.
     */
    public CheckoutSession completeLate(Clock clock) {
        return transitioned(CheckoutSessionStatus.COMPLETED_LATE, clock);
    }

    /** The sweeper's act: {@code OPEN | PAYMENT_PENDING → EXPIRED}. */
    public CheckoutSession expire(Clock clock) {
        return transitioned(CheckoutSessionStatus.EXPIRED, clock);
    }

    /** An offer nobody acted on, cancelled: {@code OPEN → ABANDONED}. */
    public CheckoutSession abandon(Clock clock) {
        return transitioned(CheckoutSessionStatus.ABANDONED, clock);
    }

    private CheckoutSession transitioned(CheckoutSessionStatus to, Clock clock) {
        if (!status.canTransitionTo(to)) {
            throw new IllegalCheckoutSessionTransitionException(status, to);
        }
        return new CheckoutSession(
                id,
                merchantRef,
                amount,
                lineSummary,
                feeScheduleVersionRef,
                tokenHash,
                algorithm,
                paymentIntentRef,
                to,
                expiresAt,
                createdAt,
                Instant.now(clock));
    }

    /**
     * Attaches the payment intent, once.
     *
     * <p>Set-once at the aggregate and again in `V002`'s trigger: one payment intent per session
     * (ADR-0053 §3). A retry after a failed payment re-uses this intent rather than opening a
     * second — exactly as ADR-0045 §4 allows for attempts one level down — so a second value
     * here would mean the session had quietly started a second payment.
     */
    private CheckoutSession withIntent(UUID intentRef) {
        if (paymentIntentRef.isPresent()) {
            throw new IllegalStateException(
                    "a checkout session opens one payment intent and keeps it");
        }
        return new CheckoutSession(
                id,
                merchantRef,
                amount,
                lineSummary,
                feeScheduleVersionRef,
                tokenHash,
                algorithm,
                Optional.of(intentRef),
                status,
                expiresAt,
                createdAt,
                statusChangedAt);
    }

    // ----------------------------------------------------------------- inspection

    /**
     * Whether the deadline has passed — a question about the clock, not about the row.
     *
     * <p>A session can be expired by this measure and still read {@code OPEN}: the state is the
     * sweeper's to produce. Both answers are true at once and neither is the other's substitute.
     */
    public boolean hasExpired(Clock clock) {
        return !Instant.now(clock).isBefore(expiresAt);
    }

    /** Whether this session may still start something — the machine's half of the gate. */
    public boolean acceptsNewWork() {
        return status.acceptsNewWork();
    }

    /** True when {@code presented} is this session's token. Constant-time, in the token. */
    public boolean authenticates(CheckoutSessionToken presented) {
        Objects.requireNonNull(presented, "presented must not be null");
        return presented.matches(tokenHash.expose());
    }

    public CheckoutSessionId id() {
        return id;
    }

    public UUID merchantRef() {
        return merchantRef;
    }

    public Money amount() {
        return amount;
    }

    public String lineSummary() {
        return lineSummary;
    }

    /** The fee schedule version this offer was priced under ({@code INV-HIST-04}). */
    public UUID feeScheduleVersionRef() {
        return feeScheduleVersionRef;
    }

    /** What the database stores — wrapped, and unwrapped only by the store writing it. */
    public Sensitive<String> tokenHash() {
        return tokenHash;
    }

    /**
     * What produced the stored hash ({@code INV-IDN-02}).
     *
     * <p>Named {@code algorithm} rather than {@code tokenAlgorithm}, and the build rule is
     * why: {@code NoUnwrappedSecretRulesTest} flags any member whose NAME says it holds a
     * secret unless it is wrapped, and "tokenAlgorithm" reads like one while holding the
     * string {@code SHA-256}. Wrapping a public constant would have been the wrong answer;
     * calling it what {@code merchant_api_key.algorithm} already calls it is the right one.
     */
    public String algorithm() {
        return algorithm;
    }

    public Optional<UUID> paymentIntentRef() {
        return paymentIntentRef;
    }

    public CheckoutSessionStatus status() {
        return status;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    private static String boundedSummary(String lineSummary) {
        Objects.requireNonNull(lineSummary, "lineSummary must not be null");
        String trimmed = lineSummary.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LINE_SUMMARY_LENGTH) {
            throw new IllegalArgumentException(
                    "a line summary is 1 to " + MAX_LINE_SUMMARY_LENGTH + " characters");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CheckoutSession other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * States and the identifier only — never the token hash, never the line summary, never the
     * amount ({@code INV-AUD-02}). A session's contents describe what one person is buying.
     */
    @Override
    public String toString() {
        return "CheckoutSession[" + id + ", " + status + "]";
    }
}
