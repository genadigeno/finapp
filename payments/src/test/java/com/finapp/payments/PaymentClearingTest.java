package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clearing recorder, pinned hermetically (`P7-TSK-005`): once per attempt with the
 * acting insert alone publishing, the rail's repetition harmless, and the cross-attempt
 * acquirer-reference claim named for what it is — the integration break. The database
 * arbiters themselves (`V015`'s two uniques) are proven in the database suite; here the
 * fake's refusals stand in for them so the COMMAND's branching is the thing under test.
 */
@DisplayName("PaymentClearing (P7-TSK-005)")
class PaymentClearingTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(25_00, EUR);

    private final FakeClearingStore clearings = new FakeClearingStore();
    private final List<EventEnvelope> events = new ArrayList<>();

    /** The ARN alias seam (`P8-TSK-004`): only the acting insert may register. */
    private final RecordingSettlementExpectations expectations =
            new RecordingSettlementExpectations();

    private final PaymentClearing clearing =
            new PaymentClearing(
                    clearings,
                    (uow, envelope, payload, mediaType) -> events.add(envelope),
                    IDS,
                    CLOCK,
                    PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)),
                    expectations);
    /** Caused, as every delivery's is: resolvedCorrelation() roots the causation. */
    private final Correlation correlation =
            Correlation.startingWith(CorrelationId.of("clr-" + UUID.randomUUID()))
                    .causing(CausationId.of("clr-cause"));

    @Test
    @DisplayName("the first notice records and announces; the rail repeating itself is"
            + " ALREADY_RECORDED with nothing published twice (INV-IDEM-04)")
    void recordsOnceAndAbsorbsRepetition() {
        PaymentAttempt captured = capturedAttempt();
        ProviderReference arn = reference("arn-1");
        ProviderReference nti = reference("nti-1");

        PaymentClearing.Outcome first =
                clearing.record(null, captured, arn, nti, correlation);
        assertThat(first).isEqualTo(PaymentClearing.Outcome.RECORDED);
        assertThat(clearings.findForAttempt(null, captured.id())).isPresent();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("payments.PaymentClearedOnRail");
        assertThat(events.get(0).aggregateId()).isEqualTo(captured.intentId());

        PaymentClearing.Outcome repeated =
                clearing.record(null, captured, arn, nti, correlation);
        assertThat(repeated).isEqualTo(PaymentClearing.Outcome.ALREADY_RECORDED);
        assertThat(events).as("only the acting insert announces").hasSize(1);

        // The same capture under OTHER references is a second presentment (the Phase 7 -> 8
        // transition): never the rail repeating itself, and never recorded or announced.
        assertThat(clearing.record(null, captured, reference("arn-2"), nti, correlation))
                .isEqualTo(PaymentClearing.Outcome.SECOND_PRESENTMENT);
        assertThat(clearing.record(null, captured, arn, reference("nti-2"), correlation))
                .isEqualTo(PaymentClearing.Outcome.SECOND_PRESENTMENT);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("an acquirer reference already claimed by ANOTHER attempt is the"
            + " integration break: nothing recorded, nothing published, the first stands")
    void aForeignClaimIsTheIntegrationBreak() {
        PaymentAttempt first = capturedAttempt();
        PaymentAttempt second = capturedAttempt();
        ProviderReference shared = reference("arn-shared");
        assertThat(clearing.record(null, first, shared, reference("nti-1"), correlation))
                .isEqualTo(PaymentClearing.Outcome.RECORDED);

        PaymentClearing.Outcome claimed =
                clearing.record(null, second, shared, reference("nti-2"), correlation);
        assertThat(claimed)
                .isEqualTo(PaymentClearing.Outcome.REFERENCE_CLAIMED_ELSEWHERE);
        assertThat(clearings.findForAttempt(null, second.id())).isEmpty();
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("clearing attaches to an attempt in ANY state - the contradiction is"
            + " reconciliation's to see, and the machine is not consulted")
    void clearingAttachesWhateverTheState() {
        // A capture still in flight: the notice raced our own outcome. Recording it must
        // not depend on the machine having concluded (the door acknowledges - a refused
        // notice would be lost forever).
        PaymentAttempt inFlight =
                PaymentAttempt.rehydrate(
                        PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS),
                        SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP,
                        new ProviderIdempotencyReference("auth-" + IDS.next()),
                        new ProviderIdempotencyReference("cap-" + IDS.next()),
                        new ProviderReference("psp-auth-1"), AMOUNT, null, null, null, null,
                        null, PaymentAttemptStatus.CAPTURE_UNKNOWN, Instant.now(CLOCK),
                null, null, null, null, null);
        assertThat(clearing.record(null, inFlight, reference("arn-early"),
                        reference("nti-early"), correlation))
                .isEqualTo(PaymentClearing.Outcome.RECORDED);
        assertThat(clearings.findForAttempt(null, inFlight.id())).isPresent();
    }

    // ------------------------------------------------------------------ fixtures

    private static ProviderReference reference(String value) {
        return new ProviderReference(value + "-" + UUID.randomUUID());
    }

    private static PaymentAttempt capturedAttempt() {
        return PaymentAttempt.rehydrate(
                PaymentAttemptId.next(IDS), PaymentIntentId.next(IDS),
                SimulatedCardPspAdapter.RAIL.id(), InteractionModel.TWO_STEP,
                new ProviderIdempotencyReference("auth-" + IDS.next()),
                new ProviderIdempotencyReference("cap-" + IDS.next()),
                new ProviderReference("psp-auth-" + IDS.next()), AMOUNT,
                new ProviderReference("psp-cap-" + IDS.next()), AMOUNT, null, null, null,
                PaymentAttemptStatus.CAPTURED, Instant.now(CLOCK),
                null, null, null, null, null);
    }

    /** The V015 arbiters, in miniature: one per attempt, one per acquirer reference. */
    private static final class FakeClearingStore implements ClearingRecordStore<Connection> {
        private final Map<UUID, ClearingRecord> byAttempt = new HashMap<>();
        private final Map<String, UUID> byAcquirerReference = new HashMap<>();

        @Override
        public java.util.List<ClearingRecord> page(
                Connection unitOfWork, UUID after, int limit) {
            // P8-TSK-007: the opening-position backfill's page - not this suite's subject.
            throw new UnsupportedOperationException("no backfill runs in this suite");
        }

        @Override
        public boolean insert(Connection unitOfWork, ClearingRecord fresh) {
            if (byAttempt.containsKey(fresh.attemptId().value())
                    || byAcquirerReference.containsKey(fresh.acquirerReference().value())) {
                return false;
            }
            byAttempt.put(fresh.attemptId().value(), fresh);
            byAcquirerReference.put(
                    fresh.acquirerReference().value(), fresh.attemptId().value());
            return true;
        }

        @Override
        public Optional<ClearingRecord> findForAttempt(
                Connection unitOfWork, PaymentAttemptId attempt) {
            return Optional.ofNullable(byAttempt.get(attempt.value()));
        }
    }
}
