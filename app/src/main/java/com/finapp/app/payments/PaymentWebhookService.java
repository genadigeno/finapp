package com.finapp.app.payments;

import com.finapp.payments.EvidenceKind;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentOutcomes;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PaymentCreation;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.WebhookSignature;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * One payment webhook, delivered at-least-once, recorded exactly once (`P5-TSK-012`,
 * ADR-0047 §1–§3 and §5; the {@code ProviderCallbackService} template with the payments
 * additions).
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
 * of the inbox's block-don't-skip rule for internal events. (The unmappable-rate meter is
 * plan §15's, arriving with `P5-TSK-017`.)
 *
 * <h2>This task transitions nothing</h2>
 *
 * <p>The inbox handler is the seam `P5-TSK-013` fills with the conditional machine
 * transitions (ADR-0047 §4); here it carries the dedupe record alone, which is exactly the
 * scope boundary the backlog draws — ingestion, not effect.
 */
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

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

    private final WebhookSignature signature;
    private final ProviderEvidenceStore<Connection> evidence;
    private final PaymentAttemptStore<Connection> attempts;
    private final PaymentIntentStore<Connection> intents;
    private final RefundStore<Connection> refunds;
    private final PaymentOutcomes outcomes;
    private final InboxConsumer<Connection> inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PaymentWebhookService(
            WebhookSignature webhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
            PaymentIntentStore<Connection> paymentIntentStore,
            RefundStore<Connection> refundStore,
            PaymentOutcomes paymentOutcomes,
            InboxConsumer<Connection> inboxConsumer,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate paymentTransactions,
            DataSource dataSource) {
        this.signature =
                Objects.requireNonNull(webhookSignature, "webhookSignature must not be null");
        this.evidence =
                Objects.requireNonNull(
                        providerEvidenceStore, "providerEvidenceStore must not be null");
        this.attempts =
                Objects.requireNonNull(paymentAttemptStore, "paymentAttemptStore must not be null");
        this.intents =
                Objects.requireNonNull(paymentIntentStore, "paymentIntentStore must not be null");
        this.refunds = Objects.requireNonNull(refundStore, "refundStore must not be null");
        this.outcomes = Objects.requireNonNull(paymentOutcomes, "paymentOutcomes must not be null");
        this.inbox = Objects.requireNonNull(inboxConsumer, "inboxConsumer must not be null");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(paymentTransactions, "paymentTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * The provider's wire shape — ours end to end, like the outbound protocol it mirrors
     * (`P5-TSK-003`): the provider's event id, <strong>our</strong> operation reference
     * (reconciliation by our reference, ADR-0046 §4 applied inbound), and the provider's
     * status word — untouched here; `P5-TSK-013`'s total mapping owns it ({@code INV-PAY-03}).
     */
    private record WebhookPayload(
            String eventId, String operation, String status, String reference) {}

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
            // is written (INV-PAY-01): an unauthenticated stranger grows no table.
            throw new ApiException(
                    PlatformErrorCode.UNAUTHENTICATED,
                    "A payment webhook failed signature or freshness verification");
        }
        if (rawBody.length == 0 || rawBody.length > ProviderEvidenceStore.MAX_PAYLOAD_BYTES) {
            // The evidence bound, enforced where the caller is told rather than discovered at
            // the insert (the PspWireClient reasoning, inbound).
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
            inOneTransaction(
                    unitOfWork -> {
                        retain(unitOfWork, Optional.empty(), Optional.empty(), rawBody);
                        return null;
                    });
            log.warn(
                    "An authenticated payment webhook was unparseable or carried no usable"
                            + " event id; its bytes are retained as evidence and it is"
                            + " acknowledged (ADR-0047 §5)");
            return;
        }

        Optional<ProviderIdempotencyReference> operation =
                parsed.flatMap(payload -> mintedShape(payload.operation()));
        Delivered delivered =
                inOneTransaction(
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
                            // distinct - an attempt reference never names a refund.
                            Optional<Refund> refundSubject =
                                    subject.isPresent()
                                            ? Optional.empty()
                                            : operation.flatMap(
                                                    reference ->
                                                            refunds.findByOperationReference(
                                                                    unitOfWork, reference));
                            // THE EFFECT BEFORE THE EVIDENCE ROW, deliberately - a lock-order
                            // rule, not a priority statement: the evidence INSERT takes FOR
                            // KEY SHARE on the attempt row (the FK), and the outcome's UPDATE
                            // rewrites a UNIQUE column, which needs the full FOR UPDATE that
                            // KEY SHARE blocks - two deliveries retaining first then applying
                            // deadlock each other (40P01, found by this suite's ten-way race).
                            // "Evidence first" (ADR-0047 §2) is a COMMIT claim and stands:
                            // evidence, dedupe and effect still commit together, and 2xx
                            // still waits for that commit.
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(
                                                    CONSUMER,
                                                    SimulatedCardPspAdapter.NAME
                                                            + ":"
                                                            + eventId.get()),
                                            MESSAGE_TYPE,
                                            uow ->
                                                    // The P5-TSK-013 effect: the conditional
                                                    // machine transitions, with the dedupe
                                                    // record, or not at all (ADR-0047 §3-§4).
                                                    effect(
                                                            uow,
                                                            subject,
                                                            refundSubject,
                                                            operation,
                                                            parsed.get()));
                            retain(unitOfWork,
                                    subject.map(PaymentAttempt::id),
                                    refundSubject.map(Refund::id),
                                    rawBody);
                            return new Delivered(
                                    consumed,
                                    subject.isPresent() || refundSubject.isPresent());
                        });

        if (delivered.consumed() == InboxConsumer.Outcome.CONTENDED) {
            // The inbox's own contract: the other transaction may yet roll back, so this
            // delivery must NOT be acknowledged. The rollback took this delivery's evidence
            // row with it; the redelivery retains it.
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "A payment webhook is being processed by another instance; asking the"
                            + " provider to redeliver");
        }
        if (!delivered.attributed()) {
            // Authentic but unmappable: acknowledged with the evidence retained. The rate is
            // plan §15's webhook meter (P5-TSK-017); until then this line is the alert's raw
            // material. Identifiers only.
            log.warn(
                    "An authenticated payment webhook named no operation this platform minted;"
                            + " retained unattributed and acknowledged (ADR-0047 §5)");
        }
        if (delivered.consumed() == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            log.info(
                    "A duplicate payment webhook delivery was absorbed by the inbox; its bytes"
                            + " are retained as evidence (INV-IDEM-04, INV-HIST-02)");
        }
    }

    /**
     * The webhook's state effect (`P5-TSK-013`, ADR-0047 §4): the statement mapped through
     * the total vocabulary onto a conditional edge of the attempt machine, applied through
     * the ONE shared outcome component — the same transaction the sync response and the
     * sweeper use, from this attempt's own current source state.
     *
     * <p><strong>As the platform</strong> — the module's second enumerated
     * {@code enterSystem()} site: a provider's unsolicited statement has no session, and the
     * same outcome applied by the sweeper has no person at all, so attribution must not
     * depend on which resolver wins the harmless race (the `P5-TSK-009` reasoning, third
     * occurrence).
     *
     * <p><strong>Refused edges are evidence, never errors</strong> ({@code INV-LIFE-04}): a
     * late report on a terminal attempt, an out-of-order authorization report on a captured
     * one, an unrecognised status word, an approval missing the reference the row must store
     * — each changes nothing, logs identifiers, and the retained bytes (already committed in
     * this same transaction) are the statement of record. Unlike the synchronous call —
     * whose ENDING without knowledge is itself the fact {@code *_UNKNOWN} records — an
     * unsolicited statement we cannot read resolves nothing (the recorded decision).
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private void effect(
            Connection uow,
            Optional<PaymentAttempt> attemptSubject,
            Optional<Refund> refundSubject,
            Optional<ProviderIdempotencyReference> operation,
            WebhookPayload payload) {
        if (attemptSubject.isEmpty() && refundSubject.isEmpty()) {
            return;
        }
        // As the platform - the module's enumerated enterSystem() site, held HERE so the
        // attempt branch and the refund branch (P5-TSK-016) are one site, not two: a
        // provider's unsolicited statement has no session whichever machine it names.
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            if (attemptSubject.isPresent()) {
                attemptEffect(uow, attemptSubject.get(), operation.orElseThrow(), payload);
            } else {
                refundEffect(uow, refundSubject.get(), payload);
            }
        }
    }

    private void attemptEffect(
            Connection uow,
            PaymentAttempt attempt,
            ProviderIdempotencyReference operation,
            WebhookPayload payload) {
        Optional<ProviderAnswer.Verdict> verdict = mappedVerdict(payload);
        if (verdict.isEmpty()) {
            log.warn(
                    "An authenticated payment webhook for attempt {} carried a status the"
                            + " total mapping refuses to act on; retained as evidence,"
                            + " nothing transitions (INV-PAY-03)",
                    attempt.id());
            return;
        }
        boolean authOperation = operation.equals(attempt.authorizationReference());
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        {
            PaymentIntent intent =
                    intents.findById(uow, attempt.intentId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "an attempt row's intent exists: V003's"
                                                            + " foreign key holds it"));
            if (authOperation && authResolvable(attempt.status())) {
                outcomes.applyAuthorization(
                        uow,
                        intent.id(),
                        attempt.id(),
                        attempt.status(),
                        verdict.get(),
                        providerReference(payload),
                        // The issuer approved the dispatched ask: the intent's amount, the
                        // same promise the synchronous path carries from its Tx1.
                        intent.amount(),
                        correlation);
            } else if (!authOperation && captureResolvable(attempt.status())) {
                outcomes.applyCapture(
                        uow,
                        intent.id(),
                        attempt.id(),
                        attempt.status(),
                        verdict.get(),
                        providerReference(payload),
                        intent.walletAccount(),
                        // The capture is the authorized promise, in full (one attempt, no
                        // partial capture until its producer exists - ADR-0045 §4).
                        attempt.authorizedAmount(),
                        correlation);
            } else {
                log.info(
                        "A payment webhook reported on attempt {} in state {} which its"
                                + " operation cannot move; the statement stands as evidence"
                                + " and changes nothing (INV-LIFE-04)",
                        attempt.id(),
                        attempt.status());
            }
        }
    }

    /**
     * The refund's webhook effect (`P5-TSK-016`): the asynchronous completion a real PSP
     * actually sends — the statement mapped through the same total vocabulary onto the
     * refund machine's conditional edges, applied through the ONE shared outcome component,
     * from this refund's own current source state ({@code DISPATCHED} or {@code UNKNOWN} —
     * the order-blindness). Completion releases-and-posts atomically; a late report on a
     * terminal refund is evidence beside an untouched row ({@code INV-LIFE-04}). Runs inside
     * the caller's platform scope — one enumerated site, whichever machine the statement
     * names.
     */
    private void refundEffect(Connection uow, Refund refund, WebhookPayload payload) {
        Optional<ProviderAnswer.Verdict> verdict = mappedVerdict(payload);
        if (verdict.isEmpty()) {
            log.warn(
                    "An authenticated payment webhook for refund {} carried a status the"
                            + " total mapping refuses to act on; retained as evidence,"
                            + " nothing transitions (INV-PAY-03)",
                    refund.id());
            return;
        }
        if (!refundResolvable(refund.status())) {
            log.info(
                    "A payment webhook reported on refund {} in state {} which cannot move;"
                            + " the statement stands as evidence and changes nothing"
                            + " (INV-LIFE-04)",
                    refund.id(),
                    refund.status());
            return;
        }
        PaymentAttempt attempt =
                attempts.findById(uow, refund.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund row's attempt exists: V004's"
                                                        + " foreign key holds it"));
        PaymentIntent intent =
                intents.findById(uow, attempt.intentId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an attempt row's intent exists: V003's"
                                                        + " foreign key holds it"));
        outcomes.applyRefund(
                uow,
                intent.id(),
                refund,
                refund.status(),
                verdict.get(),
                providerReference(payload),
                intent.walletAccount(),
                PaymentCreation.resolvedCorrelation());
    }

    private static boolean refundResolvable(RefundStatus status) {
        return status == RefundStatus.DISPATCHED || status == RefundStatus.UNKNOWN;
    }

    /**
     * The total mapping ({@code INV-PAY-03}): the provider's own two words act; everything
     * else — unrecognised states, an approval without a usable reference — is empty, and the
     * caller retains without transitioning. The vocabulary is the wire client's exactly:
     * this is the same provider speaking.
     */
    private static Optional<ProviderAnswer.Verdict> mappedVerdict(WebhookPayload payload) {
        return switch (payload.status() == null ? "" : payload.status()) {
            case "approved" ->
                    providerReference(payload).isPresent()
                            ? Optional.of(ProviderAnswer.Verdict.APPROVED)
                            // An approval the row cannot store is unactionable - not
                            // knowledge (the PspWireClient totality rule, inbound).
                            : Optional.empty();
            case "declined" -> Optional.of(ProviderAnswer.Verdict.DECLINED);
            default -> Optional.empty();
        };
    }

    /** Shape-total: a reference we cannot store is absent, never a throw. */
    private static Optional<ProviderReference> providerReference(WebhookPayload payload) {
        if (payload.reference() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ProviderReference(payload.reference()));
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    private static boolean authResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.AUTH_DISPATCHED
                || status == PaymentAttemptStatus.AUTH_UNKNOWN;
    }

    private static boolean captureResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.CAPTURE_DISPATCHED
                || status == PaymentAttemptStatus.CAPTURE_UNKNOWN;
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

    /** The delivery transaction's yield: the dedupe outcome, and whether anything owned it. */
    private record Delivered(InboxConsumer.Outcome consumed, boolean attributed) {}

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
