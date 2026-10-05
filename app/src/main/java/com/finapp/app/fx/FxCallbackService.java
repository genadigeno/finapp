package com.finapp.app.fx;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.TransactionRunner;
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
 * The FX provider's callback door (`P9-TSK-012`; ADR-0077 section 9, D25) - the instant-scheme
 * door's discipline verbatim (signature, then size, then a total parse, then the inbox and the
 * evidence in one transaction), with ONE difference: <strong>a callback is a hint</strong>. What it
 * claims moves nothing; a first delivery triggers an authenticated {@code inquire(T)} after its
 * transaction commits, and only the INQUIRY's answer moves the cover ({@link FxCoverDispatch#hint}).
 * So a forged-but-signed callback - a stolen key - moves no money, and ten deliveries are one
 * inbox record and at most one inquiry.
 *
 * <p>Read from the body: the provider's event id (the inbox key) and our reference {@code T}
 * (the inquiry's subject) - nothing else, so nothing in it is ever trusted as an outcome.
 */
@Slf4j
public class FxCallbackService {

    static final String CONSUMER = "fx.provider-webhook";
    static final String MESSAGE_TYPE = "fx.ProviderCallback";

    /** The card door's charset rule, verbatim: the event id keys a durable column. */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:@/+=-]{1,100}");

    /** Our minted cover reference's shape (fx V006's {@code cover_attempt_reference_shape}). */
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("T-[0-9a-f]{32}");

    /** Where an unattributable callback's bytes rest - a fixed marker, never a caller's value. */
    static final String UNATTRIBUTED = "callback-unattributed";

    private final WebhookSignature signature;
    private final String providerCode;
    private final FxProviderEvidenceStore<Connection> evidence;
    private final InboxConsumer<Connection> inbox;
    private final FxCoverDispatch dispatch;
    private final TransactionRunner transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public FxCallbackService(
            WebhookSignature signature,
            String providerCode,
            FxProviderEvidenceStore<Connection> evidence,
            InboxConsumer<Connection> inbox,
            FxCoverDispatch dispatch,
            TransactionRunner transactions,
            ObjectMapper json,
            Clock clock) {
        this.signature = Objects.requireNonNull(signature, "signature must not be null");
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode must not be null");
        this.evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What one delivery did - for the suites; the door answers 204 to all but the refusals. */
    public enum Delivered {
        /** First delivery: retained, recorded, and the inquiry triggered. */
        HINTED,
        /** A redelivery the inbox absorbed: retained, nothing triggered. */
        DUPLICATE,
        /** Authentic but carrying no usable event id or reference: retained, acknowledged. */
        UNMAPPABLE
    }

    /**
     * Accepts one delivery.
     *
     * @throws ApiException 401 for every unauthenticated or unfresh shape (one refusal), 413 for a
     *     body outside the evidence bound, 409 for a contended dedupe record (unacknowledged, so the
     *     provider redelivers - the inbox's own contract)
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public Delivered deliver(byte[] rawBody, String presentedTimestamp, String presentedSignature) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (!signature.matches(presentedTimestamp, rawBody, presentedSignature)) {
            throw new ApiException(PlatformErrorCode.UNAUTHENTICATED,
                    "An FX provider callback failed signature or freshness verification");
        }
        if (rawBody.length == 0 || rawBody.length > FxProviderEvidenceStore.MAX_PAYLOAD_BYTES) {
            throw new ApiException(PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "An FX provider callback body was empty or exceeded the evidence bound");
        }
        Optional<JsonNode> parsed = parse(rawBody);
        Optional<String> eventId = parsed.flatMap(body -> text(body, "eventId")).filter(id -> EVENT_ID.matcher(id).matches());
        Optional<String> reference = parsed.flatMap(body -> text(body, "clientRef"))
                .filter(ref -> CLIENT_REFERENCE.matcher(ref).matches());
        if (eventId.isEmpty() || reference.isEmpty()) {
            transactions.inTransaction(unitOfWork -> {
                retain(unitOfWork, UNATTRIBUTED, rawBody);
                return null;
            });
            log.warn("An authenticated FX provider callback carried no usable event id or reference; its bytes"
                    + " are retained as evidence and it is acknowledged");
            return Delivered.UNMAPPABLE;
        }
        InboxConsumer.Outcome consumed = transactions.inTransaction(unitOfWork -> {
            retain(unitOfWork, reference.get(), rawBody);
            // The handler records nothing but the dedupe: the callback's claim is never an effect.
            return inbox.consume(unitOfWork, new InboxKey(CONSUMER, providerCode + ":" + eventId.get()), MESSAGE_TYPE,
                    uow -> { });
        });
        if (consumed == InboxConsumer.Outcome.CONTENDED) {
            throw new ApiException(PlatformErrorCode.CONFLICT,
                    "An FX provider callback is being processed by another instance; asking the provider to redeliver");
        }
        if (consumed == InboxConsumer.Outcome.SKIPPED_DUPLICATE) {
            return Delivered.DUPLICATE;
        }
        // After the commit, holding no connection: the inquiry, whose answer alone moves the cover.
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            dispatch.hint(reference.get(), SecurityContext.require());
        } catch (RuntimeException failure) {
            // The evidence and the dedupe record are committed; the sweep inquires on its own.
            log.warn("The inquiry an FX provider callback triggered failed: {}", failure.getClass().getSimpleName());
        }
        return Delivered.HINTED;
    }

    private void retain(Connection unitOfWork, String reference, byte[] rawBody) {
        evidence.append(unitOfWork, providerCode, reference, FxProviderEvidenceStore.Kind.CALLBACK, rawBody,
                Instant.now(clock));
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
