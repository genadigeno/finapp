package com.finapp.app.telemetry;

import com.finapp.payments.DisputeResponseStatus;
import com.finapp.payments.InteractionModel;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.RailCapabilities;
import com.finapp.payments.RailId;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.WithdrawalStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The payment surface's counters and timers (`P5-TSK-017`, `PHASE_5_PLAN.md` §15; the rail
 * series `P7-TSK-015`, `PHASE_7_PLAN.md` §15): {@code finapp.payments.attempt} and
 * {@code finapp.payments.refund} by outcome, {@code finapp.payments.provider.latency} by provider
 * and operation, {@code finapp.payments.webhook} by outcome, and — since `P7-TSK-015` —
 * {@code finapp.payments.rail.outcome} by rail, type and outcome and
 * {@code finapp.payments.rail.latency} by rail and operation.
 *
 * <p><strong>Every series is registered at construction</strong> (`P1-TSK-029`): a counter
 * created on its first increment is a series an alert cannot evaluate at exactly the moment
 * it is needed, and a freshly started instance must publish healthy zeros rather than
 * absences that read as a quiet system. The outcome series are derived from enums, so a new
 * judgement registers itself; the rail series from the declared rails' capabilities
 * ({@link #registerRail}), so each rail publishes exactly the combinations its machine can
 * produce and nothing branches on a rail's name ({@code INV-RAIL-01}).
 *
 * <p><strong>Only the ACTING judgement counts, post-commit</strong> — the plan's own
 * "replays/converges never counted". The acting bit is the conditional transition's own row
 * count, and the one place it exists is the applier that wrote it: since `P7-TSK-015` the three
 * appliers report each acting judgement to their {@code RailOutcomeObserver}, and
 * {@link CommittedRailOutcomes} counts it here once its transaction commits — so a judgement is
 * counted once however many doors raced for it, and one that rolled back never happened. (Until
 * then each DOOR counted after its commit, and every door Phase 7 added lost its count.)
 *
 * <p><strong>The timers record every outcome</strong>, in a {@code finally} inside the port
 * decorators: a timer that recorded only successes would flatter exactly the incident an
 * operator is trying to see. No percentile histogram (`P1-TSK-029`: {@code _bucket} does not
 * exist unless asked for); the dashboard reads {@code _count}, {@code _sum} and {@code _max}.
 * The {@code provider} tag names the organisation the call went to — the card PSP, or the
 * instant scheme under its OWN name (until `P7-TSK-015` every instant call was published as the
 * card PSP's, the mislabel the rail series exposed).
 *
 * <p>Tags carry {@code provider}, {@code rail}, {@code operation}, {@code type} and
 * {@code outcome} only — deployment constants and closed vocabularies, all bounded at compile
 * time. The provider's status vocabulary never reaches a meter ({@code INV-PAY-03}), and no
 * identifier, reference or amount ever does ({@code INV-AUD-02}).
 */
public final class PaymentMeters {

    /** {@code finapp.payments.attempt} — acting attempt judgements, by outcome. */
    static final String ATTEMPT = "finapp.payments.attempt";

    /** {@code finapp.payments.provider.latency} — provider call duration, every outcome. */
    static final String PROVIDER_LATENCY = "finapp.payments.provider.latency";

    /** {@code finapp.payments.webhook} — webhook deliveries, by what became of them. */
    static final String WEBHOOK = "finapp.payments.webhook";

    /** {@code finapp.payments.refund} — acting refund judgements, by outcome. */
    static final String REFUND = "finapp.payments.refund";

    /** {@code finapp.payments.routing.decision} — routing decisions, by rail and outcome. */
    static final String ROUTING_DECISION = "finapp.payments.routing.decision";

    /** {@code finapp.payments.unmatched.parked} — money parked in suspense (`P7-TSK-009`). */
    static final String UNMATCHED_PARKED = "finapp.payments.unmatched.parked";

    /** {@code finapp.payments.rail.outcome} — acting judgements by rail, type and outcome. */
    static final String RAIL_OUTCOME = "finapp.payments.rail.outcome";

    /** {@code finapp.payments.rail.latency} — every rail port call, by rail and operation. */
    static final String RAIL_LATENCY = "finapp.payments.rail.latency";

    /** What an acting judgement decided about an attempt — the machine's own vocabulary. */
    public enum Judgement {
        AUTHORIZED,
        CAPTURED,
        FAILED,
        UNKNOWN,
        /** The push pay-in's completion (`P7-TSK-009`): EXECUTED is not CAPTURED here either. */
        EXECUTED,
        /** The card authorization released (`P7-TSK-004`); counted from `P7-TSK-015`. */
        VOIDED;

        /**
         * The committed status as a judgement, or empty for a mid-question one: a dispatched
         * operation, or a pay-in awaiting its payer, has decided nothing yet. Every
         * {@code *_UNKNOWN} IS a decision — the honest one ({@code INV-LIFE-03}). Exhaustive, so
         * a new state cannot be added without deciding what it counts as.
         */
        public static Optional<Judgement> of(PaymentAttemptStatus status) {
            return switch (status) {
                case AUTHORIZED -> Optional.of(AUTHORIZED);
                case CAPTURED -> Optional.of(CAPTURED);
                case EXECUTED -> Optional.of(EXECUTED);
                case VOIDED -> Optional.of(VOIDED);
                case FAILED -> Optional.of(FAILED);
                case AUTH_UNKNOWN, CAPTURE_UNKNOWN, EXECUTION_UNKNOWN, VOID_UNKNOWN ->
                        Optional.of(UNKNOWN);
                case AUTH_DISPATCHED, CAPTURE_DISPATCHED, EXECUTION_DISPATCHED, VOID_DISPATCHED,
                        AWAITING_PAYER -> Optional.empty();
            };
        }
    }

    /** What became of a delivery at the webhook door (ADR-0047's own four states). */
    public enum WebhookOutcome {
        /** Authentic, mapped to an operation, and its effect applied. */
        PROCESSED,
        /** Authentic and already handled — the inbox absorbed it ({@code INV-IDEM-04}). */
        DUPLICATE,
        /** Unauthenticated, unfresh or outside the evidence bound: nothing written. */
        REFUSED,
        /** Authentic but naming no operation we minted, or unreadable (ADR-0047 §5). */
        UNMAPPABLE
    }

    /** What an acting judgement decided about a refund. */
    public enum RefundOutcome {
        COMPLETED,
        FAILED,
        UNKNOWN;

        /** The committed status as a judgement; a dispatch is none. */
        public static Optional<RefundOutcome> of(RefundStatus status) {
            return switch (status) {
                case COMPLETED -> Optional.of(COMPLETED);
                case FAILED -> Optional.of(FAILED);
                case UNKNOWN -> Optional.of(UNKNOWN);
                case DISPATCHED -> Optional.empty();
            };
        }
    }

    /** What an acting judgement decided about a withdrawal (`P7-TSK-015`). */
    public enum WithdrawalOutcome {
        COMPLETED,
        FAILED,
        UNKNOWN;

        /** The committed status as a judgement; a dispatch is none. */
        public static Optional<WithdrawalOutcome> of(WithdrawalStatus status) {
            return switch (status) {
                case COMPLETED -> Optional.of(COMPLETED);
                case FAILED -> Optional.of(FAILED);
                case UNKNOWN -> Optional.of(UNKNOWN);
                case DISPATCHED -> Optional.empty();
            };
        }
    }

    /** What an acting judgement decided about a dispute answer (`P7-TSK-015`). */
    public enum ResponseOutcome {
        /** The PSP took the answer — never the network's verdict, which is the dispute's. */
        SUBMITTED,
        FAILED,
        UNKNOWN;

        /** The committed status as a judgement; a dispatch is none. */
        public static Optional<ResponseOutcome> of(DisputeResponseStatus status) {
            return switch (status) {
                case SUBMITTED -> Optional.of(SUBMITTED);
                case FAILED -> Optional.of(FAILED);
                case UNKNOWN -> Optional.of(UNKNOWN);
                case DISPATCHED -> Optional.empty();
            };
        }
    }

    /** The kind of thing a rail judged — the rail outcome's {@code type} tag (`P7-TSK-015`). */
    public enum JudgedType {
        PAYMENT,
        REFUND,
        WITHDRAWAL,
        DISPUTE_RESPONSE
    }

    /** The ports' methods — the {@code operation} tag's closed vocabulary. */
    public enum Operation {
        AUTHORIZE,
        CAPTURE,
        REFUND,
        /** The card rail's reversal (`P7-TSK-004`) — the port's fifth method. */
        VOID,
        QUERY,
        /** The push rail's outbound credit transfer (`P7-TSK-008`, ADR-0062 §6). */
        WITHDRAW,
        /** The pay-by-bank initiation (`P7-TSK-009`, ADR-0062 §5) — the port's opener. */
        INITIATE,
        /** A dispute answer at the card PSP's dispute port (`P7-TSK-015`, ADR-0061 §7). */
        RESPOND
    }

    /**
     * The card PSP's operations across its two ports — the payment port and the dispute port
     * (`P7-TSK-015`): what {@code provider.latency} registers for the provider this class is
     * constructed with. The push scheme registers its own ({@link #PUSH_OPERATIONS}) when its
     * decorator is built, under its own name.
     */
    static final Set<Operation> CARD_PSP_OPERATIONS =
            EnumSet.of(
                    Operation.AUTHORIZE,
                    Operation.CAPTURE,
                    Operation.REFUND,
                    Operation.VOID,
                    Operation.QUERY,
                    Operation.RESPOND);

    /** The push rail port's timed operations (the grant exchange stays un-timed). */
    public static final Set<Operation> PUSH_OPERATIONS =
            EnumSet.of(Operation.INITIATE, Operation.WITHDRAW, Operation.REFUND, Operation.QUERY);

    private final Map<Judgement, Counter> attempts = new EnumMap<>(Judgement.class);
    private final Map<WebhookOutcome, Counter> webhooks = new EnumMap<>(WebhookOutcome.class);
    private final Map<RefundOutcome, Counter> refunds = new EnumMap<>(RefundOutcome.class);
    private final Counter unmatchedParked;

    /**
     * Held for the directory-tagged series: the routing counter's rail tag and the rail series
     * resolve per call through micrometer's dedupe by id — one meter per tag combination, the
     * first registration at construction. Every tag is a bounded vocabulary (ADR-0060's
     * operational note).
     */
    private final MeterRegistry registry;

    /**
     * Public because the payment slice's own suites compose the real doors (the
     * {@code TransferMetrics} bean is consumed the same way): a suite driving the webhook
     * resolver or the sweeper schedule constructs this over a {@code SimpleMeterRegistry}
     * of its own, so it exercises the WIRED counting path rather than a double. Registers no
     * rail series up front — a suite's rail series register on first use.
     */
    public PaymentMeters(MeterRegistry registry, String provider) {
        this(registry, provider, PaymentRails.of(List.of()));
    }

    /**
     * The wired construction (`P7-TSK-015`): the rail series of every rail {@code rails}
     * declares are registered here, derived from each declaration's capabilities — so a
     * freshly started instance publishes them before any rail is configured or called.
     */
    public PaymentMeters(MeterRegistry registry, String provider, PaymentRails rails) {
        this.registry = registry;
        // The routing series exists on a FRESHLY STARTED instance (the P2-TSK-020 lesson:
        // criterion 6 is judged on a fresh boot at the phase flip, not after traffic): a
        // zero baseline under neutral tags; the real (rail, outcome) series arrive with
        // real decisions through routingDecision().
        routingCounter("none", "none");
        for (Judgement judgement : Judgement.values()) {
            attempts.put(
                    judgement,
                    Counter.builder(ATTEMPT)
                            .tag("outcome", lower(judgement.name()))
                            .description(
                                    "Acting payment-attempt judgements by outcome, counted once"
                                        + " the transaction that applied them commits:"
                                        + " authorized, captured, executed, voided, failed, and"
                                        + " the honest unknown (INV-LIFE-03) a resolver later"
                                        + " settles - whichever door or sweep carried them. A"
                                        + " replay, a converged retry and the losers of a racing"
                                        + " resolution are never throughput. Per instance;"
                                        + " rate() and sum() aggregate")
                            .register(registry));
        }
        for (WebhookOutcome outcome : WebhookOutcome.values()) {
            webhooks.put(
                    outcome,
                    Counter.builder(WEBHOOK)
                            .tag("outcome", lower(outcome.name()))
                            .description(
                                    "Provider webhook deliveries by what became of them:"
                                        + " processed applied an effect, duplicate was"
                                        + " absorbed by the inbox, refused failed"
                                        + " authentication or the evidence bound, unmappable"
                                        + " was authentic but named no operation we minted. A"
                                        + " rise in refused is somebody probing the door; a"
                                        + " rise in unmappable is an integration break. Per"
                                        + " instance; rate() and sum() aggregate")
                            .register(registry));
        }
        for (RefundOutcome outcome : RefundOutcome.values()) {
            refunds.put(
                    outcome,
                    Counter.builder(REFUND)
                            .tag("outcome", lower(outcome.name()))
                            .description(
                                    "Acting refund judgements by outcome, counted once their"
                                        + " transaction commits: completed released the hold and"
                                        + " posted, failed released with nothing posted, unknown"
                                        + " left the customer's funds visibly reserved - card"
                                        + " refunds, push returns and book refunds alike. A"
                                        + " replay and a converged takeover are never"
                                        + " throughput. Per instance; rate() and sum()"
                                        + " aggregate")
                            .register(registry));
        }
        unmatchedParked =
                Counter.builder(UNMATCHED_PARKED)
                        .description(
                                "Confirmations carrying money the platform could not"
                                    + " attribute, parked in SUSPENSE_UNMATCHED (P7-TSK-009,"
                                    + " INV-REC-05): acting parkings only - a duplicate"
                                    + " delivery converges and counts nothing. ANY rise is an"
                                    + " integration break to investigate; the parked-age"
                                    + " gauge is the standing alert. Per instance; rate() and"
                                    + " sum() aggregate")
                        .register(registry);
        registerProvider(provider, CARD_PSP_OPERATIONS);
        for (RailId rail : rails.declaredIds()) {
            registerRail(rail, rails.capabilitiesOf(rail));
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Registers {@code provider}'s latency series for {@code operations} — at construction for
     * the card PSP, and by the push decorator for the scheme it wraps, at boot, before its first
     * call (`P1-TSK-029`).
     */
    public void registerProvider(String provider, Set<Operation> operations) {
        for (Operation operation : operations) {
            providerTimer(provider, operation);
        }
    }

    /**
     * Registers the rail series one declared rail can produce, from its capabilities alone —
     * never its name ({@code INV-RAIL-01}): its payment judgements are its interaction model's
     * own states; refunds on every rail (a book refund completes whole or not at all, so it
     * judges only {@code completed}); withdrawals on a push rail; dispute answers where the rail
     * declares card chargebacks. The latency series follow the ports the model speaks through.
     */
    void registerRail(RailId rail, RailCapabilities capabilities) {
        InteractionModel model = capabilities.interactionModel();
        Set<Judgement> payments = EnumSet.noneOf(Judgement.class);
        for (PaymentAttemptStatus status : model.statuses()) {
            Judgement.of(status).ifPresent(payments::add);
        }
        for (Judgement judgement : payments) {
            railOutcome(rail, JudgedType.PAYMENT, judgement.name());
        }
        if (capabilities.refundMode() == RailCapabilities.RefundMode.BOOK_REFUND) {
            railOutcome(rail, JudgedType.REFUND, RefundOutcome.COMPLETED.name());
        } else {
            for (RefundOutcome outcome : RefundOutcome.values()) {
                railOutcome(rail, JudgedType.REFUND, outcome.name());
            }
        }
        if (model == InteractionModel.PUSH) {
            for (WithdrawalOutcome outcome : WithdrawalOutcome.values()) {
                railOutcome(rail, JudgedType.WITHDRAWAL, outcome.name());
            }
        }
        boolean chargebacks =
                capabilities.disputes() == RailCapabilities.DisputeModel.CARD_SCHEME_CHARGEBACKS;
        if (chargebacks) {
            for (ResponseOutcome outcome : ResponseOutcome.values()) {
                railOutcome(rail, JudgedType.DISPUTE_RESPONSE, outcome.name());
            }
        }
        Set<Operation> operations =
                switch (model) {
                    case TWO_STEP -> {
                        Set<Operation> card =
                                EnumSet.of(
                                        Operation.AUTHORIZE,
                                        Operation.CAPTURE,
                                        Operation.REFUND,
                                        Operation.QUERY);
                        if (capabilities.reversals().contains(RailCapabilities.Reversal.VOID)) {
                            card.add(Operation.VOID);
                        }
                        if (chargebacks) {
                            card.add(Operation.RESPOND);
                        }
                        yield card;
                    }
                    case PUSH -> PUSH_OPERATIONS;
                    // A book movement has no wire, so nothing to time.
                    case BOOK -> EnumSet.noneOf(Operation.class);
                };
        for (Operation operation : operations) {
            railTimer(rail, operation);
        }
    }

    /**
     * An acting attempt judgement on {@code rail}, post-commit — the legacy un-railed series and
     * the rail series together, so the two can never disagree.
     */
    public void attemptJudged(RailId rail, Judgement judgement) {
        attempts.get(judgement).increment();
        railOutcome(rail, JudgedType.PAYMENT, judgement.name()).increment();
    }

    /** An acting refund judgement on the refunded attempt's rail, post-commit. */
    public void refundJudged(RailId rail, RefundOutcome outcome) {
        refunds.get(outcome).increment();
        railOutcome(rail, JudgedType.REFUND, outcome.name()).increment();
    }

    /** An acting withdrawal judgement on {@code rail}, post-commit (`P7-TSK-015`). */
    public void withdrawalJudged(RailId rail, WithdrawalOutcome outcome) {
        railOutcome(rail, JudgedType.WITHDRAWAL, outcome.name()).increment();
    }

    /** An acting dispute-answer judgement on the disputed rail, post-commit (`P7-TSK-015`). */
    public void disputeResponseJudged(RailId rail, ResponseOutcome outcome) {
        railOutcome(rail, JudgedType.DISPUTE_RESPONSE, outcome.name()).increment();
    }

    private Counter railOutcome(RailId rail, JudgedType type, String outcome) {
        return Counter.builder(RAIL_OUTCOME)
                .tag("rail", rail.value())
                .tag("type", lower(type.name()))
                .tag("outcome", lower(outcome))
                .description(
                        "Acting judgements by rail, type and outcome (P7-TSK-015): payments"
                            + " (authorized, captured, executed, voided, failed, and the honest"
                            + " unknown), refunds, withdrawals and dispute answers, each counted"
                            + " once however many doors and sweeps raced for it, after the"
                            + " transaction that applied it committed. A rail's success rate is"
                            + " its successes over its decided outcomes. Per instance; rate()"
                            + " and sum() aggregate")
                .register(registry);
    }

    /**
     * One routing decision (`P7-TSK-003`): the chosen rail with outcome {@code chosen}, or
     * rail {@code none} with the refusal's leading rejection. Telemetry only — the counts
     * of record are the decision rows.
     */
    public void routingDecision(String rail, String outcome) {
        routingCounter(rail, outcome).increment();
    }

    private Counter routingCounter(String rail, String outcome) {
        return Counter.builder(ROUTING_DECISION)
                .tag("rail", rail)
                .tag("outcome", lower(outcome))
                .description(
                        "Routing decisions by chosen rail and outcome: chosen, or the"
                            + " refusal's leading rejection reason with rail=none. A refused"
                            + " confirm records one decision per attempt, so a spike here"
                            + " with rail=none is a rail outage or a policy gap. Per"
                            + " instance; rate() and sum() aggregate")
                .register(registry);
    }

    /** A webhook delivery's fate, after its transaction committed (or refused everything). */
    public void webhook(WebhookOutcome outcome) {
        webhooks.get(outcome).increment();
    }

    /**
     * One port call's duration, whatever it answered — the injected clock's measure, recorded
     * under the organisation it went to AND the rail it served (`P7-TSK-015`).
     */
    public void call(String provider, RailId rail, Operation operation, Duration elapsed) {
        providerTimer(provider, operation).record(elapsed);
        railTimer(rail, operation).record(elapsed);
    }

    private Timer providerTimer(String provider, Operation operation) {
        return Timer.builder(PROVIDER_LATENCY)
                .tag("provider", provider)
                .tag("operation", lower(operation.name()))
                .description(
                        "Duration of a call to an external payment provider, EVERY outcome -"
                            + " approvals, declines, timeouts and dropped connections alike,"
                            + " recorded in a finally, under the organisation the call went to."
                            + " A timer that recorded only successes would hide the ambiguity"
                            + " this phase exists to handle. No histogram buckets: read _count,"
                            + " _sum and _max")
                .register(registry);
    }

    private Timer railTimer(RailId rail, Operation operation) {
        return Timer.builder(RAIL_LATENCY)
                .tag("rail", rail.value())
                .tag("operation", lower(operation.name()))
                .description(
                        "Duration of every call a rail's port makes, by rail and operation"
                            + " (P7-TSK-015) - approvals, declines, timeouts and refused"
                            + " connections alike, recorded in a finally. A rail whose _max"
                            + " climbs is a rail in trouble before its outcomes say so. No"
                            + " histogram buckets: read _count, _sum and _max")
                .register(registry);
    }

    /** An acting suspense parking (`P7-TSK-009`), post-commit — duplicates count nothing. */
    public void unmatchedParked() {
        unmatchedParked.increment();
    }
}
