package com.finapp.app.kyc;

import com.finapp.kyc.CallbackSignature;
import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.EvidenceId;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * One provider callback, delivered at-least-once, effected at most once (`P2-TSK-011`,
 * {@code INV-KYC-03}, {@code INV-IDEM-04}).
 *
 * <h2>Two dedupe layers, blind in different directions — designed in, not found by a gate</h2>
 *
 * <p>The <strong>inbox</strong> absorbs <em>identical</em> deliveries: the provider retrying
 * one delivery reuses its {@code deliveryId}, and the primary key on
 * {@code (consumer, dedupe_key)} lets exactly one instance run the handler
 * ({@code P0-TSK-021}). The <strong>conditional completion</strong> absorbs <em>distinct</em>
 * deliveries of the same result — a re-sent answer under a fresh id, or a callback racing the
 * synchronous run — the `P2-TSK-007` lesson that an exact duplicate never reaches the handler
 * and the real subject is a distinct delivery converging.
 *
 * <h2>A late answer is evidence, never a transition</h2>
 *
 * <p>A callback for a check that is already terminal — the provider answering after our
 * timeout, plan §8 scenario 2 — is retained verbatim ({@code INV-HIST-02}) and changes
 * nothing: no transition out of a terminal ({@code INV-LIFE-04}), and the resolution of an
 * {@code INDETERMINATE} remains a new check (ADR-0038). It is acknowledged with success,
 * because a refusal would make a correct provider retry a fact this platform will never accept.
 *
 * <h2>What a callback heals</h2>
 *
 * <p>A check stranded {@code DISPATCHED} by a crash mid-call was `P2-TSK-009`'s recorded
 * visible-by-design remainder. The provider received that request — the dispatch committed
 * first, by design — so its callback is how the stranded fact completes without a sweeper.
 * After the delivery commits, the case is <strong>assessed</strong> ({@link CaseAssessment}, a
 * separate transaction), because a callback that answered the last outstanding question must
 * move the case; and a <em>duplicate</em> delivery re-assesses too, which is what heals a crash
 * between a delivery's commit and its assessment.
 */
public class ProviderCallbackService {

    static final String CONSUMER = "kyc.provider-callback";
    static final String MESSAGE_TYPE = "kyc.ProviderCallback";

    /**
     * The {@code IdempotencyKeyHeader} charset, for the same reason (`P0-TSK-017`): the
     * delivery id is a caller-supplied value bound for a durable column
     * ({@code inbox_message.dedupe_key}, whose {@code INTERNAL} classification is a
     * <em>requirement on the value</em>) and for log lines — and this callback is that
     * column's first external supplier, so the requirement is enforced at the boundary before
     * it can stop being true. A newline in it would be a forged log line.
     */
    private static final Pattern DELIVERY_ID = Pattern.compile("[A-Za-z0-9._:@/+=-]+");

    private static final Logger log = LoggerFactory.getLogger(ProviderCallbackService.class);

    private final CheckStore<Connection> checks;
    private final CallbackSignature signature;
    private final InboxConsumer<Connection> inbox;
    private final CheckOutcomeTrail trail;
    private final CaseAssessment assessment;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public ProviderCallbackService(
            CheckStore<Connection> checkStore,
            CallbackSignature callbackSignature,
            InboxConsumer<Connection> inboxConsumer,
            CheckOutcomeTrail checkOutcomeTrail,
            CaseAssessment caseAssessment,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.signature =
                Objects.requireNonNull(callbackSignature, "callbackSignature must not be null");
        this.inbox = Objects.requireNonNull(inboxConsumer, "inboxConsumer must not be null");
        this.trail = Objects.requireNonNull(checkOutcomeTrail, "checkOutcomeTrail must not be null");
        this.assessment =
                Objects.requireNonNull(caseAssessment, "caseAssessment must not be null");
        this.json = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /** The provider's wire shape — ours end to end, like the outbound protocol it mirrors. */
    private record CallbackPayload(String deliveryId, String checkId, String status) {}

    /**
     * Delivers one callback.
     *
     * <p>The signature is verified over the raw bytes <strong>before anything else</strong> —
     * before parsing, before any read, before any write — so an unauthenticated stranger can
     * grow no table and learn nothing but 401.
     *
     * @throws ApiException with the code that says which refusal; never a 500 for a caller's
     *     shape
     */
    public void deliver(byte[] rawBody, String presentedSignature) {
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        if (!signature.matches(rawBody, presentedSignature)) {
            // Missing, malformed and wrong are ONE refusal: which way a forgery failed is not
            // information to hand out.
            throw new ApiException(
                    PlatformErrorCode.UNAUTHENTICATED,
                    "A provider callback failed signature verification");
        }
        if (rawBody.length == 0 || rawBody.length > DocumentBytes.MAX_BYTES) {
            // The evidence bound, enforced where the caller is told rather than discovered at
            // the insert (the SimulatedProviderClient reasoning, inbound).
            throw new ApiException(
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "A provider callback body was empty or exceeded the evidence bound");
        }

        CallbackPayload payload = parse(rawBody);
        String deliveryId = validatedDeliveryId(payload.deliveryId());
        CheckId checkId = validatedCheckId(payload.checkId());
        if (payload.status() == null) {
            // Absent is a broken integration the provider must fix; an UNRECOGNISED status is
            // the provider saying something we do not map, and normalises to INDETERMINATE.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A provider callback carried no status",
                    "status is required");
        }
        CheckOutcome outcome = CheckOutcome.fromWire(payload.status());

        Delivered delivered =
                units.inTransaction(
                        unitOfWork -> {
                            VerificationCheck check =
                                    checks.findById(unitOfWork, checkId)
                                            .orElseThrow(
                                                    () ->
                                                            new ApiException(
                                                                    PlatformErrorCode.NOT_FOUND,
                                                                    "A provider callback named an"
                                                                        + " unknown check"));
                            InboxConsumer.Outcome consumed =
                                    inbox.consume(
                                            unitOfWork,
                                            new InboxKey(CONSUMER, deliveryId),
                                            MESSAGE_TYPE,
                                            uow -> effect(uow, check, outcome, rawBody));
                            return new Delivered(consumed, check);
                        });

        if (delivered.consumed() == InboxConsumer.Outcome.CONTENDED) {
            // The inbox's own contract: the other transaction may yet roll back, so this
            // delivery must NOT be acknowledged. A non-2xx makes the provider redeliver, and
            // the dedupe absorbs or the redelivery lands - correct whichever way the race went.
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "A provider callback is being processed by another instance; asking the"
                            + " provider to redeliver");
        }
        // PROCESSED assesses because this delivery may have answered the last outstanding
        // question; a DUPLICATE re-assesses because the first delivery may have crashed between
        // its commit and its assessment - the same self-healing the run's re-assessment gives.
        assessment.assess(delivered.check().caseId());
    }

    /** The one effect, inside the delivery's transaction — with the dedupe record, or not at all. */
    private void effect(
            Connection unitOfWork, VerificationCheck check, CheckOutcome outcome, byte[] rawBody) {
        boolean won = checks.complete(unitOfWork, check.id(), outcome, Instant.now(clock));
        // Evidence ALWAYS (INV-HIST-02): the late answer and the losing racer are both genuine
        // provider statements an investigation wants - unlike the run's duplicate completion,
        // whose losing response duplicates an answer already retained.
        checks.appendEvidence(
                unitOfWork, EvidenceId.next(ids), check.id(), rawBody, Instant.now(clock));
        if (won) {
            trail.record(unitOfWork, check, outcome);
        } else {
            // Identifiers only. The commonest cause is the provider answering after our
            // timeout already went INDETERMINATE - plan §8 scenario 2, working as designed.
            log.info(
                    "A callback for check {} arrived after it was already terminal; its answer"
                            + " is retained as evidence and changes nothing",
                    check.id());
        }
    }

    private record Delivered(InboxConsumer.Outcome consumed, VerificationCheck check) {}

    private CallbackPayload parse(byte[] rawBody) {
        try {
            return json.readValue(rawBody, CallbackPayload.class);
        } catch (tools.jackson.core.JacksonException unparseable) {
            // Never the parser's message: it echoes the caller's bytes. The cause is dropped
            // for the same reason (the DatabaseFailure.describe argument, at the boundary).
            throw new ApiException(
                    PlatformErrorCode.MALFORMED_REQUEST,
                    "A provider callback body could not be parsed as JSON");
        }
    }

    private static String validatedDeliveryId(String deliveryId) {
        if (deliveryId == null
                || deliveryId.isBlank()
                || deliveryId.length() > InboxKey.MAX_LENGTH
                || !DELIVERY_ID.matcher(deliveryId).matches()) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A provider callback carried a missing or malformed delivery id",
                    "deliveryId is required, at most " + InboxKey.MAX_LENGTH
                            + " characters from the documented charset");
        }
        return deliveryId;
    }

    private static CheckId validatedCheckId(String checkId) {
        if (checkId == null) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A provider callback carried no check identifier",
                    "checkId is required");
        }
        try {
            return CheckId.of(UUID.fromString(checkId));
        } catch (IllegalArgumentException malformed) {
            // A v4 lands here too (EntityId validates UUIDv7) - the recurring trap, met at the
            // boundary this time and answered with a 422 rather than discovered by a test.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A provider callback carried a malformed check identifier",
                    "checkId is not a valid check identifier");
        }
    }
}
