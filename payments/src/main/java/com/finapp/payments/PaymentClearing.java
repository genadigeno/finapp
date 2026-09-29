package com.finapp.payments;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Records a capture's clearing evidence (`P7-TSK-005`, ADR-0059 §4): the network's own
 * references, once per attempt, in the delivering webhook's own transaction.
 *
 * <h2>Evidence, not effect</h2>
 *
 * <p>Clearing agrees an obligation and moves no money ({@code INV-SET-01}): this command
 * posts nothing, transitions nothing, and attaches to an attempt in ANY state — a clearing
 * racing our own capture-approval webhook, or contradicting a void, is exactly the statement
 * Phase 8's matching must see, and the door acknowledges deliveries, so a refused notice
 * would be lost forever.
 *
 * <h2>Once, decided by the database</h2>
 *
 * <p>The two unique arbiters (one record per attempt, one per acquirer reference,
 * platform-wide) settle every race: ten deliveries of one notice under ten fresh event ids
 * insert one row, and only the acting insert publishes {@code payments.PaymentClearedOnRail}
 * — the same-event-id duplicates never reach here, absorbed by the inbox
 * ({@code INV-IDEM-04}'s two ranks). A refused insert is then read back apart: a record
 * already standing for THIS attempt is the rail repeating itself (harmless); an acquirer
 * reference claimed by ANOTHER attempt is the integration break the caller must make loud.
 */
@RequiredArgsConstructor
public final class PaymentClearing {

    /** The clearing announcement: a fact for Phase 8's ears, never a state change. */
    public static final String CLEARED_EVENT_TYPE = "payments.PaymentClearedOnRail";

    @NonNull private final ClearingRecordStore<Connection> clearings;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The stored rail's key back to its declaration (`P8-TSK-004`, `INV-RAIL-01`). */
    @NonNull private final PaymentRails rails;

    /**
     * The ARN alias's seam (`P8-TSK-004`, ADR-0067 §5): the acting insert registers the
     * acquirer reference as an alias resolving to the attempt's {@code CARD_ATTEMPT}
     * anchor, in this same transaction — in either order with the capture's expectation,
     * because the matcher's two-hop join makes order irrelevant. Only {@code RECORDED}
     * registers: the repeats, the claimed-elsewhere and the second presentment leave the
     * first record standing and rest as evidence. Appended last (positional history).
     */
    @NonNull private final SettlementExpectations expectations;

    /** What one notice did — the caller's logging and metering seam. */
    public enum Outcome {
        /** This call recorded the clearing and published the event. */
        RECORDED,
        /** A record for this attempt already stands: the rail repeating itself, harmless. */
        ALREADY_RECORDED,
        /**
         * The acquirer reference is already recorded for a DIFFERENT attempt: two captures
         * cannot share one network clearing, so one side is wrong — the first record
         * stands, this statement rests as evidence, and the caller alerts.
         */
        REFERENCE_CLAIMED_ELSEWHERE,
        /**
         * The capture is already cleared under OTHER network references (the Phase 7 -&gt; 8
         * transition): the network presenting one capture a second time - a double charge to
         * the cardholder, or a presentment the platform never made. The first record stands;
         * this statement is a break, never the rail repeating itself (the gate found it
         * absorbed as {@link #ALREADY_RECORDED}, counted processed, kept nowhere but evidence).
         */
        SECOND_PRESENTMENT
    }

    /**
     * Records the clearing of {@code attempt}, once. Runs inside the delivery's transaction:
     * the record, the event and the delivery's dedupe commit together or not at all.
     */
    public Outcome record(
            Connection unitOfWork,
            PaymentAttempt attempt,
            ProviderReference acquirerReference,
            ProviderReference networkTransactionId,
            Correlation correlation) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(acquirerReference, "acquirerReference must not be null");
        Objects.requireNonNull(networkTransactionId, "networkTransactionId must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");

        Instant now = Instant.now(clock);
        boolean recorded =
                clearings.insert(
                        unitOfWork,
                        new ClearingRecord(
                                ClearingRecordId.next(ids),
                                attempt.id(),
                                acquirerReference,
                                networkTransactionId,
                                now));
        if (!recorded) {
            // The standing record decides which repeat this is: the same references are the
            // rail repeating itself; different ones on this attempt are a second presentment.
            return clearings.findForAttempt(unitOfWork, attempt.id())
                    .map(standing ->
                            standing.acquirerReference().equals(acquirerReference)
                                            && standing.networkTransactionId()
                                                    .equals(networkTransactionId)
                                    ? Outcome.ALREADY_RECORDED
                                    : Outcome.SECOND_PRESENTMENT)
                    .orElse(Outcome.REFERENCE_CLAIMED_ELSEWHERE);
        }

        // Announced only by the acting insert, in its transaction: downstream hears one
        // clearing per capture exactly as the table holds one. Identifiers and the rail -
        // never an amount, and never the network references (the table is their home; an
        // event fans out to consumers the register has not classified).
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        CLEARED_EVENT_TYPE,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        attempt.intentId(),
                        PaymentCreation.TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("status", "CLEARED")
                        .with("attemptId", attempt.id().value().toString())
                        .with("rail", attempt.rail().value())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);

        // THE ALIAS (P8-TSK-004, ADR-0067 §5): the network's reference resolves to the
        // attempt anchor, whatever the attempt's state - the ARN is evidence even against a
        // voided or failed attempt, and the matcher then finds no candidate and types the
        // break. Judged from the stored rail's declaration; a clearing on a rail declaring
        // no position (unreachable today - only the card rail clears) registers nothing.
        rails.capabilitiesOf(attempt.rail())
                .clearingPurpose()
                .ifPresent(
                        position ->
                                expectations.alias(
                                        unitOfWork,
                                        new SettlementExpectations.AliasRegistration(
                                                position,
                                                SettlementExpectations.ReferenceKind
                                                        .ACQUIRER_REF,
                                                acquirerReference.value(),
                                                SettlementExpectations.ReferenceKind
                                                        .CARD_ATTEMPT,
                                                attempt.id().value().toString(),
                                                correlation)));
        return Outcome.RECORDED;
    }
}
