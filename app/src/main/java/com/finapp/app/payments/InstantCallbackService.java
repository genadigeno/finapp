package com.finapp.app.payments;

import com.finapp.payments.CallbackHint;
import com.finapp.payments.CallbackHintResolution;
import com.finapp.payments.CallbackHintStore;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.HintedOutcome;
import com.finapp.payments.InstantCallbackInquiry;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.security.SecurityContext;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * The instant rail's signed confirmation callback (`P7-TSK-009`, ADR-0062 §5; since `P15-TSK-036`,
 * ADR-0083 decision 4: a hint) — the {@code PaymentWebhookService} doctrine at the second rail's own
 * door, deliberately a sibling rather than a generalisation: this vocabulary attributes by OUR
 * end-to-end reference and speaks the payer PSP's three words, and one door serving two provider
 * grammars is the god-door the two-vocabulary discipline refuses.
 *
 * <p>Authenticate before parsing (the per-rail HMAC over {@code timestamp + "." + raw
 * bytes}, its own key); evidence for every authenticated delivery ({@code INV-HIST-02});
 * the inbox's dedupe in the same commit ({@code INV-IDEM-04}); the anti-stall
 * acknowledgement for the unparseable and the unattributable (ADR-0047 §5).
 *
 * <p>Then, holding no connection, {@link InstantCallbackInquiry} asks the scheme over the provider
 * credential and only its answer moves anything. The body names the subject and the word chooses
 * the question; the amount, the scheme reference and the cycle a posting or a parking uses are the
 * inquiry's. Even value that names nothing we minted parks only on the scheme's own record of it
 * ({@code INV-REC-05}), which is asked for by the scheme's reference because our own cannot ask about
 * a statement that names none.
 *
 * <p><strong>Every reference we mint on the rail is recognised</strong>: a pay-in's attributes to its
 * attempt, and a return's or a withdrawal's is asked about through that row's own inquiry, so a
 * withdrawal's confirmation can never park as inbound value for money that went out.
 */
@Slf4j
public class InstantCallbackService {

    static final String CONSUMER = "payments.instant-webhook";
    static final String MESSAGE_TYPE = "payments.InstantConfirmation";

    /** The card door's charset rule, verbatim: the event id keys a durable column. */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:@/+=-]+");

    private final WebhookSignature signature;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentAttemptStore<Connection> attempts;
    private final com.finapp.app.telemetry.PaymentMeters meters;
    private final InboxConsumer<Connection> inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final InstantCallbackInquiry inquiry;
    private final CallbackHintStore<Connection> hints;
    private final CallbackHintResolution hintResolution;

    /** The rail this door serves — the composition root's binding, never a name held here
     * ({@code INV-RAIL-01}: the {@code Withdrawals} constructor's discipline at a door). */
    private final RailId railId;
    private final RefundStore<Connection> refunds;
    private final WithdrawalStore<Connection> withdrawals;

    /** At most so many inquiries at once on this instance's request threads; a refused one is left to the sweep. */
    private final CallbackInquiryBulkhead bulkhead;

    public InstantCallbackService(
            WebhookSignature instantWebhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            InboxConsumer<Connection> inboxConsumer,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionRunner paymentTransactionRunner,
            InstantCallbackInquiry instantCallbackInquiry,
            CallbackHintStore<Connection> callbackHintStore,
            CallbackHintResolution callbackHintResolution,
            RailId railId,
            RefundStore<Connection> refundStore,
            WithdrawalStore<Connection> withdrawalStore,
            CallbackInquiryBulkhead callbackInquiryBulkhead) {
        this.signature =
                Objects.requireNonNull(
                        instantWebhookSignature, "instantWebhookSignature must not be null");
        this.evidence =
                Objects.requireNonNull(
                        providerEvidenceStore, "providerEvidenceStore must not be null");
        this.attempts =
                Objects.requireNonNull(paymentAttemptStore, "paymentAttemptStore must not be null");
        this.meters = Objects.requireNonNull(paymentMeters, "paymentMeters must not be null");
        this.inbox = Objects.requireNonNull(inboxConsumer, "inboxConsumer must not be null");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(
                        paymentTransactionRunner, "paymentTransactionRunner must not be null");
        this.inquiry =
                Objects.requireNonNull(instantCallbackInquiry, "instantCallbackInquiry must not be null");
        this.hints = Objects.requireNonNull(callbackHintStore, "callbackHintStore must not be null");
        this.hintResolution =
                Objects.requireNonNull(
                        callbackHintResolution, "callbackHintResolution must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
        this.refunds = Objects.requireNonNull(refundStore, "refundStore must not be null");
        this.withdrawals =
                Objects.requireNonNull(withdrawalStore, "withdrawalStore must not be null");
        this.bulkhead =
                Objects.requireNonNull(
                        callbackInquiryBulkhead, "callbackInquiryBulkhead must not be null");
    }

    /**
     * The scheme's wire shape — ours end to end (the stub speaks it). This door reads the scheme's
     * event id (the inbox key), OUR end-to-end reference and the scheme's reference (each the subject
     * of an inquiry), and the payer PSP's word (which inquiry to make). The amount and the cycle rest
     * as evidence and are read by nobody here.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record CallbackPayload(
            String eventId,
            String reference,
            String status,
            String schemeReference) {}

    /** What this delivery asks about, decided in its transaction and acted on after the commit. */
    private sealed interface Planned {

        /** A pay-in that can still move. */
        record PayIn(PaymentAttemptId attempt, EndToEndReference reference) implements Planned {}

        /** A return this rail carries. */
        record ReturnPayment(Refund refund) implements Planned {}

        /** A withdrawal this rail carries. */
        record WithdrawalOutcome(Withdrawal withdrawal) implements Planned {}

        /** Value claimed on a concluded row, or on none: no row to sweep, so a durable hint carries it. */
        record Durable(CallbackHint.Kind kind, Optional<PaymentAttemptId> attempt, String askedAbout)
                implements Planned {}

        /** Nothing to ask; {@code unreadable} when the statement could not be read. */
        record Nothing(boolean unreadable) implements Planned {}
    }

    /**
     * Accepts one delivery.
     *
     * @throws ApiException 401 for every unauthenticated or unfresh shape (one refusal),
     *     413 for a body outside the evidence bound, 409 for a contended dedupe record
     *     (unacknowledged, so the scheme redelivers — the inbox's own contract)
     */
    public void deliver(byte[] rawBody, String presentedTimestamp, String presentedSignature) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (!signature.matches(presentedTimestamp, rawBody, presentedSignature)) {
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.REFUSED);
            throw new ApiException(
                    PlatformErrorCode.UNAUTHENTICATED,
                    "An instant confirmation failed signature or freshness verification");
        }
        if (rawBody.length == 0 || rawBody.length > ProviderEvidenceStore.MAX_PAYLOAD_BYTES) {
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.REFUSED);
            throw new ApiException(
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "An instant confirmation body was empty or exceeded the evidence bound");
        }

        Optional<CallbackPayload> parsed = parse(rawBody);
        Optional<String> eventId = parsed.flatMap(payload -> usableEventId(payload.eventId()));
        if (eventId.isEmpty()) {
            transactions.inTransaction(
                    unitOfWork -> {
                        retain(unitOfWork, Optional.empty(), Optional.empty(), Optional.empty(), rawBody);
                        return null;
                    });
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.UNMAPPABLE);
            log.warn(
                    "An authenticated instant confirmation was unparseable or carried no"
                            + " usable event id; its bytes are retained as evidence and it"
                            + " is acknowledged (ADR-0047 §5)");
            return;
        }

        Optional<EndToEndReference> reference =
                parsed.flatMap(payload -> mintedShape(payload.reference()));
        Delivered delivered =
                transactions.inTransaction(
                        unitOfWork -> {
                            Optional<PaymentAttempt> subject =
                                    reference.flatMap(
                                            ours ->
                                                    attempts.findByEndToEndReference(
                                                            unitOfWork, ours));
                            Optional<Refund> returned =
                                    subject.isPresent()
                                            ? Optional.empty()
                                            : refundFor(unitOfWork, parsed.get().reference());
                            Optional<Withdrawal> withdrawn =
                                    subject.isPresent() || returned.isPresent()
                                            ? Optional.empty()
                                            : withdrawalFor(unitOfWork, reference);
                            Planned planned = plan(subject, returned, withdrawn, parsed.get());
                            CallbackHint[] hint = new CallbackHint[1];
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(
                                                    CONSUMER,
                                                    SimulatedInstantSchemeAdapter.NAME
                                                            + ":"
                                                            + eventId.get()),
                                            MESSAGE_TYPE,
                                            // The dedupe record, and the hint for a statement no row
                                            // can stand for: the claim itself is never an effect.
                                            uow -> {
                                                if (planned instanceof Planned.Durable durable) {
                                                    hint[0] =
                                                            hints.insert(
                                                                            uow,
                                                                            durable.kind(),
                                                                            durable.attempt(),
                                                                            durable.askedAbout(),
                                                                            hintResolution.firstAsk())
                                                                    .orElse(null);
                                                }
                                            });
                            retain(
                                    unitOfWork,
                                    subject.map(PaymentAttempt::id),
                                    returned.map(Refund::id),
                                    withdrawn.map(Withdrawal::id),
                                    rawBody);
                            boolean attributed =
                                    subject.isPresent() || returned.isPresent() || withdrawn.isPresent();
                            return new Delivered(
                                    consumed, attributed, planned, Optional.ofNullable(hint[0]));
                        });

        if (delivered.consumed() == InboxConsumer.Outcome.CONTENDED) {
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "An instant confirmation is being processed by another instance; asking"
                            + " the scheme to redeliver");
        }
        if (delivered.consumed() == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            // No inquiry: the cost of a callback is bounded by this dedupe (ADR-0083 decision 5).
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.DUPLICATE);
            log.info(
                    "A duplicate instant confirmation delivery was absorbed by the inbox;"
                            + " its bytes are retained as evidence (INV-IDEM-04,"
                            + " INV-HIST-02)");
            return;
        }

        HintedOutcome outcome = effect(delivered.planned(), delivered.hint());
        boolean unreadable = delivered.planned() instanceof Planned.Nothing nothing && nothing.unreadable();
        boolean handled = delivered.attributed() || delivered.planned() instanceof Planned.Durable;
        meters.webhook(
                handled && !unreadable && !outcome.isBreak()
                        ? com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.PROCESSED
                        : com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.UNMAPPABLE);
        if (!handled) {
            log.warn(
                    "An authenticated instant confirmation named no initiation this platform"
                            + " made and carried no reference to ask about; retained unattributed"
                            + " and acknowledged (ADR-0047 §5)");
        }
    }

    /**
     * Which inquiry this statement calls for. The word chooses the question and nothing more: the
     * answer alone moves anything. A rejection or an expiry on a row that has concluded, or naming
     * nothing of ours, has no outcome to adopt and asks nothing.
     */
    private static Planned plan(
            Optional<PaymentAttempt> attempt,
            Optional<Refund> returned,
            Optional<Withdrawal> withdrawn,
            CallbackPayload payload) {
        if (attempt.isEmpty() && returned.isEmpty() && withdrawn.isEmpty()) {
            return unattributed(payload);
        }
        if (!outcomeWord(payload)) {
            return new Planned.Nothing(true);
        }
        if (returned.isPresent()) {
            return new Planned.ReturnPayment(returned.get());
        }
        if (withdrawn.isPresent()) {
            return new Planned.WithdrawalOutcome(withdrawn.get());
        }
        PaymentAttempt named = attempt.get();
        if (pushResolvable(named.status())) {
            return new Planned.PayIn(named.id(), named.endToEndReference());
        }
        return "executed".equals(payload.status())
                ? new Planned.Durable(
                        CallbackHint.Kind.INSTANT_CONCLUDED_EXECUTION,
                        Optional.of(named.id()),
                        named.endToEndReference().value())
                : new Planned.Nothing(false);
    }

    /** A statement naming nothing of ours: only value can matter, and only the scheme's record of it. */
    private static Planned unattributed(CallbackPayload payload) {
        if (!"executed".equals(payload.status())) {
            return new Planned.Nothing(false);
        }
        return schemeReference(payload.schemeReference())
                .<Planned>map(
                        scheme ->
                                new Planned.Durable(
                                        CallbackHint.Kind.INSTANT_UNATTRIBUTED_EXECUTION,
                                        Optional.empty(),
                                        scheme.value()))
                .orElseGet(() -> new Planned.Nothing(true));
    }

    /**
     * The inquiry, after the commit and holding no connection, as the platform (the module's enumerated
     * {@code enterSystem()} site at this door: the payer PSP's unsolicited statement has no session).
     * Never throws: a failed inquiry is logged and left to the sweep.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private HintedOutcome effect(Planned planned, Optional<CallbackHint> hint) {
        if (planned instanceof Planned.Nothing) {
            return HintedOutcome.UNDECIDED;
        }
        if (!bulkhead.tryEnter()) {
            log.info("Too many hinted inquiries are running on this instance; this one is left to the sweep");
            return HintedOutcome.UNDECIDED;
        }
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return switch (planned) {
                case Planned.PayIn named -> inquiry.payIn(named.attempt(), named.reference());
                case Planned.ReturnPayment named -> inquiry.returnPayment(named.refund());
                case Planned.WithdrawalOutcome named -> inquiry.withdrawal(named.withdrawal());
                // No hint means the same question already stands open: its next ask answers this delivery too.
                case Planned.Durable durable -> hint.map(hintResolution::resolve).orElse(HintedOutcome.UNDECIDED);
                case Planned.Nothing nothing -> HintedOutcome.UNDECIDED;
            };
        } catch (RuntimeException failure) {
            // The class only: a scheme or JDBC message can name hosts and identifiers (INV-AUD-02).
            log.warn(
                    "The inquiry an instant confirmation triggered failed: {}; the sweep decides",
                    failure.getClass().getSimpleName());
            return HintedOutcome.UNDECIDED;
        } finally {
            bulkhead.exit();
        }
    }

    /** The push model's resolvable sources at this door — waiting, or an outbound unknown. */
    private static boolean pushResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.AWAITING_PAYER
                || status == PaymentAttemptStatus.EXECUTION_DISPATCHED
                || status == PaymentAttemptStatus.EXECUTION_UNKNOWN;
    }

    /**
     * The payer PSP's three words, an execution only with a scheme reference: anything else is not a
     * statement of an outcome and asks nothing.
     */
    private static boolean outcomeWord(CallbackPayload payload) {
        return switch (payload.status() == null ? "" : payload.status()) {
            case "executed" -> schemeReference(payload.schemeReference()).isPresent();
            case "rejected", "expired" -> true;
            default -> false;
        };
    }

    /** The return this reference names on THIS rail, or absent — never a throw. */
    private Optional<Refund> refundFor(Connection uow, String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        try {
            return refunds.findByOperationReference(
                            uow, new com.finapp.payments.ProviderIdempotencyReference(reference))
                    .filter(
                            refund ->
                                    attempts.findById(uow, refund.attemptId())
                                            .filter(attempt -> attempt.rail().equals(railId))
                                            .isPresent());
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    /** The withdrawal this reference names on THIS rail, or absent. */
    private Optional<Withdrawal> withdrawalFor(Connection uow, Optional<EndToEndReference> reference) {
        return reference
                .flatMap(ours -> withdrawals.findByEndToEndReference(uow, ours))
                .filter(withdrawal -> withdrawal.railId().equals(railId));
    }

    /** Shape-total: a reference we cannot store is absent, never a throw. */
    private static Optional<ProviderReference> schemeReference(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProviderReference(value));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    /** Shape only, no read: a reference we never mint names nothing. */
    private static Optional<EndToEndReference> mintedShape(String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new EndToEndReference(reference));
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
    }

    /** Evidence, addressed to what the statement names, when it names something of ours. */
    private void retain(
            Connection unitOfWork,
            Optional<PaymentAttemptId> attempt,
            Optional<RefundId> refund,
            Optional<WithdrawalId> withdrawal,
            byte[] rawBody) {
        Instant now = Instant.now(clock);
        if (withdrawal.isPresent()) {
            evidence.appendForWithdrawal(unitOfWork, withdrawal.get(), EvidenceKind.WEBHOOK, rawBody, now);
        } else {
            evidence.append(unitOfWork, attempt, refund, EvidenceKind.WEBHOOK, rawBody, now);
        }
    }

    /** Total: unparseable is empty, never a throw — the anti-stall class, not a refusal. */
    private Optional<CallbackPayload> parse(byte[] rawBody) {
        try {
            return Optional.of(json.readValue(rawBody, CallbackPayload.class));
        } catch (tools.jackson.core.JacksonException unparseable) {
            return Optional.empty();
        }
    }

    private static Optional<String> usableEventId(String eventId) {
        if (eventId == null
                || eventId.isBlank()
                || eventId.length()
                        > InboxKey.MAX_LENGTH - SimulatedInstantSchemeAdapter.NAME.length() - 1
                || !EVENT_ID.matcher(eventId).matches()) {
            return Optional.empty();
        }
        return Optional.of(eventId);
    }

    /** The delivery transaction's yield: the dedupe outcome, whether a row owned it, and what to ask. */
    private record Delivered(
            InboxConsumer.Outcome consumed,
            boolean attributed,
            Planned planned,
            Optional<CallbackHint> hint) {}
}
