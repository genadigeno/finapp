package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.IDS;
import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The account-takeover sequence and its alert (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 7.1, 12.6, scenario
 * 11; {@code INV-RSK-04}, {@code INV-RSK-06}) - the pure detector over a verdict's hits; the detector over generated
 * signal sequences, property-based (seeded, so every run judges the same cases): an anchor inside its lookback with no
 * refuting continuation always shows the sequence, outside never, and the alert is always keyed by the latest anchor's
 * own day; and the retrospective leg over {@link ProfileRecompute} - a recovery without a movement alerts once, a second
 * sweep and T-eval's raise of the same sequence meet the same row. The database's arbiters are the owed
 * {@code AccountTakeoverDatabaseTest}'s.
 */
@DisplayName("the account-takeover sequence: the detector, generated sequences, the retrospective leg (P13-TSK-013)")
class AtoSequenceTest {

    private static final Instant NOW = EvaluationWorld.CLOCK.instant();
    private static final CorrelationId FLOW = CorrelationId.of("ato-flow");

    // ------------------------------------------------------------------ the detector, case by case

    @Test
    @DisplayName("the sequence from the hits: an anchor fired continues through a fired or absent continuation, is broken"
            + " by one judged and not fired, needs a movement (a purchase is none) - and in the sweep the anchor alone")
    void theSequenceFromTheHits() {
        RuleHit change = fired(1, RuleKind.RECENT_CREDENTIAL_CHANGE);
        RuleHit recovery = fired(2, RuleKind.RECENT_RECOVERY);
        assertThat(AtoSequence.anchorsShown(RiskOperation.WITHDRAWAL, List.of(change, absent(3, RuleKind.NEW_DEVICE))))
                .containsExactly(RuleKind.RECENT_CREDENTIAL_CHANGE);
        assertThat(AtoSequence.anchorsShown(RiskOperation.TRANSFER, List.of(recovery, fired(3, RuleKind.NEW_PAYEE),
                absent(4, RuleKind.NEW_DEVICE)))).containsExactly(RuleKind.RECENT_RECOVERY);
        assertThat(AtoSequence.anchorsShown(RiskOperation.TRANSFER, List.of(recovery, notFired(3, RuleKind.NEW_PAYEE))))
                .as("a payee seen before breaks it").isEmpty();
        assertThat(AtoSequence.anchorsShown(RiskOperation.TRANSFER, List.of(recovery, fired(3, RuleKind.NEW_PAYEE),
                notFired(4, RuleKind.NEW_DEVICE)))).as("a device seen before breaks it").isEmpty();
        assertThat(AtoSequence.anchorsShown(RiskOperation.TRANSFER, List.of(recovery, notFired(3, RuleKind.NEW_PAYEE),
                fired(4, RuleKind.NEW_PAYEE)))).as("one rule of the kind fired: it continues")
                .containsExactly(RuleKind.RECENT_RECOVERY);
        assertThat(AtoSequence.anchorsShown(RiskOperation.CROSS_BORDER, List.of(change, recovery)))
                .containsExactly(RuleKind.RECENT_CREDENTIAL_CHANGE, RuleKind.RECENT_RECOVERY);
        assertThat(AtoSequence.anchorsShown(RiskOperation.WITHDRAWAL, List.of(notFired(1, RuleKind.RECENT_CREDENTIAL_CHANGE),
                fired(2, RuleKind.NEW_DEVICE)))).as("no anchor, no sequence").isEmpty();
        assertThat(AtoSequence.anchorsShown(RiskOperation.CARD_PAYMENT, List.of(change)))
                .as("a purchase at a merchant is -014's").isEmpty();
        assertThat(AtoSequence.anchorsShown(RiskOperation.WALLET_PAYMENT, List.of(change))).isEmpty();
        assertThat(AtoSequence.anchorsShown(RiskOperation.MONITORING, List.of(recovery, notFired(2, RuleKind.NEW_PAYEE))))
                .as("from signals alone the anchor is the sequence").containsExactly(RuleKind.RECENT_RECOVERY);
        assertThat(AtoSequence.anchorSignal(RuleKind.RECENT_CREDENTIAL_CHANGE)).isEqualTo(SignalKind.CREDENTIAL_CHANGED);
        assertThat(AtoSequence.anchorSignal(RuleKind.RECENT_RECOVERY)).isEqualTo(SignalKind.RECOVERY_COMPLETED);
    }

    // ------------------------------------------------------------------ the detector over generated sequences

    private enum Seen { NEW, KNOWN, ABSENT }

    @Test
    @DisplayName("property, 2,000 seeded sequences: inside the lookback with no refuting continuation always fires, outside"
            + " never; the alert keyed (party, anchor, the latest anchor's day) and naming that signal - for a movement and"
            + " for the sweep")
    void insideTheLookbackAlwaysOutsideNever() {
        Random random = new Random(13_013L);
        List<SignalKind> kinds = List.of(SignalKind.CREDENTIAL_CHANGED, SignalKind.RECOVERY_COMPLETED,
                SignalKind.RECOVERY_INITIATED, SignalKind.IDENTITY_SUSPENDED);
        int shownSomewhere = 0;
        int refutedSomewhere = 0;
        for (int round = 0; round < 2_000; round++) {
            UUID identity = IDS.next();
            UUID party = IDS.next();
            SignalReadingsTest.InMemoryStore store = new SignalReadingsTest.InMemoryStore();
            Set<Integer> minutes = new HashSet<>();
            int signals = random.nextInt(6);
            for (int i = 0; i < signals; i++) {
                int ago = random.nextInt(400 * 60 + 120) - 120; // up to two hours "ahead", a producer's skew
                if (!minutes.add(ago)) {
                    continue;
                }
                SignalKind kind = kinds.get(random.nextInt(kinds.size()));
                store.add(kind == SignalKind.IDENTITY_SUSPENDED
                        ? new RiskSignal(RiskSignalId.next(IDS), kind, SignalSubject.of(SubjectKind.IDENTITY, identity),
                                AtoRulesTest.source(kind.sourceEventType()), NOW.minus(Duration.ofMinutes(ago)),
                                SignalAttributes.of(kind, Map.of("status", "SUSPENDED")))
                        : AtoRulesTest.signal(kind, identity, NOW.minus(Duration.ofMinutes(ago))));
            }
            Map<RuleKind, Integer> lookback = new EnumMap<>(RuleKind.class);
            lookback.put(RuleKind.RECENT_CREDENTIAL_CHANGE, 1 + random.nextInt(200));
            lookback.put(RuleKind.RECENT_RECOVERY, 1 + random.nextInt(200));
            Seen payee = Seen.values()[random.nextInt(3)];
            Seen device = Seen.values()[random.nextInt(3)];

            // The expectation, computed from the generated signals alone - never from the code under test.
            Set<RuleKind> inside = EnumSet.noneOf(RuleKind.class);
            Map<RuleKind, RiskSignal> latest = new EnumMap<>(RuleKind.class);
            for (RuleKind anchor : AtoSequence.ANCHORS) {
                Optional<RiskSignal> last = store.latest(UOW, SignalSubject.of(SubjectKind.IDENTITY, identity),
                        AtoSequence.anchorSignal(anchor));
                last.ifPresent(signal -> latest.put(anchor, signal));
                if (last.isPresent()) {
                    long ago = Math.max(0, Duration.between(last.get().observedAt(), NOW).toHours());
                    if (ago < lookback.get(anchor)) {
                        inside.add(anchor);
                    }
                }
            }
            boolean refuted = payee == Seen.KNOWN || device == Seen.KNOWN;
            Set<RuleKind> expected = refuted ? Set.of() : inside;

            Verdict judged = RiskEngineV1.evaluate(policy(RiskOperation.TRANSFER, lookback),
                    inputs(RiskOperation.TRANSFER, store, identity, lookback, payee, device));
            Verdict swept = RiskEngineV1.evaluate(policy(RiskOperation.MONITORING, lookback),
                    inputs(RiskOperation.MONITORING, store, identity, lookback, Seen.ABSENT, Seen.ABSENT));
            assertThat(AtoSequence.anchorsShown(RiskOperation.TRANSFER, judged.hits())).as("round %d", round)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(AtoSequence.anchorsShown(RiskOperation.MONITORING, swept.hits())).as("round %d swept", round)
                    .containsExactlyInAnyOrderElementsOf(inside);

            RiskAssessmentId assessment = RiskAssessmentId.next(IDS);
            RiskPolicyVersionId version = RiskPolicyVersionId.next(IDS);
            AccountTakeover.Anchors anchors = AccountTakeover.Anchors.read(UOW, new SignalReadings(store),
                    List.of(identity), lookback.keySet());
            List<NewAlert> alerts = AccountTakeover.alerts(judged, anchors, party, version, Optional.of(assessment),
                    FLOW, Optional.of(CausationId.of("command")));
            assertThat(alerts).hasSize(expected.size());
            for (NewAlert alert : alerts) {
                RuleKind anchor = alert.rule().ruleKind().orElseThrow();
                RiskSignal signal = latest.get(anchor);
                assertThat(alert.kind()).isEqualTo(AlertKind.ACCOUNT_TAKEOVER);
                assertThat(alert.severity()).isEqualTo(AlertSeverity.HIGH);
                assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, party));
                assertThat(alert.window()).isEqualTo(AlertWindow.containing(WindowKind.D1, signal.observedAt()));
                assertThat(alert.evidence().signals()).containsExactly(signal.id());
                assertThat(alert.evidence().assessments()).containsExactly(assessment);
                assertThat(alert.policyVersion()).contains(version);
            }
            List<NewAlert> retrospective = AccountTakeover.alerts(swept, anchors, party, version, Optional.empty(), FLOW,
                    Optional.empty());
            assertThat(retrospective).extracting(NewAlert::key).as("both paths, one key per anchor")
                    .containsAll(alerts.stream().map(NewAlert::key).toList());
            shownSomewhere += expected.isEmpty() ? 0 : 1;
            refutedSomewhere += refuted && !inside.isEmpty() ? 1 : 0;
        }
        assertThat(shownSomewhere).as("the generator reaches the sequence").isGreaterThan(100);
        assertThat(refutedSomewhere).as("the generator reaches a refuted sequence").isGreaterThan(100);
    }

    // ------------------------------------------------------------------ T-eval's raise

    @Test
    @DisplayName("T-eval raises after the seal and the announcement, before the case, through ONE raiseAll behind a"
            + " 250 ms savepoint of its own; a raise that fails is undone alone - the HOLD sealed, announced and cased,"
            + " the failure reported with its cause's code")
    void tEvalRaisesBehindItsOwnSavepoint() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        UUID identity = IDS.next();
        world.signalStore.add(AtoRulesTest.signal(SignalKind.CREDENTIAL_CHANGED, identity,
                NOW.minus(Duration.ofHours(2))));
        RiskEvaluation.Assessed held = world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.WITHDRAWAL,
                eur(10_00), IDS.next()).identity(identity).sourceAccount(IDS.next()).build());
        assertThat(held.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(world.journal).containsSubsequence("insert", "event risk.RiskDecisionMade", "open 250ms", "raise 1",
                "keep", "case HOLD");
        assertThat(world.raised).singleElement().satisfies(alerts -> assertThat(alerts).singleElement()
                .satisfies(alert -> assertThat(alert.evidence().assessments()).containsExactly(held.assessmentId())));

        world.clear();
        world.failRaise.push(EvaluationWorld.timeout());
        UUID another = IDS.next();
        world.signalStore.add(AtoRulesTest.signal(SignalKind.CREDENTIAL_CHANGED, another,
                NOW.minus(Duration.ofHours(2))));
        RiskEvaluation.Assessed stands = world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.WITHDRAWAL,
                eur(10_00), IDS.next()).identity(another).sourceAccount(IDS.next()).build());
        assertThat(stands.decision()).as("the decision stands").isEqualTo(RiskDecision.HOLD);
        assertThat(world.journal).containsSubsequence("insert", "event risk.RiskDecisionMade", "open 250ms", "raise 1",
                "undo", "case HOLD");
        assertThat(world.unraised).containsExactly(stands.assessmentId().value() + " TIMEOUT");
        assertThat(world.assessmentStore.read(UOW, stands.assessmentId())).isPresent();

        world.clear();
        RiskEvaluation.Assessed again = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.WITHDRAWAL, eur(10_00), IDS.next()).identity(identity).sourceAccount(IDS.next()).build());
        assertThat(again.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(world.count("raise")).as("a fresh command raises again; the store's unique answers ALREADY")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the retrospective leg

    @Test
    @DisplayName("the profile leg: a recovery without a movement alerts once - ACCOUNT_TAKEOVER on the party, the"
            + " MONITORING version's RECENT_RECOVERY, the recovery's day, the signal as evidence, OPEN; a second sweep"
            + " raises nothing new")
    void aRecoveryWithoutAMovementAlertsOnce() {
        Leg leg = new Leg();
        RiskPolicyVersionId monitoring = leg.policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        RiskSignal recovered = AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, leg.identity,
                NOW.minus(Duration.ofHours(10)));
        leg.signals.add(recovered);

        ProfileRecompute.Outcome first = leg.recompute.recompute(UOW, leg.party, FLOW);
        assertThat(first.rating()).as("the recovery's 100 alone keeps the party LOW").contains(RiskRating.LOW);
        assertThat(leg.alerts.store.all()).singleElement().satisfies(alert -> {
            assertThat(alert.kind()).isEqualTo(AlertKind.ACCOUNT_TAKEOVER);
            assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
            assertThat(alert.attachedCase()).as("-016 folds it; until then queued").isEmpty();
            assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, leg.party));
            assertThat(alert.rule()).isEqualTo(AlertRule.of(RuleKind.RECENT_RECOVERY));
            assertThat(alert.window()).isEqualTo(AlertWindow.containing(WindowKind.D1, recovered.observedAt()));
            assertThat(alert.policyVersion()).contains(monitoring);
            assertThat(alert.evidence().signals()).containsExactly(recovered.id());
            assertThat(alert.evidence().assessments()).isEmpty();
            assertThat(alert.causationId()).isEqualTo(recovered.source().id().value().toString());
        });
        assertThat(leg.alerts.events).extracting(e -> e.eventType()).containsExactly(Alerts.EVENT_TYPE);

        leg.recompute.recompute(UOW, leg.party, FLOW);
        assertThat(leg.alerts.store.all()).as("born once: the second sweep meets the row").hasSize(1);
        assertThat(leg.alerts.events).hasSize(1);
    }

    @Test
    @DisplayName("the profile leg raises nothing outside the lookback (80 h > 72 h), with no MONITORING version, or for a"
            + " party whose identities cannot be placed (the anchors ABSENT)")
    void nothingOutsideTheLookbackOrWithoutAVersion() {
        Leg outside = new Leg();
        outside.policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        outside.signals.add(AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, outside.identity,
                NOW.minus(Duration.ofHours(80))));
        outside.recompute.recompute(UOW, outside.party, FLOW);
        assertThat(outside.alerts.store.all()).isEmpty();

        Leg unjudged = new Leg();
        unjudged.signals.add(AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, unjudged.identity,
                NOW.minus(Duration.ofHours(1))));
        assertThat(unjudged.recompute.recompute(UOW, unjudged.party, FLOW).kind())
                .isEqualTo(ProfileRecompute.Kind.NOT_JUDGED);
        assertThat(unjudged.alerts.store.all()).isEmpty();

        Leg unplaced = new Leg();
        unplaced.policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        unplaced.signals.add(AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, unplaced.identity,
                NOW.minus(Duration.ofHours(1))));
        unplaced.recompute.recompute(UOW, IDS.next(), FLOW);
        assertThat(unplaced.alerts.store.all()).isEmpty();
        ProfileInputs.Read read = unplaced.inputs.read(UOW, IDS.next(), Set.of(RuleKind.RECENT_RECOVERY));
        assertThat(read.inputs().recovery().isPresent()).as("no identity placed: we did not look").isFalse();
    }

    @Test
    @DisplayName("a raise that fails fails the recompute whole: RecomputeFailed, no version written - the rating and its"
            + " alert commit together or not at all")
    void aFailedRaiseFailsTheRecomputeWhole() {
        Leg leg = new Leg();
        leg.failRaise = true;
        leg.policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
        leg.signals.add(AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, leg.identity, NOW.minus(Duration.ofHours(1))));
        assertThatExceptionOfType(ProfileRecompute.RecomputeFailed.class)
                .isThrownBy(() -> leg.recompute.recompute(UOW, leg.party, FLOW))
                .satisfies(failed -> assertThat(failed.partyId()).isEqualTo(leg.party));
        assertThat(leg.profiles.versions(UOW, leg.party)).isEmpty();
    }

    @Test
    @DisplayName("both paths meet on one row: T-eval holds the withdrawal after the recovery and raises the alert; the"
            + " sweep's raise of the same sequence answers ALREADY - one alert, one event")
    void bothPathsMeetOnOneRow() {
        Leg leg = new Leg();
        EvaluationWorld world = new EvaluationWorld();
        RiskPolicy withdrawal = withdrawalWithTheRecoveryHeld();
        world.activate(withdrawal);
        RiskSignal recovered = AtoRulesTest.signal(SignalKind.RECOVERY_COMPLETED, leg.identity,
                NOW.minus(Duration.ofHours(5)));
        world.signalStore.add(recovered);
        leg.signals.add(recovered);
        leg.policies.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));

        RiskEvaluation.Assessed held = world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.WITHDRAWAL,
                eur(10_00), leg.party).identity(leg.identity).sourceAccount(IDS.next()).build());
        assertThat(held.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(world.raised).singleElement().satisfies(raised -> assertThat(raised).hasSize(1));
        Alerts.Raised synchronous = leg.alerts.alerts.raise(UOW, world.raised.get(0).get(0));
        assertThat(synchronous.outcome()).isEqualTo(Alerts.Outcome.BORN);

        leg.recompute.recompute(UOW, leg.party, FLOW);
        assertThat(leg.alerts.store.all()).singleElement().satisfies(alert ->
                assertThat(alert.id()).isEqualTo(synchronous.alert().id()));
        assertThat(leg.alerts.events).hasSize(1);
    }

    // ------------------------------------------------------------------ the shapes

    /** The profile leg over fakes: one party holding one identity, the real alert machine. */
    private static final class Leg {
        final UUID party = IDS.next();
        final UUID identity = IDS.next();
        final CustomerRiskProfileTest.PolicyStore policies = new CustomerRiskProfileTest.PolicyStore();
        final com.finapp.risk.testing.InMemoryCustomerRiskProfileStore profiles =
                new com.finapp.risk.testing.InMemoryCustomerRiskProfileStore();
        final SignalReadingsTest.InMemoryStore signals = new SignalReadingsTest.InMemoryStore();
        final AlertWorld alerts = new AlertWorld();
        boolean failRaise;
        final ProfileInputs inputs = new ProfileInputs(new CustomerRiskProfileTest.Screening(),
                new SignalReadings(signals), (unitOfWork, partyId) -> partyId.equals(party) ? List.of(identity)
                        : List.of(), new VelocityWindows(new FakeVelocityWindowStore(NOW)));
        final ProfileRecompute recompute = new ProfileRecompute(new RiskPolicies(policies), profiles, inputs,
                (unitOfWork, raised) -> {
                    if (failRaise) {
                        throw new RiskStorageException("risk storage failed raising an alert: SQLSTATE 08006");
                    }
                    return alerts.alerts.raiseAll(unitOfWork, raised);
                }, new RiskSignalsTest.RecordingOutbox(), IDS, EvaluationWorld.CLOCK, Duration.ofDays(1));
    }

    private static RiskPolicy policy(RiskOperation operation, Map<RuleKind, Integer> lookback) {
        List<RiskRule> rules = new ArrayList<>();
        rules.add(RiskRule.weighted(1, RuleKind.RECENT_CREDENTIAL_CHANGE, RuleParameters.of(
                RuleKind.RECENT_CREDENTIAL_CHANGE, Map.of("lookback_hours",
                        RiskPolicyFixtures.hours(lookback.get(RuleKind.RECENT_CREDENTIAL_CHANGE)))), 100));
        rules.add(RiskRule.weighted(2, RuleKind.RECENT_RECOVERY, RuleParameters.of(RuleKind.RECENT_RECOVERY,
                Map.of("lookback_hours", RiskPolicyFixtures.hours(lookback.get(RuleKind.RECENT_RECOVERY)))), 100));
        if (operation == RiskOperation.MONITORING) {
            return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, EvaluationWorld.EUR, rules, List.of(),
                    List.of(new RatingBand(0, 1000, RiskRating.LOW)), List.of(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
        rules.add(RiskRule.weighted(3, RuleKind.NEW_PAYEE, RuleParameters.of(RuleKind.NEW_PAYEE,
                Map.of("lookback_days", RiskPolicyFixtures.days(30))), 100));
        rules.add(RiskRule.weighted(4, RuleKind.NEW_DEVICE, RuleParameters.of(RuleKind.NEW_DEVICE,
                Map.of("lookback_days", RiskPolicyFixtures.days(30))), 100));
        return new RiskPolicy(operation, RiskEngine.RISK_ENGINE_V1, EvaluationWorld.EUR, rules,
                List.of(new ScoreBand(0, 1000, RiskDecision.ALLOW)), List.of(),
                List.of(new FailSafeBand(0, OptionalLong.empty(), RiskDecision.ALLOW)), Optional.of(RiskAssurance.MULTI_FACTOR),
                Optional.of(eur(1_000_00)), Optional.empty());
    }

    private static EvaluationInputs inputs(RiskOperation operation, SignalReadingsTest.InMemoryStore store,
            UUID identity, Map<RuleKind, Integer> lookback, Seen payee, Seen device) {
        EvaluationInputs.Builder in = EvaluationInputs.builder(operation);
        if (operation.judgesAMovement()) {
            in.amount(eur(10_00));
        }
        AccountTakeover.Anchors.read(UOW, new SignalReadings(store), List.of(identity), lookback.keySet())
                .applyTo(in, NOW);
        seen(payee).ifPresent(in::payeeFirstSeen);
        seen(device).ifPresent(in::deviceFirstSeen);
        return in.build();
    }

    private static Optional<FirstSeen> seen(Seen seen) {
        return switch (seen) {
            case NEW -> Optional.of(FirstSeen.never());
            case KNOWN -> Optional.of(FirstSeen.daysAgo(60));
            case ABSENT -> Optional.empty();
        };
    }

    /** A withdrawal version whose recovery is a hard HOLD - the sequence held, whatever its weight. */
    private static RiskPolicy withdrawalWithTheRecoveryHeld() {
        RiskPolicy fixture = RiskPolicyFixtures.v1(RiskOperation.WITHDRAWAL);
        List<RiskRule> rules = new ArrayList<>(fixture.rules().stream().sorted(Comparator.comparingInt(RiskRule::ordinal))
                .toList());
        rules.add(RiskRule.deciding(rules.size() + 1, RuleKind.RECENT_RECOVERY, RuleParameters.of(RuleKind.RECENT_RECOVERY,
                Map.of("lookback_hours", RiskPolicyFixtures.hours(72))), RiskDecision.HOLD));
        return new RiskPolicy(fixture.operation(), fixture.engine(), fixture.currency(), rules, fixture.scoreBands(),
                fixture.ratingBands(), fixture.failSafeBands(), fixture.challengeAssurance(), fixture.overrideThreshold(),
                fixture.holdValidity());
    }

    private static RuleHit fired(int ordinal, RuleKind kind) {
        return new RuleHit(ordinal, kind, RuleOutcome.FIRED, 100, Optional.empty(), "fired");
    }

    private static RuleHit notFired(int ordinal, RuleKind kind) {
        return new RuleHit(ordinal, kind, RuleOutcome.NOT_FIRED, 0, Optional.empty(), "notFired");
    }

    private static RuleHit absent(int ordinal, RuleKind kind) {
        return new RuleHit(ordinal, kind, RuleOutcome.ABSENT, 0, Optional.empty(), RuleEvaluation.ABSENT_JUDGED);
    }
}
