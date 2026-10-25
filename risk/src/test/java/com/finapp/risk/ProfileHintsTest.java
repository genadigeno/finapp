package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-sig's permit pull (`P13-TSK-007`; {@code PHASE_13_PLAN.md} section 7.1): a signal born about a placeable party
 * enrols the party or pulls its profile permit to now, in the handler's own transaction; an unplaceable subject, a
 * signal already recorded and {@link ProfileHints#NONE} pull nothing. The waiting upsert itself is
 * {@code ProfileDatabaseTest#aSignalPullsThePermitForward}'s.
 */
@DisplayName("T-sig: a signal pulls its party's profile permit forward (P13-TSK-007)")
class ProfileHintsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            ProfileHintsTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final InMemoryCustomerRiskProfileStore profiles = new InMemoryCustomerRiskProfileStore();
    private final RiskSignalsTest.RecordingOutbox outbox = new RiskSignalsTest.RecordingOutbox();

    @Test
    @DisplayName("an identity's credential change born: its party is enrolled due now; a later one pulls a later permit"
            + " back to now")
    void aBornSignalPullsItsPartysPermit() {
        UUID identity = IDS.next();
        UUID party = IDS.next();
        RiskSignals signals = new RiskSignals(new Born(), outbox, IDS, CLOCK,
                ProfileHints.over(placing(identity, party), profiles));

        assertThat(signals.record(UOW, credentialChanged(identity))).isEqualTo(RiskSignals.Outcome.BORN);
        assertThat(profiles.head(party)).hasValueSatisfying(head -> {
            assertThat(head.assessed()).as("enrolled, not assessed").isFalse();
            assertThat(head.nextRecomputeAt()).isEqualTo(profiles.now);
        });

        profiles.restamp(UOW, party, java.time.Duration.ofDays(1), true);
        profiles.now = profiles.now.plusSeconds(60);
        signals.record(UOW, credentialChanged(identity));
        assertThat(profiles.head(party).orElseThrow().nextRecomputeAt()).as("pulled back to now").isEqualTo(profiles.now);
    }

    @Test
    @DisplayName("a subject placed in no party is recorded and pulls nothing; a signal already recorded hints nothing; NONE"
            + " hints nothing")
    void nothingIsPulledWithoutABirthOrAParty() {
        UUID identity = IDS.next();
        UUID party = IDS.next();
        new RiskSignals(new Born(), outbox, IDS, CLOCK, ProfileHints.over(RiskSubjectParties.NONE, profiles))
                .record(UOW, credentialChanged(identity));
        assertThat(profiles.log).isEmpty();

        RiskSignals already = new RiskSignals(new Already(), outbox, IDS, CLOCK,
                ProfileHints.over(placing(identity, party), profiles));
        assertThat(already.record(UOW, credentialChanged(identity))).isEqualTo(RiskSignals.Outcome.ALREADY);
        assertThat(profiles.log).isEmpty();

        new RiskSignals(new Born(), outbox, IDS, CLOCK).record(UOW, credentialChanged(identity));
        assertThat(profiles.log).isEmpty();
        assertThat(profiles.head(party)).isEmpty();
    }

    // -----------------------------------------------------------------

    private static RiskSubjectParties placing(UUID identity, UUID party) {
        return (unitOfWork, subject) -> subject.equals(SignalSubject.of(SubjectKind.IDENTITY, identity))
                ? Optional.of(party) : Optional.empty();
    }

    private static RiskSignal credentialChanged(UUID identity) {
        return new RiskSignal(RiskSignalId.next(IDS), SignalKind.CREDENTIAL_CHANGED,
                SignalSubject.of(SubjectKind.IDENTITY, identity),
                new RiskSignal.SourceEvent(EventId.next(IDS), "identity.CredentialChanged", 1, "identity",
                        CorrelationId.of("flow-9"), CausationId.of("cause-9")),
                Instant.parse("2026-10-23T11:59:00Z"), SignalAttributes.of(SignalKind.CREDENTIAL_CHANGED, Map.of()));
    }

    private static class Born implements RiskSignalStore {
        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            return true;
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

    private static final class Already extends Born {
        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            return false;
        }
    }
}
