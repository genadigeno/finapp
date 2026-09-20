package com.finapp.app.payments;

import com.finapp.payments.EvidenceKind;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.RefundId;
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
    private final InboxConsumer<Connection> inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PaymentWebhookService(
            WebhookSignature webhookSignature,
            ProviderEvidenceStore<Connection> providerEvidenceStore,
            PaymentAttemptStore<Connection> paymentAttemptStore,
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
    private record WebhookPayload(String eventId, String operation, String status) {}

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
                        retain(unitOfWork, Optional.empty(), rawBody);
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
                            Optional<PaymentAttemptId> subject =
                                    operation.flatMap(
                                            reference ->
                                                    attempts.findByOperationReference(
                                                                    unitOfWork, reference)
                                                            .map(PaymentAttempt::id));
                            retain(unitOfWork, subject, rawBody);
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(
                                                    CONSUMER,
                                                    SimulatedCardPspAdapter.NAME
                                                            + ":"
                                                            + eventId.get()),
                                            MESSAGE_TYPE,
                                            uow -> {
                                                // The P5-TSK-013 seam: the conditional machine
                                                // transitions run exactly here, with the
                                                // dedupe record, or not at all. Ingestion
                                                // itself has no state effect by design.
                                            });
                            return new Delivered(consumed, subject);
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
        if (delivered.subject().isEmpty()) {
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

    /** Evidence, attributed when the operation resolved ({@code V005}'s recorded rule). */
    private void retain(
            Connection unitOfWork, Optional<PaymentAttemptId> attempt, byte[] rawBody) {
        evidence.append(
                unitOfWork,
                attempt,
                Optional.<RefundId>empty(),
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

    /** The delivery transaction's yield: the dedupe outcome and the attributed subject. */
    private record Delivered(InboxConsumer.Outcome consumed, Optional<PaymentAttemptId> subject) {}

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
