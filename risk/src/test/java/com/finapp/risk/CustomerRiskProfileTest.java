package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The customer risk profile's recompute over fakes (`P13-TSK-007`; {@code RISK_LIFECYCLES.md} section 4.9;
 * {@code INV-RSK-10}, {@code INV-RSK-07}, {@code INV-HIST-04}): a version only when the rating changes, with its whole
 * evaluation and one announcement of ids and codes; {@code PROHIBITED} sticky against the sweep; nothing judged with no
 * {@code MONITORING} version; the inputs the evaluation seals, and a version replaying its rating from them; the pin
 * (R1) before the head (R8). The database's locks and triggers are {@code ProfileDatabaseTest}'s.
 */
@DisplayName("the customer risk profile's recompute (P13-TSK-007)")
class CustomerRiskProfileTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Duration CADENCE = Duration.ofDays(1);
    private static final CorrelationId FLOW = CorrelationId.of("profile-flow");
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            CustomerRiskProfileTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final PolicyStore policies = new PolicyStore();
    private final InMemoryCustomerRiskProfileStore profiles = new InMemoryCustomerRiskProfileStore();
    private final RiskSignalsTest.RecordingOutbox outbox = new RiskSignalsTest.RecordingOutbox();
    private final Screening screening = new Screening();
    private final ProfileRecompute recompute = new ProfileRecompute(new RiskPolicies(policies), profiles,
            inputs(screening), AlertRaiser.NONE, outbox, IDS, CLOCK, CADENCE);

    @Test
    @DisplayName("the first recompute writes version 1 with its whole evaluation; an unchanged rating writes nothing but"
            + " the permit")
    void aVersionOnlyWhenTheRatingChanges() {
        RiskPolicyVersionId monitoring = policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        UUID party = IDS.next();

        ProfileRecompute.Outcome first = recompute.recompute(UOW, party, FLOW);
        assertThat(first.kind()).isEqualTo(ProfileRecompute.Kind.CHANGED);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.rating()).contains(RiskRating.LOW);
        CustomerRiskProfileVersion v1 = profiles.versions(UOW, party).get(0);
        assertThat(v1.cause()).isEqualTo(ProfileCause.RECOMPUTE);
        assertThat(v1.reasons()).as("nothing fired - never screened, every movement reading ABSENT").isEmpty();
        assertThat(v1.evaluation()).hasValueSatisfying(evaluation -> {
            assertThat(evaluation.policyVersionId()).isEqualTo(monitoring);
            assertThat(evaluation.policyVersion()).isEqualTo(1);
            assertThat(evaluation.engine()).isEqualTo(RiskEngine.RISK_ENGINE_V1);
            assertThat(evaluation.signalScore()).isZero();
            assertThat(evaluation.inputsSha256()).isEqualTo(CanonicalEvaluation.sha256(evaluation.inputsCanonical()));
        });
        assertThat(v1.correlationId()).isEqualTo(FLOW.value());
        assertThat(profiles.head(party)).hasValueSatisfying(head -> {
            assertThat(head.currentVersion()).isEqualTo(1);
            assertThat(head.rating()).contains(RiskRating.LOW);
            assertThat(head.nextRecomputeAt()).isEqualTo(profiles.now.plus(CADENCE));
        });

        profiles.log.clear();
        ProfileRecompute.Outcome again = recompute.recompute(UOW, party, FLOW);
        assertThat(again.kind()).isEqualTo(ProfileRecompute.Kind.UNCHANGED);
        assertThat(again.version()).isEqualTo(1);
        assertThat(profiles.versions(UOW, party)).hasSize(1);
        assertThat(profiles.log).as("the permit alone moves").containsExactly("lock " + party,
                "restamp " + party + " evaluated", "versions " + party);
        assertThat(outbox.envelopes).as("one announcement - the first version's").hasSize(1);
    }

    @Test
    @DisplayName("a rating that moves writes the next version and announces it - the party, the rating's code and the"
            + " version, nothing else (INV-RSK-08)")
    void aMovedRatingIsVersionedAndAnnounced() {
        policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        UUID party = IDS.next();
        recompute.recompute(UOW, party, FLOW);

        screening.hit = Optional.of(true);
        ProfileRecompute.Outcome medium = recompute.recompute(UOW, party, FLOW);
        assertThat(medium.kind()).isEqualTo(ProfileRecompute.Kind.CHANGED);
        assertThat(medium.version()).isEqualTo(2);
        assertThat(medium.rating()).as("SCREENING_HIT weighs 600: the MEDIUM band").contains(RiskRating.MEDIUM);
        CustomerRiskProfileVersion v2 = profiles.versions(UOW, party).get(1);
        assertThat(v2.reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(v2.evaluation().orElseThrow().signalScore()).isEqualTo(600);

        screening.hit = Optional.of(false);
        assertThat(recompute.recompute(UOW, party, FLOW).rating()).contains(RiskRating.LOW);
        assertThat(profiles.versions(UOW, party)).extracting(CustomerRiskProfileVersion::version).containsExactly(1, 2, 3);

        assertThat(outbox.envelopes).hasSize(3);
        EventEnvelope announced = outbox.envelopes.get(1);
        assertThat(announced.eventType()).isEqualTo("risk.CustomerRiskRatingChanged");
        assertThat(announced.eventVersion()).isEqualTo(1);
        assertThat(announced.producer()).isEqualTo("risk");
        assertThat(announced.aggregateType()).isEqualTo("customer_risk_profile");
        assertThat(announced.aggregateId()).isEqualTo(CustomerRiskProfileId.of(party));
        assertThat(announced.correlationId()).isEqualTo(FLOW);
        assertThat(EventPayload.fields(outbox.payloads.get(1)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("party", party.toString(), "rating", "MEDIUM", "version", "2"));
    }

    @Test
    @DisplayName("PROHIBITED is sticky: a recompute evaluates nothing, writes nothing and announces nothing - only a"
            + " disposition lifts it (INV-RSK-07)")
    void prohibitedIsNeverClearedByARecompute() {
        policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        UUID party = IDS.next();
        profiles.seedDisposition(party, RiskRating.PROHIBITED);
        screening.hit = Optional.of(false);

        ProfileRecompute.Outcome outcome = recompute.recompute(UOW, party, FLOW);
        assertThat(outcome.kind()).isEqualTo(ProfileRecompute.Kind.PROHIBITED_STANDS);
        assertThat(outcome.rating()).contains(RiskRating.PROHIBITED);
        assertThat(screening.reads).as("nothing evaluated - not even read").isZero();
        assertThat(profiles.versions(UOW, party)).hasSize(1);
        assertThat(profiles.head(party).orElseThrow().rating()).contains(RiskRating.PROHIBITED);
        assertThat(outbox.envelopes).isEmpty();
    }

    @Test
    @DisplayName("the engine never yields PROHIBITED and a recompute's version can never carry it - the bands, the"
            + " verdict and the version each refuse it")
    void noRecomputeEverSetsProhibited() {
        assertThatExceptionOfType(RiskPolicy.PolicyInvalid.class)
                .isThrownBy(() -> new RatingBand(0, ScoreBand.CEILING, RiskRating.PROHIBITED));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new CustomerRiskProfileVersion(
                IDS.next(), 1, RiskRating.PROHIBITED, ProfileCause.RECOMPUTE, List.of(),
                Optional.of(evaluation()), Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).as("a disposition names its disposition")
                .isThrownBy(() -> new CustomerRiskProfileVersion(IDS.next(), 1, RiskRating.PROHIBITED,
                        ProfileCause.DISPOSITION, List.of(), Optional.empty(), Optional.empty(), "flow", Optional.empty()));
        assertThatExceptionOfType(IllegalArgumentException.class).as("a recompute carries its evaluation")
                .isThrownBy(() -> new CustomerRiskProfileVersion(IDS.next(), 1, RiskRating.LOW, ProfileCause.RECOMPUTE,
                        List.of(), Optional.empty(), Optional.empty(), "flow", Optional.empty()));
    }

    @Test
    @DisplayName("no MONITORING version ACTIVE: nothing is judged - no version, no announcement, the party stays"
            + " unassessed and is not rated LOW by default (DESIGN-007 D2)")
    void noActivePolicyJudgesNothing() {
        UUID party = IDS.next();
        ProfileRecompute.Outcome outcome = recompute.recompute(UOW, party, FLOW);
        assertThat(outcome.kind()).isEqualTo(ProfileRecompute.Kind.NOT_JUDGED);
        assertThat(outcome.version()).isZero();
        assertThat(outcome.rating()).isEmpty();
        assertThat(profiles.versions(UOW, party)).isEmpty();
        assertThat(profiles.head(party).orElseThrow().assessed()).isFalse();
        assertThat(outbox.envelopes).isEmpty();
    }

    @Test
    @DisplayName("the inputs sealed: the party as the subject, its screening clearance, every other reading ABSENT - and"
            + " a version replays its rating from its own sealed inputs under its pinned version (INV-HIST-04)")
    void theInputsAreSealedAndReplay() {
        RiskPolicy high = monitoringWithScreeningWeight(800);
        policies.activate(high);
        UUID party = IDS.next();
        screening.hit = Optional.of(true);
        recompute.recompute(UOW, party, FLOW);

        CustomerRiskProfileVersion v1 = profiles.versions(UOW, party).get(0);
        assertThat(v1.rating()).isEqualTo(RiskRating.HIGH);
        EvaluationInputs sealed = CanonicalEvaluation.parseInputs(v1.evaluation().orElseThrow().inputsCanonical());
        assertThat(sealed.operation()).isEqualTo(RiskOperation.MONITORING);
        assertThat(sealed.subjects()).containsExactly(Map.entry(SubjectKind.PARTY, SignalSubject.of(SubjectKind.PARTY, party)));
        assertThat(sealed.screeningHit().value()).contains(true);
        assertThat(sealed.amount().isPresent()).isFalse();
        assertThat(sealed.velocity()).isEmpty();
        assertThat(sealed.profileRating().isPresent()).as("the profile never reads itself").isFalse();
        assertThat(sealed.monitoring()).isEqualTo(MonitoringReadings.none());
        assertThat(RiskEngineV1.evaluate(high, sealed).rating()).as("the replay").contains(v1.rating());
    }

    @Test
    @DisplayName("the sweep's leg: the pin first (R1), then one due head claimed (R8); none due answers empty; a recompute"
            + " re-stamps the permit so the head is not due again until the cadence")
    void theSweepPinsThenClaims() {
        policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        assertThat(recompute.recomputeNextDue(UOW)).as("no head at all").isEmpty();

        UUID party = IDS.next();
        profiles.pullForward(UOW, party);
        policies.log.clear();
        profiles.log.clear();
        Optional<ProfileRecompute.Outcome> outcome = recompute.recomputeNextDue(UOW);
        assertThat(outcome).hasValueSatisfying(o -> {
            assertThat(o.partyId()).isEqualTo(party);
            assertThat(o.kind()).isEqualTo(ProfileRecompute.Kind.CHANGED);
        });
        assertThat(policies.log).first().as("R1 - the pin").isEqualTo("share MONITORING");
        assertThat(profiles.log).first().as("then R8 - the claim").isEqualTo("claim");
        assertThat(recompute.recomputeNextDue(UOW)).as("re-stamped a cadence ahead: not due").isEmpty();

        profiles.pullForward(UOW, party);
        assertThat(recompute.recomputeNextDue(UOW)).as("a hint makes it due again")
                .hasValueSatisfying(o -> assertThat(o.kind()).isEqualTo(ProfileRecompute.Kind.UNCHANGED));
    }

    @Test
    @DisplayName("a recompute that fails names its party and writes nothing - the sweep defers that party's permit by the"
            + " backoff, so it never stands first in every claim")
    void aFailedRecomputeNamesItsPartyAndIsDeferred() {
        policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        UUID party = IDS.next();
        ProfileRecompute failing = new ProfileRecompute(new RiskPolicies(policies), profiles,
                inputs((unitOfWork, p) -> {
                    throw new IllegalStateException("the clearance could not be read");
                }), AlertRaiser.NONE, outbox, IDS, CLOCK, CADENCE);
        assertThatExceptionOfType(ProfileRecompute.RecomputeFailed.class)
                .isThrownBy(() -> failing.recompute(UOW, party, FLOW))
                .satisfies(failed -> assertThat(failed.partyId()).isEqualTo(party))
                .withMessageNotContaining("clearance");
        assertThat(profiles.versions(UOW, party)).isEmpty();
        assertThat(outbox.envelopes).isEmpty();

        failing.defer(UOW, party);
        assertThat(profiles.head(party).orElseThrow().nextRecomputeAt())
                .isEqualTo(profiles.now.plus(ProfileRecompute.FAILURE_BACKOFF));
    }

    @Test
    @DisplayName("the cadence is judged first: zero or negative refuses to build")
    void theCadenceIsPositive() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new ProfileRecompute(
                new RiskPolicies(policies), profiles, inputs(screening), AlertRaiser.NONE, outbox, IDS, CLOCK,
                Duration.ZERO));
    }

    /**
     * The profile's inputs over {@code screening} alone: no identity placed, so the account-takeover anchors stay
     * {@code ABSENT} (`P13-TSK-013` - its own suites, {@code AtoSequenceTest}, place one).
     */
    static ProfileInputs inputs(ScreeningClearances screening) {
        return new ProfileInputs(screening, new SignalReadings(new SignalReadingsTest.InMemoryStore()),
                RiskPartyIdentities.NONE, new VelocityWindows(new FakeVelocityWindowStore(CLOCK.instant())));
    }

    // -----------------------------------------------------------------

    static RiskPolicy monitoringWithScreeningWeight(int weight) {
        return new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, RiskPolicyFixtures.EUR,
                new RiskPolicyFixtures.Rules().weighted(RuleKind.SCREENING_HIT, Map.of(), weight).list(), List.of(),
                List.of(new RatingBand(0, 299, RiskRating.LOW), new RatingBand(300, 699, RiskRating.MEDIUM),
                        new RatingBand(700, 1000, RiskRating.HIGH)),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static CustomerRiskProfileVersion.Evaluation evaluation() {
        String canonical = CanonicalEvaluation.render(EvaluationInputs.builder(RiskOperation.MONITORING).build());
        return new CustomerRiskProfileVersion.Evaluation(RiskPolicyVersionId.next(IDS), 1, RiskEngine.RISK_ENGINE_V1, 0,
                canonical, CanonicalEvaluation.sha256(canonical));
    }

    /** A party's latest clearance, as scripted - empty when never screened; counts its reads. */
    static final class Screening implements ScreeningClearances {
        Optional<Boolean> hit = Optional.empty();
        int reads;

        @Override
        public Optional<Clearance> latestFor(Connection unitOfWork, UUID partyId) {
            reads++;
            return hit.map(h -> new Clearance(UUID.randomUUID(), h, Instant.parse("2026-10-22T09:00:00Z")));
        }
    }

    /** The policy store with exactly one ACTIVE version per operation, activated by the suite. */
    static final class PolicyStore extends RiskPolicyAdministrationTest.FakeRiskPolicyStore {
        private int versions;

        RiskPolicyVersionId activate(RiskPolicy policy) {
            lockActive(UOW, policy.operation()).ifPresent(active -> retire(UOW, active.id()));
            RiskPolicyVersionId id = RiskPolicyVersionId.next(IDS);
            insertProposal(UOW, new NewProposal(id, ++versions, policy, new Actor("alice", ActorType.EMPLOYEE),
                    "fixture", CorrelationId.of("policy"), IDS.next()));
            decide(UOW, id, RiskPolicyVersionStatus.ACTIVE, new Actor("bob", ActorType.EMPLOYEE), "fixture");
            log.clear();
            return id;
        }
    }
}
