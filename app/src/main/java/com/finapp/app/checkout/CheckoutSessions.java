package com.finapp.app.checkout;

import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.checkout.CheckoutAuditAction;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.checkout.Order;
import com.finapp.checkout.OrderStore;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout flow's three commands (`P6-TSK-007`, ADR-0053) — <strong>the phase's first
 * whole flow</strong>, and almost entirely composition.
 *
 * <p>Every money-moving part already existed: Phase 5's payment machinery, {@code P6-TSK-004}'s
 * pricing, {@code P6-TSK-005}'s four-line entry, {@code P6-TSK-006}'s aggregates. What this
 * class adds is the order in which they happen, and the honest answer when one of them refuses.
 *
 * <h2>Why this lives in {@code app}</h2>
 *
 * <p>{@code checkout} cannot see {@code payments}, {@code merchant} or {@code ledger} — the
 * build graph refuses all three (`P6-TSK-001`), so the purchase experience cannot grow into a
 * god-orchestrator inside the module that owns it. The orchestration therefore lives in the
 * composition root, where it is one reviewable file rather than a capability every checkout
 * class inherits.
 *
 * <h2>Three refusals that happen EARLY on purpose</h2>
 *
 * <p>A merchant with no fee schedule, or one that is not trading, is refused <strong>at session
 * creation</strong>. The alternative is discovering it at the capture — inside the transaction
 * that moves money, after the customer has paid — which is the difference between a merchant
 * fixing their configuration and a customer's money needing a refund. The third, the expired
 * session, is refused at confirmation by the aggregate's own clock.
 */
public final class CheckoutSessions {

    static final String IDEMPOTENCY_SCOPE = "checkout.session";
    static final String SESSION_TARGET_TYPE = "checkout_session";
    static final String ORDER_TARGET_TYPE = "checkout_order";
    static final String ORDER_PAID_EVENT_TYPE = "checkout.OrderPaid";
    static final String PRODUCER = "checkout";
    static final int EVENT_VERSION = 1;

    /** How long an offer stands. A merchant-configurable window is a later decision. */
    static final Duration OFFER_WINDOW = Duration.ofMinutes(30);

    /** The merchant's new offer, parsed and bounded by the surface before this class runs. */
    public record OpenSessionCommand(
            String idempotencyKey, MerchantId merchant, Money amount, String lineSummary) {}

    /**
     * What creation answers.
     *
     * @param token the freshly minted token, <strong>wrapped</strong> and present exactly once
     *     — empty on a replay, because the claim records the session id alone
     */
    public record OpenedSession(
            CheckoutSessionId id, Optional<Sensitive<String>> token, boolean replayed) {}

    private final CheckoutSessionStore<Connection> sessions;
    private final OrderStore<Connection> orders;
    private final MerchantStore<Connection> merchants;
    private final FeeSchedules feeSchedules;
    private final IdempotentExecutor executor;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom randomness;
    private final CheckoutMeters meters;

    public CheckoutSessions(
            CheckoutSessionStore<Connection> sessions,
            OrderStore<Connection> orders,
            MerchantStore<Connection> merchants,
            FeeSchedules feeSchedules,
            IdempotentExecutor executor,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock,
            SecureRandom randomness,
            CheckoutMeters meters) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
        this.feeSchedules = Objects.requireNonNull(feeSchedules, "feeSchedules must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    // ----------------------------------------------------------------- create

    /**
     * Opens an offer, at most once for its key.
     *
     * <h2>The claim records the SESSION ID ALONE, and that is the second time</h2>
     *
     * <p>Every other keyed command on this platform records its response so a replay renders
     * the original bytes. This one cannot: the response carries the <strong>session
     * token</strong>, and recording it would persist a live bearer credential in
     * {@code platform.idempotency_record.response_body}, recoverable for the claim's whole
     * retention — exactly what {@code INV-IDN-01} forbids and what `V002`'s own comment claims
     * is impossible.
     *
     * <p>{@code P6-TSK-002} met this question with the merchant API key and answered it the
     * same way. So a replay converges on the same session and renders <strong>no token</strong>:
     * show-once means once, and the byte-for-byte replay discipline yields to the stronger
     * invariant rather than being quietly bent.
     *
     * @throws MerchantNotTradingException the merchant is not {@code ACTIVE}
     * @throws MerchantNotPriceableException the merchant has no effective fee schedule version
     */
    public OpenedSession open(Connection unitOfWork, OpenSessionCommand command) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // The authoritative reads, BEFORE the claim: every refusal here writes nothing and
        // consumes no key (PaymentCreation's recorded order).
        if (merchants
                .findById(unitOfWork, command.merchant())
                .filter(merchant -> merchant.status() == MerchantStatus.ACTIVE)
                .isEmpty()) {
            throw new MerchantNotTradingException();
        }
        FeeScheduleVersion pricing =
                feeSchedules
                        .effectiveVersionFor(
                                unitOfWork, command.merchant(), Instant.now(clock))
                        .orElseThrow(MerchantNotPriceableException::new);

        // The token is minted OUTSIDE the claim body, so the value is reachable here to be
        // returned once. What goes INTO the claim is the session id.
        CheckoutSessionToken token = CheckoutSessionToken.issue(randomness);

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, command.idempotencyKey());
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (command.merchant().value()
                                + "|" + command.amount().minorUnits()
                                + "|" + command.amount().currency().code()
                                + "|" + command.lineSummary()
                                + "|" + actor.id())
                                .getBytes(StandardCharsets.UTF_8));

        IdempotentExecutor.ExecutionOutcome outcome =
                executor.execute(
                        unitOfWork,
                        key,
                        fingerprint,
                        uow ->
                                accept(
                                        uow,
                                        command,
                                        pricing,
                                        token,
                                        actor,
                                        correlation));

        CheckoutSessionId id =
                CheckoutSessionId.of(
                        UUID.fromString(
                                new String(
                                        outcome.body()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "a recorded checkout"
                                                                            + " creation always"
                                                                            + " carries its"
                                                                            + " session id")),
                                        StandardCharsets.UTF_8)));
        return new OpenedSession(
                id,
                // SHOWN ONCE. A replay renders no token - the credential exists for the length
                // of one response and is then unreachable from every path on the platform.
                outcome.replayed() ? Optional.empty() : Optional.of(token.presentedOnce()),
                outcome.replayed());
    }

    private CommandResult accept(
            Connection unitOfWork,
            OpenSessionCommand command,
            FeeScheduleVersion pricing,
            CheckoutSessionToken token,
            Actor actor,
            Correlation correlation) {
        CheckoutSession session =
                CheckoutSession.open(
                        ids,
                        clock,
                        command.merchant().value(),
                        command.amount(),
                        command.lineSummary(),
                        pricing.id().value(),
                        token,
                        Instant.now(clock).plus(OFFER_WINDOW));
        sessions.insert(unitOfWork, session);

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        CheckoutAuditAction.CHECKOUT_SESSION_CREATED,
                        SESSION_TARGET_TYPE,
                        session.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only: never the token, never the line summary, never the
                        // amount (INV-AUD-02).
                        Optional.of(
                                "session=" + session.id() + ", merchant=" + command.merchant()
                                        + ", pricing=" + pricing.id())));

        // THE SESSION ID, NOT THE RESPONSE. See the method javadoc.
        return CommandResult.succeeded(
                StoredResponse.of(
                        session.id().value().toString().getBytes(StandardCharsets.UTF_8),
                        "text/plain"));
    }

    // ----------------------------------------------------------------- confirm

    /**
     * The session a token opens.
     *
     * <p>Resolved <strong>by hash</strong>, so the stored value never travels back out. The
     * gating - state, clock, convergence - is the caller's, because those three answers map to
     * three different things to tell a customer looking at a page.
     *
     * @throws UnknownCheckoutSessionException unknown, malformed and another's, one answer
     */
    public CheckoutSession byToken(Connection unitOfWork, CheckoutSessionToken presented) {
        return sessions
                .findByToken(unitOfWork, presented)
                .orElseThrow(UnknownCheckoutSessionException::new);
    }

    /**
     * Records that this session's payment has been opened: the fee pinned, the session moved
     * to {@code PAYMENT_PENDING} with its one intent reference, the audit record — one
     * transaction with the intent's own creation.
     *
     * @return {@code false} when another writer moved the session first (an expiry sweeper, or
     *     a concurrent confirmation); the caller converges rather than assumes
     */
    public boolean paymentOpened(
            Connection unitOfWork, CheckoutSession session, UUID intentRef) {
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        CheckoutSession pending = session.confirm(clock, intentRef);
        if (!sessions.transition(unitOfWork, session, pending)) {
            return false;
        }
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        CheckoutAuditAction.CHECKOUT_SESSION_CONFIRMED,
                        SESSION_TARGET_TYPE,
                        session.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("session=" + session.id() + ", intent=" + intentRef)));
        return true;
    }

    // ----------------------------------------------------------------- complete

    /**
     * The capture landed: the session completes and its order exists — <strong>in the
     * capture's own transaction</strong> (`P6-TSK-007`, called by the composition seam).
     *
     * <p>Converges silently on a session already completed: the conditional transition admits
     * one writer and {@code UNIQUE (session_ref)} is the second rank behind it, so ten
     * instances applying one outcome produce one order.
     *
     * <p><strong>An {@code EXPIRED} session completes LATE rather than not at all</strong>
     * ({@code INV-MER-06}, ADR-0053 §5): money that landed is never orphaned by a clock. The
     * edge is driven for real by {@code P6-TSK-008}; it is taken here because the capture that
     * finds an expired row is the one that needs it.
     *
     * @return the order, when this call created it
     */
    public Optional<Order> completed(
            Connection unitOfWork, UUID intentRef, UUID capturedEntryRef, Correlation correlation) {
        Optional<CheckoutSession> found = sessions.findByIntentForUpdate(unitOfWork, intentRef);
        if (found.isEmpty()) {
            // Not a checkout payment at all - a wallet top-up, or a merchant-bound payment
            // created by something other than a session. Nothing to complete.
            return Optional.empty();
        }
        CheckoutSession session = found.get();
        Optional<CheckoutSession> moved = completionOf(session);
        if (moved.isEmpty() || !sessions.transition(unitOfWork, session, moved.get())) {
            // Already completed, or another writer won. One order either way.
            return Optional.empty();
        }

        Order order = Order.paid(ids, clock, session, capturedEntryRef);
        orders.insert(unitOfWork, order);

        Actor actor = SecurityContext.require();
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        CheckoutAuditAction.ORDER_CREATED,
                        ORDER_TARGET_TYPE,
                        order.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // The chain's last link, in the trail: order -> entry -> the four lines.
                        Optional.of(
                                "order=" + order.id() + ", session=" + session.id()
                                        + ", entry=" + capturedEntryRef
                                        + ", completedAs=" + moved.get().status())));
        announceOrder(unitOfWork, order, session, correlation);
        // ON THE ACTING TRANSITION ONLY: the conditional above already returned for every
        // racer that lost, so ten instances applying one capture outcome count ONE ending.
        // Inside the transaction rather than after it, and CheckoutMeters says why.
        meters.session(
                moved.get().status() == CheckoutSessionStatus.COMPLETED_LATE
                        ? CheckoutMeters.Outcome.COMPLETED_LATE
                        : CheckoutMeters.Outcome.COMPLETED);
        return Optional.of(order);
    }

    // ----------------------------------------------------------------- abandon

    /**
     * A merchant withdraws its own offer: {@code OPEN → ABANDONED} (`P6-TSK-008`).
     *
     * <h2>What it cannot reach, and why that is the design rather than a gap</h2>
     *
     * <p>There is no {@code PAYMENT_PENDING → ABANDONED} edge in the machine. A session whose
     * payment is in flight has money moving toward it, and withdrawing the offer would leave
     * that money with no commercial home — the state {@code INV-MER-06} exists to prevent. The
     * aggregate refuses it, the trigger refuses it, and the surface turns the refusal into
     * {@code checkout.NotAbandonable}. A merchant who needs the money back refunds it, through
     * the existing human-decided path, once the order exists.
     *
     * <h2>Tenant first, then lock</h2>
     *
     * <p>The tenant-scoped read decides the {@code 404} with the predicate <em>in the
     * statement</em> ({@code INV-MER-01}); the locking read by identifier then serializes the
     * transition. Two reads rather than one, and the order is safe because {@code merchant_ref}
     * is frozen for every writer by `V002`'s trigger — the tenant a row belongs to cannot
     * change between them.
     *
     * <p><strong>Converges on a session already abandoned</strong> and writes nothing, the
     * {@code MerchantAdministration} shape: a retry that finds the state it wanted is not an
     * error. Any other terminal or pending state is the caller's refusal.
     *
     * @return {@code true} when this call's own conditional transition fired
     * @throws UnknownCheckoutSessionException unknown, malformed or another merchant's — one
     *     answer, so the surface never becomes an oracle over a competitor's offers
     * @throws IllegalCheckoutSessionTransitionException the session cannot be withdrawn
     */
    public boolean abandon(
            Connection unitOfWork, MerchantId merchant, CheckoutSessionId id, String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        Correlation correlation = resolvedCorrelation();
        Actor actor = SecurityContext.require();

        sessions.findOwnedBy(unitOfWork, merchant.value(), id)
                .orElseThrow(UnknownCheckoutSessionException::new);
        CheckoutSession current =
                sessions.findByIdForUpdate(unitOfWork, id)
                        .orElseThrow(UnknownCheckoutSessionException::new);
        if (current.status() == CheckoutSessionStatus.ABANDONED) {
            return false;
        }

        // Throws IllegalCheckoutSessionTransitionException for every state that cannot get
        // here - PAYMENT_PENDING above all. The aggregate is the judge; this class does not
        // re-list the edges (a second list is a second thing to get wrong).
        CheckoutSession abandoned = current.abandon(clock);
        if (!sessions.transition(unitOfWork, current, abandoned)) {
            return false;
        }

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        CheckoutAuditAction.CHECKOUT_SESSION_ABANDONED,
                        SESSION_TARGET_TYPE,
                        current.id().value().toString(),
                        // THE ONE CHECKOUT ACTION WITH A REASON (INV-AUD-03): a merchant
                        // withdrawing an offer it already made is a judgement about somebody
                        // else's purchase, and the customer looking at the page finds their
                        // checkout gone.
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "session=" + current.id() + ", merchant=" + merchant)));
        return true;
    }

    /** {@code PAYMENT_PENDING → COMPLETED}, or {@code EXPIRED → COMPLETED_LATE}. */
    private Optional<CheckoutSession> completionOf(CheckoutSession session) {
        return switch (session.status()) {
            case PAYMENT_PENDING -> Optional.of(session.complete(clock));
            case EXPIRED -> Optional.of(session.completeLate(clock));
            default -> Optional.empty();
        };
    }

    private void announceOrder(
            Connection unitOfWork,
            Order order,
            CheckoutSession session,
            Correlation correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ORDER_PAID_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        order.id(),
                        ORDER_TARGET_TYPE,
                        order.createdAt(),
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("checkoutId", session.id().value().toString())
                        .with("merchantId", session.merchantRef().toString())
                        .with("capturedEntryId", order.capturedEntryRef().toString())
                        // NOT the line summary: what somebody bought is not for every consumer
                        // of this event (DATA_CLASSIFICATION's reasoning for that column).
                        .with("completedAs", session.status().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // ----------------------------------------------------------------- reads

    /** A session by its own identifier, with no tenant - the confirming customer's read. */
    public Optional<CheckoutSession> ownedBySession(
            Connection unitOfWork, CheckoutSessionId id) {
        return sessions.findById(unitOfWork, id);
    }

    /**
     * A merchant's own session, or empty — <strong>the tenant predicate is in the
     * statement</strong> ({@code INV-MER-01}), not a filter over an unscoped read.
     */
    public Optional<CheckoutSession> ownedBy(
            Connection unitOfWork, MerchantId merchant, CheckoutSessionId id) {
        return sessions.findOwnedBy(unitOfWork, merchant.value(), id);
    }

    /** The order a session produced, or empty when it died unpaid. */
    public Optional<Order> orderOf(Connection unitOfWork, CheckoutSessionId session) {
        return orders.findBySession(unitOfWork, session);
    }

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a checkout command must run inside a correlation"
                                                + " scope"));
    }
}
