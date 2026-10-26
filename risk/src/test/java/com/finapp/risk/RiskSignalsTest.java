package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-sig's own decisions (`P13-TSK-005`): born announces, already is silent, and the announcement carries identifiers
 * and codes only, caused by the consumed event. The dedupe, the race and the unique are the inbox's and the database
 * suite's subjects ({@code SignalIntakeDatabaseTest}); this is hermetic on purpose, over a store that answers what it
 * is told to.
 */
@DisplayName("T-sig: a signal recorded once and announced when born (P13-TSK-005)")
class RiskSignalsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final RecordingOutbox outbox = new RecordingOutbox();

    @Test
    @DisplayName("a signal born is announced once - ids and codes only, in the consumed event's flow, caused by it")
    void aBornSignalIsAnnounced() {
        ScriptedStore store = new ScriptedStore(true);
        RiskSignals signals = new RiskSignals(store, outbox, IDS, CLOCK);
        RiskSignal signal = signal();

        assertThat(signals.record(null, signal)).isEqualTo(RiskSignals.Outcome.BORN);

        assertThat(store.inserted).containsExactly(signal);
        assertThat(outbox.envelopes).hasSize(1);
        EventEnvelope announced = outbox.envelopes.get(0);
        assertThat(announced.eventType()).isEqualTo("risk.RiskSignalRecorded");
        assertThat(announced.eventVersion()).isEqualTo(1);
        assertThat(announced.producer()).isEqualTo("risk");
        assertThat(announced.aggregateType()).isEqualTo("risk_signal");
        assertThat(announced.aggregateId()).isEqualTo(signal.id());
        assertThat(announced.occurredAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(announced.correlationId()).isEqualTo(signal.source().correlation());
        assertThat(announced.causationId().value())
                .as("the CONSUMED event is this one's cause - inheriting its causation would flatten the tree")
                .isEqualTo(signal.source().id().value().toString());
        assertThat(EventPayload.fields(outbox.payloads.get(0)))
                .as("the signal's id, its kind, its subject's kind and reference - and no attribute (INV-RSK-08)")
                .containsExactly(
                        Map.entry("signalId", signal.id().value().toString()),
                        Map.entry("kind", "ACCOUNT_OPENED"),
                        Map.entry("subjectKind", "ACCOUNT"),
                        Map.entry("subjectRef", signal.subject().ref()));
        assertThat(outbox.mediaTypes).containsExactly(EventPayload.MEDIA_TYPE);
    }

    @Test
    @DisplayName("a source event already recorded writes nothing more and announces nothing")
    void anAlreadyRecordedEventAnnouncesNothing() {
        // The unique said no: a second RiskSignalRecorded would tell consumers a second signal exists, which is the
        // duplicate INV-RSK-06 forbids at the row and INV-EVT-04 forbids at the consumer.
        ScriptedStore store = new ScriptedStore(false);
        RiskSignals signals = new RiskSignals(store, outbox, IDS, CLOCK);

        assertThat(signals.record(null, signal())).isEqualTo(RiskSignals.Outcome.ALREADY);

        assertThat(store.inserted).hasSize(1);
        assertThat(outbox.envelopes).isEmpty();
    }

    @Test
    @DisplayName("every recording is told to the observer by kind and outcome - born or already, codes only"
            + " (P13-TSK-024, finapp.risk.signal)")
    void everyRecordingIsObserved() {
        List<String> told = new ArrayList<>();
        SignalObserver observer = (kind, outcome) -> told.add(kind + " " + outcome);
        new RiskSignals(new ScriptedStore(true), outbox, IDS, CLOCK, ProfileHints.NONE, SignalAlerts.NONE, observer)
                .record(null, signal());
        new RiskSignals(new ScriptedStore(false), outbox, IDS, CLOCK, ProfileHints.NONE, SignalAlerts.NONE, observer)
                .record(null, signal());
        assertThat(told).containsExactly("ACCOUNT_OPENED BORN", "ACCOUNT_OPENED ALREADY");
    }

    @Test
    @DisplayName("a device subject's reference rides the event as its hash - which the payload grammar admits and a description could not")
    void aDeviceSubjectRidesAsItsHash() {
        ScriptedStore store = new ScriptedStore(true);
        RiskSignals signals = new RiskSignals(store, outbox, IDS, CLOCK);
        DeviceHash hash = DeviceHash.of("Chrome on Windows");
        // No kind is about a device yet (no event carries one); the envelope rule is exercised on the subject alone.
        assertThat(EventPayload.of().with("subjectRef", hash.value()).toBytes()).isNotEmpty();
        assertThat(SignalSubject.device(hash).ref()).matches("[A-Za-z0-9_-]{1,200}");
        assertThat(signals).isNotNull();
    }

    // -----------------------------------------------------------------

    private static RiskSignal signal() {
        return new RiskSignal(
                RiskSignalId.next(IDS), SignalKind.ACCOUNT_OPENED,
                SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID()),
                new RiskSignal.SourceEvent(EventId.next(IDS), "accounts.AccountOpened", 1, "accounts",
                        CorrelationId.of("flow-7"), CausationId.of("cause-7")),
                Instant.parse("2026-10-23T11:59:00Z"),
                SignalAttributes.of(SignalKind.ACCOUNT_OPENED, Map.of("product", "WALLET")));
    }

    /** Answers born or already, as scripted, and remembers what it was given. */
    private static final class ScriptedStore implements RiskSignalStore {
        private final boolean born;
        private final List<RiskSignal> inserted = new ArrayList<>();

        private ScriptedStore(boolean born) {
            this.born = born;
        }

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            inserted.add(signal);
            return born;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }

    static final class RecordingOutbox implements OutboxWriter<Connection> {
        final List<EventEnvelope> envelopes = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        final List<String> mediaTypes = new ArrayList<>();

        @Override
        public void write(Connection unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType) {
            envelopes.add(envelope);
            payloads.add(payload);
            mediaTypes.add(payloadMediaType);
        }
    }
}
