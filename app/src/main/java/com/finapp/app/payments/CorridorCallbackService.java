package com.finapp.app.payments;

import com.finapp.payments.EndToEndReference;
import com.finapp.payments.EvidenceKind;
import com.finapp.payments.OutboundCreditResolution;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.payments.ProviderEvidenceStore;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The corridor provider's callback door (`P9-TSK-020`, ADR-0083 - callbacks are hints): authenticate (HMAC and
 * freshness), retain the bytes under the outbound credit our reference names, dedupe on the provider's event id
 * through the inbox, then - after the commit, holding no connection - the authenticated {@code inquire(E)} through
 * {@link OutboundCreditResolution}, whose answer alone moves the credit. The callback's own content decides
 * nothing: a forged but correctly signed callback claiming an acceptance moves nothing the inquiry does not
 * confirm.
 *
 * <p>Read from the body: the provider's event id (the inbox key) and our end-to-end reference (the inquiry's
 * subject) - nothing else.
 */
@Slf4j
public class CorridorCallbackService {

    static final String CONSUMER = "payments.corridor-webhook";
    static final String MESSAGE_TYPE = "payments.CorridorCallback";

    /** The card door's charset rule, verbatim: the event id keys a durable column. */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:@/+=-]{1,100}");

    /** Our minted end-to-end reference's shape (payments V025's {@code outbound_credit_reference_is_shaped}). */
    private static final Pattern END_TO_END_REFERENCE = Pattern.compile("[A-Za-z0-9-]{1,35}");

    private final WebhookSignature signature;
    private final String rail;
    private final OutboundCreditStore credits;
    private final ProviderEvidenceStore<Connection> evidence;
    private final InboxConsumer<Connection> inbox;
    private final OutboundCreditResolution resolution;
    private final TransactionRunner transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public CorridorCallbackService(
            WebhookSignature signature,
            String rail,
            OutboundCreditStore credits,
            ProviderEvidenceStore<Connection> evidence,
            InboxConsumer<Connection> inbox,
            OutboundCreditResolution resolution,
            TransactionRunner transactions,
            ObjectMapper json,
            Clock clock) {
        this.signature = Objects.requireNonNull(signature, "signature must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
        this.credits = Objects.requireNonNull(credits, "credits must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What one delivery did - for the suites; the door answers 202 to all but the refusals. */
    public enum Delivered {
        /** First delivery for a credit we know: retained, recorded, and the inquiry triggered. */
        HINTED,
        /** A redelivery the inbox absorbed: retained, nothing triggered. */
        DUPLICATE,
        /** Authentic but naming no credit of ours, or carrying no usable event id: acknowledged, nothing moved. */
        UNMAPPABLE
    }

    /**
     * Accepts one delivery.
     *
     * @throws ApiException 401 for every unauthenticated or unfresh shape (one refusal), 413 for a body outside the
     *     evidence bound, 409 for a contended dedupe record (unacknowledged, so the provider redelivers)
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public Delivered deliver(byte[] rawBody, String presentedTimestamp, String presentedSignature) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (!signature.matches(presentedTimestamp, rawBody, presentedSignature)) {
            throw new ApiException(PlatformErrorCode.UNAUTHENTICATED,
                    "A corridor provider callback failed signature or freshness verification");
        }
        if (rawBody.length == 0 || rawBody.length > ProviderEvidenceStore.MAX_PAYLOAD_BYTES) {
            throw new ApiException(PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "A corridor provider callback body was empty or exceeded the evidence bound");
        }
        Optional<JsonNode> parsed = parse(rawBody);
        Optional<String> eventId = parsed.flatMap(body -> text(body, "eventId")).filter(id -> EVENT_ID.matcher(id).matches());
        Optional<EndToEndReference> reference = parsed.flatMap(body -> text(body, "endToEndRef"))
                .filter(ref -> END_TO_END_REFERENCE.matcher(ref).matches())
                .map(EndToEndReference::new);
        if (eventId.isEmpty() || reference.isEmpty()) {
            log.warn("An authenticated corridor provider callback carried no usable event id or reference; it is"
                    + " acknowledged and moves nothing");
            return Delivered.UNMAPPABLE;
        }
        record Consumed(InboxConsumer.Outcome outcome, boolean known) {}
        Consumed consumed = transactions.inTransaction(unitOfWork -> {
            Optional<OutboundCreditStore.Row> credit = credits.byReference(unitOfWork, reference.get());
            credit.ifPresent(row -> evidence.appendForOutboundCredit(unitOfWork, row.id(), EvidenceKind.WEBHOOK, rawBody,
                    Instant.now(clock)));
            // The handler records nothing but the dedupe: the callback's claim is never an effect.
            return new Consumed(inbox.consume(unitOfWork, new InboxKey(CONSUMER, rail + ":" + eventId.get()), MESSAGE_TYPE,
                    uow -> { }), credit.isPresent());
        });
        if (consumed.outcome() == InboxConsumer.Outcome.CONTENDED) {
            throw new ApiException(PlatformErrorCode.CONFLICT,
                    "A corridor provider callback is being processed by another instance; asking the provider to redeliver");
        }
        if (consumed.outcome() == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            return Delivered.DUPLICATE;
        }
        if (!consumed.known()) {
            // A reference naming no credit of ours: the provider's echo of nothing we sent - recorded by its dedupe
            // only; its settlement line, if it moved money, is parked UNKNOWN_EXTERNAL.
            log.warn("An authenticated corridor provider callback named no outbound credit of ours; acknowledged");
            return Delivered.UNMAPPABLE;
        }
        // After the commit, holding no connection: the inquiry, whose answer alone moves the credit.
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            resolution.resolve(reference.get());
        } catch (RuntimeException failure) {
            // The evidence and the dedupe record are committed; the sweep inquires on its own.
            log.warn("The inquiry a corridor provider callback triggered failed: {}", failure.getClass().getSimpleName());
        }
        return Delivered.HINTED;
    }

    private Optional<JsonNode> parse(byte[] rawBody) {
        try {
            JsonNode node = json.readTree(rawBody);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }
}
