package com.finapp.checkout;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The producer of {@code EXPIRED} (`P6-TSK-008`, ADR-0053 §4, {@code INV-MER-06}): every
 * instance polls for offers whose deadline has passed and ends them with a conditional
 * transition.
 *
 * <h2>Why a state and not a filter</h2>
 *
 * <p>ADR-0053 §4 refuses {@code WHERE expires_at < now()} pretending to be a state, and this
 * class is what that refusal costs and buys. It costs a poller. It buys an expiry that is
 * <strong>countable</strong> (a meter reads acting transitions), <strong>audited</strong> (a
 * record names the session and the state it came from), <strong>present in history</strong> (an
 * append-only row beside every other transition) and <strong>announced</strong> — none of which
 * a filter can be, because a filter is an opinion each reader forms separately and no two
 * readers form it at the same instant.
 *
 * <p>The clock check inside {@link CheckoutSession#confirm} is <em>not</em> a duplicate of this
 * and does not replace it. That check makes the refusal <strong>timely</strong>; this makes the
 * expiry <strong>a fact</strong>. Between a deadline and the tick that notices it, a row still
 * reads {@code OPEN} — and without the aggregate's check, a sweeper one minute behind is a
 * minute in which money lands on a dead offer.
 *
 * <h2>No lease, no leader, by design</h2>
 *
 * <p>ADR-0024's bar is <em>idempotent per period or an explicit lease</em>; the relay took the
 * lease half and {@code PaymentSweeper} the other. This is the other half again, and more
 * cleanly than either: there is no external call at all. Every write is a conditional
 * transition whose losers converge ({@code INV-IDEM-02}) — N sweepers racing each other, a
 * customer's confirmation and a late capture are the same counted race, and the arbiter is the
 * row count, not anything process-local. The register row is
 * {@code DISTRIBUTED_EXECUTION.md} §3.
 *
 * <h2>Two states expire, and the second one is the point</h2>
 *
 * <p>{@code OPEN} is obvious: nobody paid. {@code PAYMENT_PENDING} is the interesting one, and
 * it is here for two independent reasons.
 *
 * <ul>
 *   <li><strong>It is the only terminal escape for a failed payment.</strong> ADR-0053 gave
 *       checkout no failure state, on purpose — a declined payment is the <em>payment's</em>
 *       state and the customer retries on the same intent. So a customer who is declined and
 *       then closes the tab leaves a {@code PAYMENT_PENDING} row that nothing else can ever
 *       end. Without this edge it is stuck for ever, which is precisely what ADR-0044's
 *       every-state-has-a-producer doctrine exists to prevent.
 *   <li><strong>It is what makes ADR-0053 §5's own scenario reachable.</strong> "The capture
 *       completes after the session expired" requires a session to be {@code EXPIRED} while a
 *       payment is in flight. Only this edge can put it there.
 * </ul>
 *
 * <p>And expiring a session with a payment in flight <strong>cancels nothing</strong>. The
 * payment continues on its own machine; when it succeeds the session takes
 * {@code EXPIRED → COMPLETED_LATE}, the merchant is credited and the order is born. Expiry is
 * about the offer's standing, never the money's — which is the whole of {@code INV-MER-06}.
 *
 * <h2>One bad row must not stall the queue</h2>
 *
 * <p>Each candidate expires in its own transaction, and a failing row is logged (identifier and
 * failure class only, {@code INV-AUD-02}) while the sweep continues — the {@code PaymentSweeper}
 * posture, for the same reason one level over: the rows behind a poisoned one are other
 * merchants' offers.
 */
public final class CheckoutExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(CheckoutExpirySweeper.class);

    static final String EVENT_TYPE = "checkout.CheckoutSessionExpired";
    static final String AGGREGATE_TYPE = "checkout_session";
    static final String PRODUCER = "checkout";
    static final int EVENT_VERSION = 1;

    private final CheckoutTransactionRunner transactions;
    private final CheckoutSessionStore<Connection> sessions;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration paymentGrace;
    private final int batchSize;

    public CheckoutExpirySweeper(
            CheckoutTransactionRunner transactions,
            CheckoutSessionStore<Connection> sessions,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            Duration paymentGrace,
            int batchSize) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(paymentGrace, "paymentGrace must not be null");
        if (paymentGrace.isNegative()) {
            throw new IllegalArgumentException("paymentGrace must not be negative: " + paymentGrace);
        }
        this.paymentGrace = paymentGrace;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * One tick's tally — telemetry, <strong>never the count of record</strong>
     * ({@code SweepResult}'s recorded stance, inherited): {@code candidates} is what the list
     * offered and {@code expired} is how many of this tick's own conditional transitions
     * <em>fired</em>. A racer that lost contributes to {@code skipped}, so N sweepers on one
     * overdue row report one expiry between them however many looked at it.
     *
     * <p>{@code skipped} is a candidate another writer had already moved — expired by a sibling
     * sweeper, confirmed by a customer, or completed by a capture — between the list and the
     * lock.
     */
    public record SweepResult(int candidates, int expired, int skipped, int failedRows) {}

    /**
     * One sweep: bounded candidates, one transaction per row.
     *
     * <p>The whole tick runs <strong>as the platform</strong> — a scheduled expiry has no person
     * at all, the cleanest case of the `P5-TSK-009` attribution reasoning — and each row under
     * its own fresh correlation, because scheduled work carries no request scope.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        List<CheckoutSession> candidates =
                transactions.inTransaction(
                        uow ->
                                sessions.findExpirable(
                                        uow, now, now.minus(paymentGrace), batchSize));

        int expired = 0;
        int skipped = 0;
        int failedRows = 0;
        for (CheckoutSession candidate : candidates) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem()) {
                if (expireOne(candidate.id())) {
                    expired++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException oneRowsFailure) {
                // The anti-stall posture: the rows behind this one are other merchants' offers.
                // Identifier and failure class only - a JDBC message can name hosts and values.
                failedRows++;
                log.warn(
                        "Expiring checkout session {} failed with {}; the sweep continues and"
                                + " the next tick will retry this row",
                        candidate.id(),
                        oneRowsFailure.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), expired, skipped, failedRows);
    }

    /** {@code true} when <strong>this call's own</strong> conditional transition fired. */
    private boolean expireOne(CheckoutSessionId id) {
        Correlation correlation = resolvedCorrelation();
        return transactions.inTransaction(
                uow -> {
                    // LOCK, THEN JUDGE, THEN WRITE CONDITIONALLY - the established idiom. The
                    // lock is what makes the judgement worth making: without it two sweepers
                    // both read OPEN, both build the transition, and the conditional refuses
                    // one of them anyway - correct, but it would have burned an audit record
                    // and an event id on a decision that never landed.
                    Optional<CheckoutSession> found = sessions.findByIdForUpdate(uow, id);
                    if (found.isEmpty()) {
                        return false;
                    }
                    CheckoutSession current = found.get();
                    if (!current.status().canTransitionTo(CheckoutSessionStatus.EXPIRED)) {
                        // Confirmed, completed, abandoned or already expired since the list.
                        return false;
                    }
                    // RE-JUDGED AGAINST THE CLOCK, not trusted from the list: the candidate was
                    // read in an earlier transaction, and a row that is no longer overdue must
                    // not be expired because a query once said it was.
                    if (!isOverdue(current)) {
                        return false;
                    }

                    CheckoutSession expired = current.expire(clock);
                    if (!sessions.transition(uow, current, expired)) {
                        // Another writer moved the row between the lock and the write. It
                        // cannot happen through this store - the lock serializes every writer
                        // that takes it - but the conditional is what says so, rather than a
                        // comment claiming the lock is airtight (INV-CON-01's idiom).
                        return false;
                    }

                    Instant at = Instant.now(clock);
                    Actor platform = SecurityContext.require();
                    audit.append(
                            uow,
                            new AuditRecord(
                                    AuditId.next(ids),
                                    platform,
                                    at,
                                    CheckoutAuditAction.CHECKOUT_SESSION_EXPIRED,
                                    AGGREGATE_TYPE,
                                    current.id().value().toString(),
                                    Optional.empty(),
                                    AuditOutcome.SUCCEEDED,
                                    correlation.correlationId(),
                                    // The state it came from, because OPEN (nobody paid) and
                                    // PAYMENT_PENDING (a payment that never landed) are
                                    // different operational facts.
                                    Optional.of(
                                            "session=" + current.id()
                                                    + ", expiredFrom=" + current.status())));
                    announce(uow, current, at, correlation);
                    return true;
                });
    }

    /**
     * The same judgement the candidate query makes, in Java — <strong>and it must be the same
     * one</strong>, which is why it is a method rather than a repeated expression.
     *
     * <h2>Found by the race test, and it is not a test artefact</h2>
     *
     * <p>The first version re-judged with {@code hasExpired(clock)} alone, which applies the
     * offer's deadline and knows nothing about the grace. That leaves a hole in exactly the
     * interleaving the grace exists for: a session read as {@code OPEN} in the candidate list,
     * <em>confirmed by a customer</em> before the sweeper takes its lock, and then expired as
     * {@code PAYMENT_PENDING} — with a payment in flight, one second old, and the grace never
     * applied to it at all, because the row was never selected <em>as</em> a
     * {@code PAYMENT_PENDING} row.
     *
     * <p>Harmless to the money ({@code EXPIRED → COMPLETED_LATE} still catches the capture) and
     * corrosive to the meaning: {@code COMPLETED_LATE} would count confirmations that raced a
     * tick rather than providers that answered late, and an operator reading that number would
     * be reading noise. The state a row is in <strong>when the decision is made</strong> is the
     * state whose deadline applies.
     */
    private boolean isOverdue(CheckoutSession session) {
        Instant now = Instant.now(clock);
        Instant deadline =
                session.status() == CheckoutSessionStatus.PAYMENT_PENDING
                        ? session.expiresAt().plus(paymentGrace)
                        : session.expiresAt();
        return !now.isBefore(deadline);
    }

    /**
     * Announced by the winner only, so N sweepers announce once — the {@code OrderPaid}
     * discipline, inside the transition's own transaction so the event and the state commit
     * together or neither does.
     *
     * <p>Expiry is announced and abandonment is not, and the asymmetry is the argument: an
     * expiry happens without anybody asking, so a merchant needs to be told their offer died.
     * A merchant that abandons its own session already knows.
     */
    private void announce(
            Connection uow, CheckoutSession expiredFrom, Instant at, Correlation correlation) {
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        expiredFrom.id(),
                        AGGREGATE_TYPE,
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("checkoutId", expiredFrom.id().value().toString())
                        .with("merchantId", expiredFrom.merchantRef().toString())
                        // NOT the line summary and NOT the amount: a consumer of "this offer
                        // died" needs neither, and what somebody nearly bought is as revealing
                        // as what they did (DATA_CLASSIFICATION's reasoning for that column).
                        .with("expiredFrom", expiredFrom.status().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * The tick's correlation, <strong>with its cause resolved</strong> —
     * {@code PaymentCreation.resolvedCorrelation}'s idiom, and the sweeper is the case that
     * needs it most.
     *
     * <p>A scheduled expiry is the ROOT of its own flow: nothing caused it, which is exactly
     * why {@code Correlation.startingWith} leaves the causation empty. But
     * {@code INV-EVT-03} makes all ten envelope fields mandatory, and an event with no
     * causation is one a consumer cannot place in any chain. So a root flow causes itself —
     * the honest answer, and the same one every other root on this platform gives.
     */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an expiry sweep runs inside a correlation"
                                                        + " scope: the audit record and the"
                                                        + " event carry the identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(
                        com.finapp.sharedkernel.correlation.CausationId.of(
                                current.correlationId().value()));
    }
}
