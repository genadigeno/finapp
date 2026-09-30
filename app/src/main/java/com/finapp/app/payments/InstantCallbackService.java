package com.finapp.app.payments;

import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.UnmatchedConfirmation;
import com.finapp.payments.UnmatchedConfirmations;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The instant rail's signed confirmation callback (`P7-TSK-009`, ADR-0062 §5) — the
 * {@code PaymentWebhookService} doctrine at the second rail's own door, deliberately a
 * sibling rather than a generalisation: this vocabulary attributes by OUR end-to-end
 * reference and speaks the payer PSP's three words, and one door serving two provider
 * grammars is the god-door the two-vocabulary discipline refuses.
 *
 * <p>Authenticate before parsing (the per-rail HMAC over {@code timestamp + "." + raw
 * bytes}, its own key); evidence for every authenticated delivery ({@code INV-HIST-02});
 * the inbox's dedupe committed with the effect ({@code INV-IDEM-04}); the anti-stall
 * acknowledgement for the unparseable and the unattributable (ADR-0047 §5) — except that
 * here an unattributable statement can CARRY MONEY, and money is never merely
 * acknowledged: it parks in {@code SUSPENSE_UNMATCHED} inside the delivery's transaction
 * ({@link UnmatchedConfirmations}, {@code INV-REC-05}), aged and alerted.
 *
 * <p><strong>Every reference we mint on the rail is recognised</strong> (the Phase 7 -&gt; 8
 * transition): a pay-in's attributes to its attempt, and a return's or a WITHDRAWAL's own
 * confirmation echoed back is evidence only — the gate found the withdrawal's parked as
 * inbound money for value that went out. <strong>A statement of value on an attempt that
 * cannot move parks too</strong>: an execution on a failed attempt, or a second execution
 * under another scheme reference on an executed one, is money that arrived — the gate found
 * both acknowledged at {@code INFO}, booked nowhere. The executed amount is the applier's
 * judgement, not this door's, so the inquiry sweep cannot credit what this door refused.
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
    private final PaymentIntentStore<Connection> intents;
    private final PaymentOutcomes outcomes;
    private final UnmatchedConfirmations unmatched;
    private final com.finapp.app.telemetry.PaymentMeters meters;
    private final InboxConsumer<Connection> inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    /** The rail this door serves — the composition root's binding, never a name held here
     * ({@code INV-RAIL-01}: the {@code Withdrawals} constructor's discipline at a door). */
    private final com.finapp.payments.RailId railId;
    private final com.finapp.payments.RefundStore<Connection> refunds;
    private final com.finapp.payments.WithdrawalStore<Connection> withdrawals;

    public InstantCallbackService(
            WebhookSignature instantWebhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            PaymentIntentStore<Connection> paymentIntentStore,
            PaymentOutcomes paymentOutcomes,
            UnmatchedConfirmations unmatchedConfirmations,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            InboxConsumer<Connection> inboxConsumer,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate paymentTransactions,
            DataSource dataSource,
            com.finapp.payments.RailId railId,
            com.finapp.payments.RefundStore<Connection> refundStore,
            com.finapp.payments.WithdrawalStore<Connection> withdrawalStore) {
        this.signature =
                Objects.requireNonNull(
                        instantWebhookSignature, "instantWebhookSignature must not be null");
        this.evidence =
                Objects.requireNonNull(
                        providerEvidenceStore, "providerEvidenceStore must not be null");
        this.attempts =
                Objects.requireNonNull(paymentAttemptStore, "paymentAttemptStore must not be null");
        this.intents =
                Objects.requireNonNull(paymentIntentStore, "paymentIntentStore must not be null");
        this.outcomes = Objects.requireNonNull(paymentOutcomes, "paymentOutcomes must not be null");
        this.unmatched =
                Objects.requireNonNull(
                        unmatchedConfirmations, "unmatchedConfirmations must not be null");
        this.meters = Objects.requireNonNull(paymentMeters, "paymentMeters must not be null");
        this.inbox = Objects.requireNonNull(inboxConsumer, "inboxConsumer must not be null");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(paymentTransactions, "paymentTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
        this.refunds = Objects.requireNonNull(refundStore, "refundStore must not be null");
        this.withdrawals =
                Objects.requireNonNull(withdrawalStore, "withdrawalStore must not be null");
    }

    /**
     * The scheme's wire shape — ours end to end (the stub speaks it): the scheme's event
     * id, OUR end-to-end reference, the payer PSP's word ({@code executed}, {@code
     * rejected}, {@code expired}), the scheme's transaction reference and settlement
     * cycle, and the executed amount.
     */
    private record CallbackPayload(
            String eventId,
            String reference,
            String status,
            String schemeReference,
            String settlementCycle,
            String amount,
            String currency) {}

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
            inOneTransaction(
                    unitOfWork -> {
                        retain(unitOfWork, Optional.empty(), rawBody);
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
        Judged judged = new Judged();
        Delivered delivered =
                inOneTransaction(
                        unitOfWork -> {
                            Optional<PaymentAttempt> subject =
                                    reference.flatMap(
                                            ours ->
                                                    attempts.findByEndToEndReference(
                                                            unitOfWork, ours));
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(
                                                    CONSUMER,
                                                    SimulatedInstantSchemeAdapter.NAME
                                                            + ":"
                                                            + eventId.get()),
                                            MESSAGE_TYPE,
                                            uow -> effect(uow, subject, parsed.get(), judged));
                            // A delivery that parked value - or restated a parked execution
                            // - rests addressed to its parking (V023's fifth subject).
                            if (judged.parking().isPresent()) {
                                evidence.appendForUnmatched(
                                        unitOfWork, judged.parking().get(),
                                        EvidenceKind.WEBHOOK, rawBody, Instant.now(clock));
                            } else if (judged.echoedWithdrawal().isPresent()) {
                                // The echo rests addressed to what it echoes (the Phase 7 -> 8
                                // transition; it was kept unattributed though identified).
                                evidence.appendForWithdrawal(
                                        unitOfWork, judged.echoedWithdrawal().get(),
                                        EvidenceKind.WEBHOOK, rawBody, Instant.now(clock));
                            } else if (judged.echoedRefund().isPresent()) {
                                evidence.append(
                                        unitOfWork, Optional.empty(), judged.echoedRefund(),
                                        EvidenceKind.WEBHOOK, rawBody, Instant.now(clock));
                            } else {
                                retain(unitOfWork, subject.map(PaymentAttempt::id), rawBody);
                            }
                            return new Delivered(consumed, subject.isPresent());
                        });

        if (delivered.consumed() == InboxConsumer.Outcome.CONTENDED) {
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "An instant confirmation is being processed by another instance; asking"
                            + " the scheme to redeliver");
        }
        if (delivered.consumed() == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.DUPLICATE);
            log.info(
                    "A duplicate instant confirmation delivery was absorbed by the inbox;"
                            + " its bytes are retained as evidence (INV-IDEM-04,"
                            + " INV-HIST-02)");
        } else if ((delivered.attributed() || judged.parked() || judged.isEcho())
                && !judged.isUnmappable()) {
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.PROCESSED);
        } else {
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.UNMAPPABLE);
        }
        if (!delivered.attributed() && !judged.parked() && !judged.isEcho()) {
            log.warn(
                    "An authenticated instant confirmation named no initiation this platform"
                            + " made and carried no parkable value; retained unattributed and"
                            + " acknowledged (ADR-0047 §5)");
        }
    }

    /**
     * The delivery's state effect, inside the inbox's transaction, as the platform (the
     * module's enumerated {@code enterSystem()} site at this door): the payer PSP's word
     * onto the machine's conditional edges through the ONE shared component — or, for a
     * money-carrying statement naming nothing we minted, the suspense parking
     * ({@code INV-REC-05}: recorded, never guessed into a credit).
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private void effect(
            Connection uow,
            Optional<PaymentAttempt> subject,
            CallbackPayload payload,
            Judged judged) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            if (subject.isPresent()) {
                attemptEffect(uow, subject.get(), payload, judged);
                return;
            }
            // Unattributable. With an executed word, a usable scheme reference and usable
            // money, it PARKS - the books must show value the scheme says moved
            // (ADR-0062 §5); everything else is evidence alone.
            if (!"executed".equals(payload.status())) {
                return;
            }
            // THE RETURN'S OWN ECHO (P7-TSK-010): a return payment travels under a reference
            // WE minted onto the REFUND row, not the attempt - so its confirmation attributes
            // to no initiation and would otherwise park money the books already explain. A
            // reference a refund carries is attributed, not unmatched: evidence only, loud,
            // and the return's outcome lands through its own resolution sweep on the locked
            // refund row (never through this door's pay-in machine).
            Optional<com.finapp.payments.Refund> echoedRefund =
                    refundFor(uow, payload.reference());
            if (echoedRefund.isPresent()) {
                judged.echoOf(echoedRefund.get().id());
                log.info(
                        "An instant confirmation named a return payment's own reference; the"
                                + " suspense parking is refused and the bytes rest as evidence"
                                + " - the return sweep concludes the refund (P7-TSK-010)");
                return;
            }
            // THE WITHDRAWAL'S OWN ECHO (the Phase 7 -> 8 transition): a withdrawal travels
            // under a reference WE minted onto the WITHDRAWAL row, so its confirmation names no
            // initiation - and parking it would book value that went OUT as value that came
            // in (DR clearing / CR suspense). Evidence only; the withdrawal's own resolution
            // concludes it on its locked row.
            Optional<com.finapp.payments.Withdrawal> echoedWithdrawal =
                    withdrawalFor(uow, payload.reference());
            if (echoedWithdrawal.isPresent()) {
                judged.echoOf(echoedWithdrawal.get().id());
                log.info(
                        "An instant confirmation named a withdrawal's own reference; the"
                                + " suspense parking is refused and the bytes rest as evidence"
                                + " - the withdrawal's resolution concludes it (the Phase 7 ->"
                                + " 8 transition)");
                return;
            }
            Optional<ProviderReference> scheme = schemeReference(payload.schemeReference());
            Optional<Money> carried = money(payload);
            if (scheme.isEmpty() || carried.isEmpty()) {
                judged.unmappable();
                log.warn(
                        "An authenticated instant confirmation claimed an execution the"
                                + " platform cannot attribute, with no usable reference or"
                                + " amount; retained as evidence only (INV-PAY-03's"
                                + " totality)");
                return;
            }
            if (!unmatched.canPark(uow, railId, carried.get().currency())) {
                judged.unmappable();
                log.warn(
                        "An authenticated instant confirmation carried value in a currency this"
                                + " rail cannot park; retained as evidence and acknowledged -"
                                + " an integration break reconciliation must see (the anti-stall"
                                + " doctrine, ADR-0047 section 5)");
                return;
            }
            judged.parked(
                    unmatched.park(
                            uow,
                            new UnmatchedConfirmations.Parking(
                                    railId,
                                    scheme.get(),
                                    carried.get(),
                                    UnmatchedConfirmation.Attribution.unattributed(
                                            mintedShape(payload.reference()),
                                            settlementCycleOf(payload)),
                                    PaymentCreation.resolvedCorrelation())));
        }
    }

    private void attemptEffect(
            Connection uow, PaymentAttempt attempt, CallbackPayload payload, Judged judged) {
        Optional<PushInquiryAnswer.Verdict> verdict = mappedVerdict(payload);
        if (verdict.isEmpty()) {
            judged.unmappable();
            log.warn(
                    "An authenticated instant confirmation for attempt {} carried a status"
                            + " the total mapping refuses to act on; retained as evidence,"
                            + " nothing transitions (INV-PAY-03)",
                    attempt.id());
            return;
        }
        if (!pushResolvable(attempt.status())) {
            concludedAttemptEffect(uow, attempt, payload, verdict.get(), judged);
            return;
        }
        PaymentIntent intent =
                intents.findById(uow, attempt.intentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an attempt row's intent exists: V003's"
                                                        + " foreign key holds it"));
        // The executed amount is the APPLIER's judgement since the Phase 7 -> 8 transition -
        // one judgement for this door and the inquiry sweep, so a mismatch refused here can
        // never be credited at the initiation's ask two minutes later.
        Optional<Money> stated = money(payload);
        if (verdict.get() == PushInquiryAnswer.Verdict.ACCEPTED && stated.isEmpty()) {
            judged.unmappable();
        }
        PaymentOutcomes.Applied applied =
                outcomes.applyExecution(
                        uow,
                        intent.id(),
                        attempt.id(),
                        attempt.status(),
                        verdict.get(),
                        schemeReference(payload.schemeReference()),
                        settlementCycleOf(payload),
                        intent.creditAccount(),
                        intent.amount(),
                        stated,
                        PaymentCreation.resolvedCorrelation());
        // A mismatched or concluded execution parked inside the applier: the delivery's
        // bytes rest addressed to that parking (V023's fifth subject), never the attempt -
        // so the parking's owner traces to its raw statement (P8-TSK-020).
        applied.parking()
                .ifPresent(
                        parking ->
                                judged.parked(
                                        new UnmatchedConfirmations.Parked(
                                                false, Optional.of(parking))));
    }

    /**
     * A statement on an attempt that can no longer move (the Phase 7 -&gt; 8 transition). An
     * {@code executed} word with a usable scheme reference and amount is value that ARRIVED:
     * on an attempt executed under that same reference it is the rail repeating itself
     * (evidence); on a FAILED attempt, or an executed one under ANOTHER reference, it parks
     * ({@code ATTEMPT_CONCLUDED}, attributed to the attempt) — never dropped, never credited
     * into a concluded story. Anything else is evidence alone.
     */
    private void concludedAttemptEffect(
            Connection uow,
            PaymentAttempt attempt,
            CallbackPayload payload,
            PushInquiryAnswer.Verdict verdict,
            Judged judged) {
        Optional<ProviderReference> scheme = schemeReference(payload.schemeReference());
        Optional<Money> carried = money(payload);
        boolean repeated =
                attempt.status() == PaymentAttemptStatus.EXECUTED
                        && scheme.isPresent()
                        && attempt.schemeReference().equals(scheme);
        if (verdict != PushInquiryAnswer.Verdict.ACCEPTED || repeated) {
            log.info(
                    "An instant confirmation reported on attempt {} in state {} which cannot"
                            + " move; the statement stands as evidence and changes nothing"
                            + " (INV-LIFE-04)",
                    attempt.id(),
                    attempt.status());
            return;
        }
        if (carried.isEmpty() || !unmatched.canPark(uow, railId, carried.get().currency())) {
            judged.unmappable();
            log.warn(
                    "An instant confirmation claimed an execution on attempt {} in state {}"
                            + " with no usable, parkable amount; retained as evidence only",
                    attempt.id(),
                    attempt.status());
            return;
        }
        judged.parked(
                unmatched.park(
                        uow,
                        new UnmatchedConfirmations.Parking(
                                railId,
                                scheme.orElseThrow(),
                                carried.get(),
                                UnmatchedConfirmation.Attribution.of(
                                        UnmatchedConfirmation.Cause.ATTEMPT_CONCLUDED,
                                        attempt.id(),
                                        attempt.endToEndReference(),
                                        settlementCycleOf(payload)),
                                PaymentCreation.resolvedCorrelation())));
    }

    /**
     * What this delivery's own conditionals decided that the DOOR needs — the delivery's fate
     * and the parking its bytes rest addressed to. Every count is its writer's since the
     * Phase 7 -&gt; 8 transition: the execution's judgement the applier's (`P7-TSK-015`), the
     * parking the parking's, each through the {@code RailOutcomeObserver} after its commit.
     */
    private static final class Judged {

        private boolean parkedSeen;
        private Optional<java.util.UUID> parking = Optional.empty();
        private boolean unreadable;
        private boolean echo;
        private Optional<com.finapp.payments.WithdrawalId> echoedWithdrawal = Optional.empty();
        private Optional<com.finapp.payments.RefundId> echoedRefund = Optional.empty();

        void unmappable() {
            unreadable = true;
        }

        boolean isUnmappable() {
            return unreadable;
        }

        /** A reference of ours that no pay-in carries: a withdrawal's echo. */
        void echoOf(com.finapp.payments.WithdrawalId withdrawal) {
            echo = true;
            echoedWithdrawal = Optional.of(withdrawal);
        }

        /** A reference of ours that no pay-in carries: a return's echo (a refund row). */
        void echoOf(com.finapp.payments.RefundId refund) {
            echo = true;
            echoedRefund = Optional.of(refund);
        }

        Optional<com.finapp.payments.WithdrawalId> echoedWithdrawal() {
            return echoedWithdrawal;
        }

        Optional<com.finapp.payments.RefundId> echoedRefund() {
            return echoedRefund;
        }

        boolean isEcho() {
            return echo;
        }

        /** The parking reports its own acting count (the observer); the door keeps the fate. */
        void parked(UnmatchedConfirmations.Parked parked) {
            parkedSeen = true;
            parking = parked.parking();
        }

        boolean parked() {
            return parkedSeen;
        }

        /** The parking this delivery's value rests in, when it rests in one. */
        Optional<java.util.UUID> parking() {
            return parking;
        }
    }

    /** The push model's resolvable sources at this door — waiting, or an outbound unknown. */
    private static boolean pushResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.AWAITING_PAYER
                || status == PaymentAttemptStatus.EXECUTION_DISPATCHED
                || status == PaymentAttemptStatus.EXECUTION_UNKNOWN;
    }

    /**
     * The total mapping ({@code INV-PAY-03}): the payer PSP's three words act — an
     * execution only with a scheme reference the row can store — and everything else is
     * empty, retained without transitioning. {@code expired} maps to the rejection: the
     * payer's window closed, nothing moved, and the scheme's own word rests in the
     * evidence where an investigation wants it.
     */
    private static Optional<PushInquiryAnswer.Verdict> mappedVerdict(CallbackPayload payload) {
        return switch (payload.status() == null ? "" : payload.status()) {
            case "executed" ->
                    schemeReference(payload.schemeReference()).isPresent()
                            ? Optional.of(PushInquiryAnswer.Verdict.ACCEPTED)
                            : Optional.empty();
            case "rejected", "expired" -> Optional.of(PushInquiryAnswer.Verdict.REJECTED);
            default -> Optional.empty();
        };
    }

    /** Shape-total: the refund carrying this reference as its own, or absent — never a throw. */
    private Optional<com.finapp.payments.Refund> refundFor(Connection uow, String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        try {
            return refunds.findByOperationReference(
                    uow, new com.finapp.payments.ProviderIdempotencyReference(reference));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    /** Shape-total: the withdrawal carrying this reference as its own, or absent. */
    private Optional<com.finapp.payments.Withdrawal> withdrawalFor(
            Connection uow, String reference) {
        return mintedShape(reference)
                .flatMap(ours -> withdrawals.findByEndToEndReference(uow, ours));
    }

    /** Shape-total: a cycle the row cannot store is absent, never a throw. */
    private static Optional<String> settlementCycleOf(CallbackPayload payload) {
        return Optional.ofNullable(payload.settlementCycle())
                .filter(cycle -> !cycle.isBlank()
                        && cycle.length() <= com.finapp.payments.PushAnswer.MAX_CYCLE_LENGTH);
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

    /** Shape-total money: unusable amount or currency is absent, never a throw. */
    private static Optional<Money> money(CallbackPayload payload) {
        if (payload.amount() == null || payload.currency() == null) {
            return Optional.empty();
        }
        try {
            Money money =
                    Money.of(new BigDecimal(payload.amount()), CurrencyCode.of(payload.currency()));
            // Zero or negative is no executed value (the Phase 7 -> 8 transition): it threw
            // inside the parking and rolled the delivery's evidence back.
            return money.isPositive() ? Optional.of(money) : Optional.empty();
        } catch (RuntimeException unusable) {
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

    /** Evidence, attributed when the reference resolved ({@code V005}'s recorded rule). */
    private void retain(
            Connection unitOfWork,
            Optional<com.finapp.payments.PaymentAttemptId> attempt,
            byte[] rawBody) {
        evidence.append(
                unitOfWork, attempt, Optional.empty(), EvidenceKind.WEBHOOK, rawBody,
                Instant.now(clock));
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

    /** The delivery transaction's yield: the dedupe outcome, and whether a row owned it. */
    private record Delivered(InboxConsumer.Outcome consumed, boolean attributed) {}

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
