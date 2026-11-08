package com.finapp.app.payments;

import com.finapp.payments.CallbackHint;
import com.finapp.payments.CallbackHintResolution;
import com.finapp.payments.CallbackHintStore;
import com.finapp.payments.CardCallbackInquiry;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.HintedOutcome;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WebhookSignature;
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
 * One payment webhook, delivered at-least-once, recorded exactly once, and <strong>adopted never</strong>
 * (`P5-TSK-012`, ADR-0047 §1–§3 and §5; since `P15-TSK-036`, ADR-0083 decision 4: a callback is a hint).
 *
 * <h2>Authenticate before parsing, with a freshness window ({@code INV-PAY-01})</h2>
 *
 * <p>The HMAC over {@code timestamp + "." + raw bytes} is verified <strong>before anything
 * else</strong> — before parsing, before any read, before any write — so an unauthenticated
 * stranger, and a replayer outside the window, can grow no table and learn nothing but one
 * uniform 401. The timestamp is inside the signed payload, so it cannot be refreshed without
 * the key.
 *
 * <h2>Evidence first, always ({@code INV-HIST-02}); the anti-stall inversion (§5)</h2>
 *
 * <p>Every <em>authenticated</em> delivery retains its bytes verbatim — the duplicate and the
 * unmappable included, each a genuine provider statement Phase 8 wants — in the same
 * transaction as the dedupe record, and 2xx only after that commit. An authentic webhook that
 * is unparseable, carries no usable event id, or names no operation we minted is
 * <strong>acknowledged with its evidence retained</strong>: redelivery adds nothing when the
 * bytes are already held, and the evidence row IS the detection — deliberately the opposite
 * of the inbox's block-don't-skip rule for internal events.
 *
 * <h2>The callback decides when; only the inquiry decides what</h2>
 *
 * <p>The webhook key proves who signed, not that the claim is true, and it sits on a third
 * party's infrastructure. So nothing here reads an outcome out of the body: the body names the
 * subject and the kind, the evidence and the dedupe commit, and then, holding no connection,
 * {@link CardCallbackInquiry} asks the PSP over the provider credential and applies what the
 * PSP answers through the sweep's own appliers. A stolen webhook key hastens what the platform
 * learns and moves nothing. A duplicate never reaches the inquiry. An inquiry that fails or
 * answers nothing usable adopts nothing: the delivery is still acknowledged, and the sweep
 * decides (the row's own for an attempt or a refund, {@link CallbackHintResolution} for a
 * clearing or a dispute, which have no row to sweep).
 */
@Slf4j
public class PaymentWebhookService {

    static final String CONSUMER = "payments.provider-webhook";
    static final String MESSAGE_TYPE = "payments.ProviderWebhook";

    /**
     * The {@code ProviderCallbackService} charset, for the same reason: the event id is a
     * caller-supplied value bound for a durable column ({@code inbox_message.dedupe_key}) and
     * for log lines. A shape outside it is not a refusal here, though — it folds into the
     * anti-stall class (evidence retained, acknowledged), because a provider whose event ids
     * we cannot key is an integration break to alert on, not a queue to stall.
     */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:@/+=-]+");

    private final WebhookSignature signature;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentAttemptStore<Connection> attempts;
    private final RefundStore<Connection> refunds;
    private final com.finapp.app.telemetry.PaymentMeters meters;
    private final InboxConsumer<Connection> inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final CardCallbackInquiry inquiry;
    private final CallbackHintStore<Connection> hints;
    private final CallbackHintResolution hintResolution;

    /** The rail this door serves: the composition root's binding, never a name held here ({@code INV-RAIL-01}). */
    private final RailId railId;

    /** At most so many inquiries at once on this instance's request threads; a refused one is left to the sweep. */
    private final CallbackInquiryBulkhead bulkhead;

    public PaymentWebhookService(
            WebhookSignature webhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            RefundStore<Connection> refundStore,
            com.finapp.app.telemetry.PaymentMeters paymentMeters,
            InboxConsumer<Connection> inboxConsumer,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionRunner paymentTransactionRunner,
            CardCallbackInquiry cardCallbackInquiry,
            CallbackHintStore<Connection> callbackHintStore,
            CallbackHintResolution callbackHintResolution,
            RailId railId,
            CallbackInquiryBulkhead callbackInquiryBulkhead) {
        this.signature =
                Objects.requireNonNull(webhookSignature, "webhookSignature must not be null");
        this.evidence =
                Objects.requireNonNull(
                        providerEvidenceStore, "providerEvidenceStore must not be null");
        this.attempts =
                Objects.requireNonNull(paymentAttemptStore, "paymentAttemptStore must not be null");
        this.refunds = Objects.requireNonNull(refundStore, "refundStore must not be null");
        this.meters = Objects.requireNonNull(paymentMeters, "paymentMeters must not be null");
        this.inbox = Objects.requireNonNull(inboxConsumer, "inboxConsumer must not be null");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(
                        paymentTransactionRunner, "paymentTransactionRunner must not be null");
        this.inquiry =
                Objects.requireNonNull(cardCallbackInquiry, "cardCallbackInquiry must not be null");
        this.hints = Objects.requireNonNull(callbackHintStore, "callbackHintStore must not be null");
        this.hintResolution =
                Objects.requireNonNull(
                        callbackHintResolution, "callbackHintResolution must not be null");
        this.railId = Objects.requireNonNull(railId, "railId must not be null");
        this.bulkhead =
                Objects.requireNonNull(
                        callbackInquiryBulkhead, "callbackInquiryBulkhead must not be null");
    }

    /**
     * The provider's wire shape — ours end to end, like the outbound protocol it mirrors
     * (`P5-TSK-003`). This door reads the provider's event id (the inbox key), <strong>our</strong>
     * operation reference and, for a dispute, the PSP's dispute reference (each the subject of an
     * inquiry), and the status word (which inquiry to make). The rest of the body rests as evidence
     * and is read by nobody here.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record WebhookPayload(
            String eventId,
            String operation,
            String status,
            String reference,
            String arn,
            String networkTransactionId,
            String dispute) {}

    /** What this delivery asks about, decided in its transaction and acted on after the commit. */
    private sealed interface Planned {

        /** The authorization, capture or void the operation reference names on this attempt. */
        record Operation(PaymentAttemptId attempt, ProviderIdempotencyReference reference) implements Planned {}

        /** The refund the operation reference names. */
        record RefundOutcome(Refund refund) implements Planned {}

        /** A clearing or dispute notice: no row to sweep, so a durable hint carries it. */
        record Durable(CallbackHint.Kind kind, PaymentAttemptId attempt, String askedAbout) implements Planned {}

        /** Nothing to ask; {@code unreadable} when the statement named a row but could not be read. */
        record Nothing(boolean unreadable) implements Planned {}
    }

    /**
     * Accepts one delivery.
     *
     * @throws ApiException 401 for every unauthenticated or unfresh shape (one refusal —
     *     which way a forgery failed is not information to hand out), 413 for a body outside
     *     the evidence bound, 409 for a contended dedupe record (unacknowledged, so the
     *     provider redelivers — the inbox's own contract); never a 500 for a caller's shape
     */
    public void deliver(byte[] rawBody, String presentedTimestamp, String presentedSignature) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (!signature.matches(presentedTimestamp, rawBody, presentedSignature)) {
            // Missing, malformed, wrong, stale and future-skewed are ONE refusal, and nothing
            // is written (INV-PAY-01): an unauthenticated stranger grows no table. The METER
            // may count what the byte-identical 401 hides (`P1-TSK-029`: a meter is invisible
            // to the caller): a rise here is somebody probing the door.
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.REFUSED);
            throw new ApiException(
                    PlatformErrorCode.UNAUTHENTICATED,
                    "A payment webhook failed signature or freshness verification");
        }
        if (rawBody.length == 0 || rawBody.length > ProviderEvidenceStore.MAX_PAYLOAD_BYTES) {
            // The evidence bound, enforced where the caller is told rather than discovered at
            // the insert (the PspWireClient reasoning, inbound).
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.REFUSED);
            throw new ApiException(
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "A payment webhook body was empty or exceeded the evidence bound");
        }

        // Authenticated from here on: every path below retains the bytes (INV-HIST-02) and
        // acknowledges - the anti-stall inversion (ADR-0047 §5).
        Optional<WebhookPayload> parsed = parse(rawBody);
        Optional<String> eventId = parsed.flatMap(payload -> usableEventId(payload.eventId()));
        if (eventId.isEmpty()) {
            // Unparseable, or no event id we can key: no dedupe is POSSIBLE, so the evidence
            // row alone is the record - per delivery, which is bounded because we acknowledge
            // and a correct provider then never redelivers.
            transactions.inTransaction(
                    unitOfWork -> {
                        retain(unitOfWork, Optional.empty(), Optional.empty(), rawBody);
                        return null;
                    });
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.UNMAPPABLE);
            log.warn(
                    "An authenticated payment webhook was unparseable or carried no usable"
                            + " event id; its bytes are retained as evidence and it is"
                            + " acknowledged (ADR-0047 §5)");
            return;
        }

        Optional<ProviderIdempotencyReference> operation =
                parsed.flatMap(payload -> mintedShape(payload.operation()));
        Delivered delivered =
                transactions.inTransaction(
                        unitOfWork -> {
                            // The attribution read and the evidence row share the delivery's
                            // transaction: evidence for EVERY authenticated delivery - the
                            // duplicate's statement is as genuine as the first's (ADR-0047 §2).
                            Optional<PaymentAttempt> subject =
                                    operation.flatMap(
                                            reference ->
                                                    attempts.findByOperationReference(
                                                            unitOfWork, reference));
                            // The refund's statements attribute by the same INV-PAY-04 rule
                            // (P5-TSK-016): tried second because the vocabularies are
                            // distinct - an attempt reference never names a refund. And only a
                            // refund of THIS door's rail (P15-TSK-036, Phase 7 residues #34 and
                            // #75): a card callback naming an instant return's reference names
                            // nothing this provider did.
                            Optional<Refund> refundSubject =
                                    subject.isPresent()
                                            ? Optional.empty()
                                            : operation.flatMap(
                                                            reference ->
                                                                    refunds.findByOperationReference(
                                                                            unitOfWork, reference))
                                                    .filter(refund -> onThisRail(unitOfWork, refund));
                            Planned planned = plan(subject, refundSubject, operation, parsed.get());
                            CallbackHint[] hint = new CallbackHint[1];
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(
                                                    CONSUMER,
                                                    SimulatedCardPspAdapter.NAME
                                                            + ":"
                                                            + eventId.get()),
                                            MESSAGE_TYPE,
                                            // The dedupe record, and for a notice no row can stand for
                                            // the hint that lets the sweep finish it: nothing else. The
                                            // claim in the body is never an effect (ADR-0083 decision 2).
                                            uow -> {
                                                if (planned instanceof Planned.Durable durable) {
                                                    hint[0] =
                                                            hints.insert(
                                                                            uow,
                                                                            durable.kind(),
                                                                            Optional.of(durable.attempt()),
                                                                            durable.askedAbout(),
                                                                            hintResolution.firstAsk())
                                                                    .orElse(null);
                                                }
                                            });
                            retain(unitOfWork,
                                    subject.map(PaymentAttempt::id),
                                    refundSubject.map(Refund::id),
                                    rawBody);
                            return new Delivered(
                                    consumed,
                                    subject.isPresent() || refundSubject.isPresent(),
                                    planned,
                                    Optional.ofNullable(hint[0]));
                        });

        if (delivered.consumed() == InboxConsumer.Outcome.CONTENDED) {
            // The inbox's own contract: the other transaction may yet roll back, so this
            // delivery must NOT be acknowledged. Its evidence row DID commit - the delivery
            // transaction returned normally with CONTENDED, and every authenticated delivery
            // is retained (INV-HIST-02) - so the redelivery adds a second row for the same
            // bytes.
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "A payment webhook is being processed by another instance; asking the"
                            + " provider to redeliver");
        }
        if (delivered.consumed() == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            // No inquiry: the cost of a callback is bounded by this dedupe (ADR-0083 decision 5).
            meters.webhook(com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.DUPLICATE);
            log.info(
                    "A duplicate payment webhook delivery was absorbed by the inbox; its bytes"
                            + " are retained as evidence (INV-IDEM-04, INV-HIST-02)");
            return;
        }

        HintedOutcome outcome = effect(delivered.planned(), delivered.hint());
        boolean unreadable = delivered.planned() instanceof Planned.Nothing nothing && nothing.unreadable();
        // Counted after the commit (`P5-TSK-017`). An inquiry that gave no answer is still a delivery
        // handled: the sweep decides. A contradiction or a refutation is the integration break this
        // series exists to make visible, and a refutation is what a stolen webhook key looks like.
        meters.webhook(
                delivered.attributed() && !unreadable && !outcome.isBreak()
                        ? com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.PROCESSED
                        : com.finapp.app.telemetry.PaymentMeters.WebhookOutcome.UNMAPPABLE);

        if (!delivered.attributed()) {
            // Authentic but unmappable: acknowledged with the evidence retained. Identifiers only.
            log.warn(
                    "An authenticated payment webhook named no operation this platform minted"
                            + " on this rail; retained unattributed and acknowledged (ADR-0047 §5)");
        }
    }

    /**
     * Which inquiry this statement calls for. Reads the status word only to choose the question: the
     * answer alone moves anything. An unreadable word, or a notice missing what it would be asked
     * about, asks nothing and is counted as the integration break it is.
     */
    private static Planned plan(
            Optional<PaymentAttempt> attempt,
            Optional<Refund> refund,
            Optional<ProviderIdempotencyReference> operation,
            WebhookPayload payload) {
        if (refund.isPresent()) {
            return outcomeWord(payload)
                    ? new Planned.RefundOutcome(refund.get())
                    : new Planned.Nothing(true);
        }
        if (attempt.isEmpty()) {
            return new Planned.Nothing(false);
        }
        PaymentAttemptId id = attempt.get().id();
        String status = payload.status() == null ? "" : payload.status();
        if ("cleared".equals(status)) {
            return networkReference(payload.arn()).isPresent()
                            && networkReference(payload.networkTransactionId()).isPresent()
                    ? new Planned.Durable(CallbackHint.Kind.CARD_CLEARING, id, operation.orElseThrow().value())
                    : new Planned.Nothing(true);
        }
        if ("disputed".equals(status)) {
            return networkReference(payload.dispute())
                    .<Planned>map(
                            dispute ->
                                    new Planned.Durable(
                                            CallbackHint.Kind.CARD_DISPUTE, id, dispute.value()))
                    .orElseGet(() -> new Planned.Nothing(true));
        }
        return outcomeWord(payload)
                ? new Planned.Operation(id, operation.orElseThrow())
                : new Planned.Nothing(true);
    }

    /**
     * The inquiry, after the commit and holding no connection, <strong>as the platform</strong> — the
     * module's enumerated {@code enterSystem()} site: a provider's unsolicited statement has no session,
     * and the same outcome applied by the sweeper has no person at all, so attribution must not depend
     * on which resolver wins the harmless race.
     *
     * <p>Never throws: a failed inquiry is logged and left to the sweep, because the evidence and the
     * dedupe are committed and the provider is owed its acknowledgement.
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
                case Planned.Operation named -> inquiry.operation(named.attempt(), named.reference());
                case Planned.RefundOutcome named -> inquiry.refund(named.refund());
                // No hint means the same question already stands open: its next ask answers this delivery too.
                case Planned.Durable durable -> hint.map(hintResolution::resolve).orElse(HintedOutcome.UNDECIDED);
                case Planned.Nothing nothing -> HintedOutcome.UNDECIDED;
            };
        } catch (RuntimeException failure) {
            // The class only: a provider or JDBC message can name hosts and identifiers (INV-AUD-02).
            log.warn(
                    "The inquiry a payment webhook triggered failed: {}; the sweep decides",
                    failure.getClass().getSimpleName());
            return HintedOutcome.UNDECIDED;
        } finally {
            bulkhead.exit();
        }
    }

    /** Whether this refund is on the rail this door serves. */
    private boolean onThisRail(Connection unitOfWork, Refund refund) {
        return attempts.findById(unitOfWork, refund.attemptId())
                .filter(attempt -> attempt.rail().equals(railId))
                .isPresent();
    }

    private static Optional<ProviderReference> networkReference(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProviderReference(value));
        } catch (IllegalArgumentException outOfShape) {
            return Optional.empty();
        }
    }

    /**
     * The provider's own two words, and an approval with a reference the row could store: anything
     * else is not a statement of an outcome and asks nothing. The inquiry's reference is the one
     * adopted; this one only decides whether the statement was readable.
     */
    private static boolean outcomeWord(WebhookPayload payload) {
        return switch (payload.status() == null ? "" : payload.status()) {
            case "approved" -> networkReference(payload.reference()).isPresent();
            case "declined" -> true;
            default -> false;
        };
    }

    /** Evidence, attributed when the operation resolved ({@code V005}'s recorded rule). */
    private void retain(
            Connection unitOfWork,
            Optional<PaymentAttemptId> attempt,
            Optional<RefundId> refund,
            byte[] rawBody) {
        evidence.append(
                unitOfWork,
                attempt,
                refund,
                EvidenceKind.WEBHOOK,
                rawBody,
                Instant.now(clock));
    }

    /** Total: unparseable is empty, never a throw — the anti-stall class, not a refusal. */
    private Optional<WebhookPayload> parse(byte[] rawBody) {
        try {
            return Optional.of(json.readValue(rawBody, WebhookPayload.class));
        } catch (tools.jackson.core.JacksonException unparseable) {
            // Never the parser's message: it echoes the caller's bytes.
            return Optional.empty();
        }
    }

    private static Optional<String> usableEventId(String eventId) {
        if (eventId == null
                || eventId.isBlank()
                || eventId.length() > InboxKey.MAX_LENGTH - SimulatedCardPspAdapter.NAME.length() - 1
                || !EVENT_ID.matcher(eventId).matches()) {
            return Optional.empty();
        }
        return Optional.of(eventId);
    }

    /** The delivery transaction's yield: the dedupe outcome, whether anything owned it, and what to ask. */
    private record Delivered(
            InboxConsumer.Outcome consumed,
            boolean attributed,
            Planned planned,
            Optional<CallbackHint> hint) {}

    /** Shape only, no read: a reference we never mint names nothing — the unattributable class. */
    private static Optional<ProviderIdempotencyReference> mintedShape(String operation) {
        if (operation == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProviderIdempotencyReference(operation));
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
    }
}
