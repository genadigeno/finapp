package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The readings `-004`'s inputs take from the signals (`P13-TSK-005`), over an in-memory store that answers the
 * contract's four questions by the same ordering the index gives: observed-at, the event's own instant - so a signal
 * recorded late still counts within the lookback it belongs to and never within one it does not ({@code INV-EVT-04}).
 * The store's SQL is the database suite's subject ({@code SignalIntakeDatabaseTest#theReadingsIndexAnswersInOneScan}).
 */
@DisplayName("the signal readings (P13-TSK-005)")
class SignalReadingsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant NOW = Instant.now(CLOCK);

    private final InMemoryStore store = new InMemoryStore();
    private final SignalReadings readings = new SignalReadings(store);
    private final SignalSubject identity = SignalSubject.of(SubjectKind.IDENTITY, UUID.randomUUID());

    @Test
    @DisplayName("a recent credential change is one observed within the lookback - by the event's instant, not the recording's")
    void withinLookbackJudgesTheEventsOwnInstant() {
        store.add(credentialChanged(identity, NOW.minus(Duration.ofDays(10))));
        assertThat(readings.withinLookback(null, identity, SignalKind.CREDENTIAL_CHANGED, NOW, Duration.ofDays(7)))
                .isFalse();
        assertThat(readings.withinLookback(null, identity, SignalKind.CREDENTIAL_CHANGED, NOW, Duration.ofDays(30)))
                .isTrue();

        // A late delivery: the event happened an hour ago and is recorded now - it counts as an hour ago.
        store.add(credentialChanged(identity, NOW.minus(Duration.ofHours(1))));
        assertThat(readings.countWithin(null, identity, SignalKind.CREDENTIAL_CHANGED, NOW, Duration.ofDays(7)))
                .isEqualTo(1);
        assertThat(readings.countWithin(null, identity, SignalKind.CREDENTIAL_CHANGED, NOW, Duration.ofDays(30)))
                .isEqualTo(2);
        // Another identity's change is not this one's.
        assertThat(readings.countWithin(null, SignalSubject.of(SubjectKind.IDENTITY, UUID.randomUUID()),
                SignalKind.CREDENTIAL_CHANGED, NOW, Duration.ofDays(30))).isZero();
        // Another kind about the same identity is not a credential change.
        assertThat(readings.countWithin(null, identity, SignalKind.IDENTITY_SUSPENDED, NOW, Duration.ofDays(30)))
                .isZero();
    }

    @Test
    @DisplayName("the latest is the latest observed, whatever order the events were recorded in")
    void theLatestIsByObservedAt() {
        RiskSignal later = credentialChanged(identity, NOW.minus(Duration.ofHours(1)));
        RiskSignal earlier = credentialChanged(identity, NOW.minus(Duration.ofDays(3)));
        store.add(later);
        store.add(earlier);
        assertThat(readings.latest(null, identity, SignalKind.CREDENTIAL_CHANGED)).contains(later);
        assertThat(readings.latest(null, identity, SignalKind.RECOVERY_COMPLETED)).isEmpty();
    }

    @Test
    @DisplayName("an account's age is counted from its first opening signal; none means no age")
    void ageIsFromTheFirstSignal() {
        SignalSubject account = SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID());
        assertThat(readings.age(null, account, SignalKind.ACCOUNT_OPENED, NOW)).isEmpty();
        store.add(accountOpened(account, NOW.minus(Duration.ofDays(40))));
        assertThat(readings.age(null, account, SignalKind.ACCOUNT_OPENED, NOW)).contains(Duration.ofDays(40));
        // A signal observed after "now" on a skewed producer clock is an age of zero, never negative.
        SignalSubject fresh = SignalSubject.of(SubjectKind.ACCOUNT, UUID.randomUUID());
        store.add(accountOpened(fresh, NOW.plus(Duration.ofSeconds(5))));
        assertThat(readings.age(null, fresh, SignalKind.ACCOUNT_OPENED, NOW)).contains(Duration.ZERO);
    }

    @Test
    @DisplayName("a device is novel until anything at all is observed about it")
    void noveltyIsTheAbsenceOfAnySignal() {
        SignalSubject device = SignalSubject.device(DeviceHash.of("Chrome on Windows"));
        assertThat(readings.isNovel(null, device)).isTrue();
        assertThat(readings.isNovel(null, identity)).isTrue();
        store.add(credentialChanged(identity, NOW.minus(Duration.ofDays(1))));
        assertThat(readings.isNovel(null, identity)).isFalse();
        assertThat(readings.isNovel(null, device)).isTrue();
    }

    @Test
    @DisplayName("a negative lookback is a defect, not an empty window")
    void aNegativeLookbackIsRefused() {
        assertThatThrownBy(() -> readings.countWithin(null, identity, SignalKind.CREDENTIAL_CHANGED, NOW,
                Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static RiskSignal credentialChanged(SignalSubject identity, Instant observedAt) {
        return new RiskSignal(RiskSignalId.next(IDS), SignalKind.CREDENTIAL_CHANGED, identity,
                source("identity.CredentialChanged", "identity"), observedAt, SignalAttributes.none());
    }

    private static RiskSignal accountOpened(SignalSubject account, Instant observedAt) {
        return new RiskSignal(RiskSignalId.next(IDS), SignalKind.ACCOUNT_OPENED, account,
                source("accounts.AccountOpened", "accounts"), observedAt,
                SignalAttributes.of(SignalKind.ACCOUNT_OPENED, Map.of("product", "WALLET")));
    }

    private static RiskSignal.SourceEvent source(String type, String producer) {
        return new RiskSignal.SourceEvent(EventId.next(IDS), type, 1, producer, CorrelationId.of("flow"),
                CausationId.of("cause"));
    }

    /** The contract's four reads over a list, ordered as the index orders: by observed-at. */
    static final class InMemoryStore implements RiskSignalStore {
        private final List<RiskSignal> rows = new ArrayList<>();

        void add(RiskSignal signal) {
            rows.add(signal);
        }

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            if (rows.stream().anyMatch(row -> row.source().id().equals(signal.source().id()))) {
                return false;
            }
            rows.add(signal);
            return true;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            return (int) of(subject, kind).filter(row -> !row.observedAt().isBefore(since)).count();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            return of(subject, kind).max(Comparator.comparing(RiskSignal::observedAt));
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            return of(subject, kind).map(RiskSignal::observedAt).min(Comparator.naturalOrder());
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            return rows.stream().anyMatch(row -> row.subject().equals(subject));
        }

        private java.util.stream.Stream<RiskSignal> of(SignalSubject subject, SignalKind kind) {
            return rows.stream().filter(row -> row.subject().equals(subject) && row.kind() == kind);
        }
    }
}
