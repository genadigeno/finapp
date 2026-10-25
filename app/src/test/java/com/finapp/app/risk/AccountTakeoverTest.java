package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.CLOCK;
import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalRiskDecision;
import com.finapp.payments.WithdrawalVerdict;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionKind;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertRule;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertStatus;
import com.finapp.risk.Alerts;
import com.finapp.risk.CommandRef;
import com.finapp.risk.DeviceHash;
import com.finapp.risk.ParameterValue;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RuleHit;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleOutcome;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.WindowKind;
import com.finapp.risk.WindowStarts;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Account-takeover detection through T-eval and the seams (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 12.2,
 * 12.6, scenario 11; ADR-0108, ADR-0110; {@code INV-RSK-04}, {@code INV-RSK-06}, {@code INV-RSK-08}): over a real
 * {@code RiskEvaluation} with in-memory stores and the real {@link Alerts} over risk's in-memory alert store. The full
 * sequence raises one {@code ACCOUNT_TAKEOVER} alert and the policy's {@code HOLD} stands; a partial sequence raises
 * none; the lookback's edges; the sequence judged twice raises once; a raise that fails behind its savepoint leaves the
 * evaluation, the decision and the caller's transaction intact; a device never carried never fires; the alert stays
 * {@code OPEN} (no case before `-016`) and nothing is proposed (C10's actions are `-017`'s). The database's arbiters,
 * the timeout and the concurrency are the owed {@code AccountTakeoverDatabaseTest}'s (C5).
 */
@DisplayName("account-takeover detection: the sequence held and alerted once (P13-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountTakeoverTest {

    private static final Instant NOW = CLOCK.instant();

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<LedgerAccountId, UUID> owners = new HashMap<>();
    private final RiskWithdrawalRiskDecision withdrawals = new RiskWithdrawalRiskDecision(world.evaluation,
            (unitOfWork, wallet) -> owners.get(wallet));

    /** One customer: the party, the identity, the wallet's customer account and its ledger account. */
    private record Holder(UUID party, UUID identity, UUID account, LedgerAccountId wallet) {}

    // ------------------------------------------------------------------ the full sequence

    @Test
    @DisplayName("scenario 11, hermetic: a credential change 3 h ago, then a withdrawal - the WITHDRAWAL version's HOLD"
            + " (72 h) stands, and ONE ACCOUNT_TAKEOVER alert is born OPEN: the party, RECENT_CREDENTIAL_CHANGE, the change's"
            + " day, HIGH, the version, the signal and the assessment as evidence")
    void theFullSequenceRaisesOneAlertAndThePolicyHolds() {
        RiskPolicyVersionId version = world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        RiskSignal changed = signal(SignalKind.CREDENTIAL_CHANGED, holder.identity(), NOW.minus(Duration.ofHours(3)));
        world.signalStore.add(changed);

        WithdrawalVerdict verdict = withdraw(holder, eur(10_00));
        assertThat(verdict.kind()).as("the policy decides the movement").isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(verdict.holdValidity()).contains(Duration.ofHours(72));
        RiskAssessment sealed = last();
        assertThat(sealed.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(hit(sealed, RuleKind.RECENT_CREDENTIAL_CHANGE).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit(sealed, RuleKind.NEW_DEVICE).outcome()).as("no device carried: unjudged, never refuting")
                .isEqualTo(RuleOutcome.ABSENT);

        RiskAlert alert = onlyAlert();
        assertThat(alert.kind()).isEqualTo(AlertKind.ACCOUNT_TAKEOVER);
        assertThat(alert.kind().caseKind()).contains(com.finapp.risk.CaseKind.ATO);
        assertThat(alert.severity()).isEqualTo(AlertSeverity.HIGH);
        assertThat(alert.status()).isEqualTo(AlertStatus.OPEN);
        assertThat(alert.attachedCase()).as("no case before -016: queued OPEN").isEmpty();
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.PARTY, holder.party()));
        assertThat(alert.rule()).isEqualTo(AlertRule.of(RuleKind.RECENT_CREDENTIAL_CHANGE));
        assertThat(alert.window().kind()).isEqualTo(WindowKind.D1);
        assertThat(alert.window().start()).isEqualTo(WindowStarts.start(WindowKind.D1, changed.observedAt()));
        assertThat(alert.policyVersion()).contains(version);
        assertThat(alert.evidence().signals()).containsExactly(changed.id());
        assertThat(alert.evidence().assessments()).containsExactly(sealed.id());
        assertThat(alert.evidence().journalEntries()).isEmpty();

        assertThat(world.alertsAnnounced()).isEqualTo(1);
        Map<String, String> payload = world.payloads.get(indexOf(Alerts.EVENT_TYPE));
        assertThat(payload).as("INV-RSK-08: ids and closed codes only").containsOnlyKeys("alertId", "kind", "severity",
                "subjectKind", "subjectRef");
        assertThat(payload.values()).doesNotContain("RECENT_CREDENTIAL_CHANGE", holder.identity().toString());
        assertThat(world.savepoints.subList(world.savepoints.size() - 2, world.savepoints.size()))
                .as("the raise behind a savepoint of its own, kept").containsExactly("open 250ms", "keep");
        assertThat(world.cases).as("the HOLD reaches the case opener as before (NONE in production until -016)")
                .hasSize(1);
        assertThat(world.unraised).isEmpty();
    }

    @Test
    @DisplayName("a transfer after a recovery to a payee never paid: the TRANSFER version's composite - NEW_PAYEE 250 +"
            + " RECENT_RECOVERY 200 = CHALLENGE, with the EUR 1,000 weight REVIEW - and one alert per sequence")
    void aTransferAfterARecoveryToANewPayee() {
        world.activate(RiskOperation.TRANSFER);
        Holder holder = holder();
        RiskSignal recovered = signal(SignalKind.RECOVERY_COMPLETED, holder.identity(), NOW.minus(Duration.ofHours(10)));
        world.signalStore.add(recovered);

        RiskEvaluation.Assessed small = transfer(holder, IDS.next(), eur(100_00));
        assertThat(small.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(small.reasons()).containsExactly(com.finapp.risk.ReasonCode.NEW_PAYEE,
                com.finapp.risk.ReasonCode.RECENT_RECOVERY);
        RiskEvaluation.Assessed large = transfer(holder, IDS.next(), eur(1_000_00));
        assertThat(large.decision()).as("the hard CHALLENGE under a REVIEW band: REVIEW").isEqualTo(RiskDecision.REVIEW);

        assertThat(onlyAlert()).satisfies(alert -> {
            assertThat(alert.rule()).isEqualTo(AlertRule.of(RuleKind.RECENT_RECOVERY));
            assertThat(alert.evidence().assessments()).as("the first judgement's - the second met the row")
                    .containsExactly(small.assessmentId());
        });
    }

    // ------------------------------------------------------------------ partial sequences

    @Test
    @DisplayName("a partial sequence raises none: a recovery but a payee paid 40 days ago; a new payee with no recovery;"
            + " a withdrawal with no credential change; a credential change but no new payee (a version carrying both)")
    void aPartialSequenceRaisesNone() {
        world.activate(RiskOperation.TRANSFER);
        world.activate(RiskOperation.WITHDRAWAL);

        Holder recovered = holder();
        world.signalStore.add(signal(SignalKind.RECOVERY_COMPLETED, recovered.identity(), NOW.minus(Duration.ofHours(1))));
        UUID known = IDS.next();
        world.windowStore.countedAt(VelocityDimension.DESTINATION, known.toString(), NOW.minus(Duration.ofDays(40)),
                eur(5_00));
        RiskEvaluation.Assessed toKnown = transfer(recovered, known, eur(10_00));
        assertThat(hit(sealed(toKnown), RuleKind.NEW_PAYEE).outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(hit(sealed(toKnown), RuleKind.RECENT_RECOVERY).outcome()).isEqualTo(RuleOutcome.FIRED);

        Holder quiet = holder();
        RiskEvaluation.Assessed toNew = transfer(quiet, IDS.next(), eur(10_00));
        assertThat(hit(sealed(toNew), RuleKind.NEW_PAYEE).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit(sealed(toNew), RuleKind.RECENT_RECOVERY).judged()).isEqualTo("recovery:hoursAgo=NEVER");

        assertThat(withdraw(holder(), eur(10_00))).isEqualTo(WithdrawalVerdict.permit());

        world.activate(transferWith(RuleKind.RECENT_CREDENTIAL_CHANGE));
        Holder changed = holder();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, changed.identity(), NOW.minus(Duration.ofHours(2))));
        RiskEvaluation.Assessed changedToKnown = transfer(changed, known, eur(10_00));
        assertThat(hit(sealed(changedToKnown), RuleKind.RECENT_CREDENTIAL_CHANGE).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit(sealed(changedToKnown), RuleKind.NEW_PAYEE).outcome()).isEqualTo(RuleOutcome.NOT_FIRED);

        assertThat(world.alertStore.all()).as("no sequence, no alert").isEmpty();
        assertThat(world.alertsAnnounced()).isZero();

        RiskEvaluation.Assessed changedToNew = transfer(changed, IDS.next(), eur(10_00));
        assertThat(hit(sealed(changedToNew), RuleKind.NEW_PAYEE).outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(onlyAlert().rule()).as("the positive control: the same party, a new payee")
                .isEqualTo(AlertRule.of(RuleKind.RECENT_CREDENTIAL_CHANGE));
    }

    // ------------------------------------------------------------------ the lookback

    @Test
    @DisplayName("the lookback's edges, in whole hours on the cut's instant: a credential change 23 h 59 min ago holds"
            + " and alerts, 24 h ago neither; a recovery 71 h ago alerts on a transfer, 72 h ago does not")
    void theLookbackBoundaries() {
        world.activate(RiskOperation.WITHDRAWAL);
        world.activate(RiskOperation.TRANSFER);

        Holder inside = holder();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, inside.identity(),
                NOW.minus(Duration.ofHours(24)).plusSeconds(60)));
        assertThat(withdraw(inside, eur(10_00)).kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(world.alertStore.all()).hasSize(1);

        Holder outside = holder();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, outside.identity(), NOW.minus(Duration.ofHours(24))));
        assertThat(withdraw(outside, eur(10_00))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(hit(last(), RuleKind.RECENT_CREDENTIAL_CHANGE).judged()).isEqualTo("credentialChange:hoursAgo=24");
        assertThat(world.alertStore.all()).hasSize(1);

        Holder recent = holder();
        world.signalStore.add(signal(SignalKind.RECOVERY_COMPLETED, recent.identity(), NOW.minus(Duration.ofHours(71))));
        transfer(recent, IDS.next(), eur(10_00));
        assertThat(world.alertStore.all()).hasSize(2);

        Holder stale = holder();
        world.signalStore.add(signal(SignalKind.RECOVERY_COMPLETED, stale.identity(), NOW.minus(Duration.ofHours(72))));
        transfer(stale, IDS.next(), eur(10_00));
        assertThat(hit(last(), RuleKind.RECENT_RECOVERY).outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(world.alertStore.all()).hasSize(2);
    }

    // ------------------------------------------------------------------ born once

    @Test
    @DisplayName("the same sequence judged twice raises once: two withdrawals after one credential change are one alert"
            + " (ALREADY, no second event); a retried withdrawal is answered by its seal and raises nothing")
    void theSameSequenceJudgedTwiceRaisesOnce() {
        world.activate(RiskOperation.WITHDRAWAL);
        Holder holder = holder();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, holder.identity(), NOW.minus(Duration.ofHours(1))));

        WithdrawalRiskDecision.Judged first = judged(holder, eur(10_00));
        assertThat(as(holder, () -> withdrawals.check(UNTOUCHABLE, first)).kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(withdraw(holder, eur(20_00)).kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(world.alertsAnnounced()).isEqualTo(1);
        assertThat(world.assessmentStore.all()).hasSize(2);

        int savepoints = world.savepoints.size();
        assertThat(as(holder, () -> withdrawals.check(UNTOUCHABLE, first)).kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
        assertThat(world.assessmentStore.all()).as("the retry is the seal's answer").hasSize(2);
        assertThat(world.savepoints).as("nothing evaluated, nothing raised").hasSize(savepoints);
        assertThat(world.alertStore.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ the savepoint

    @Test
    @DisplayName("a raise that fails behind its savepoint leaves the evaluation intact: the HOLD sealed and answered,"
            + " RiskDecisionMade announced, the savepoint undone, no alert, the failure reported - a timeout and any"
            + " other failure alike")
    void aFailedRaiseLeavesTheEvaluationIntact() {
        world.activate(RiskOperation.WITHDRAWAL);
        for (RuntimeException failure : List.of(RiskEvaluationWorld.timeout(),
                new com.finapp.risk.RiskStorageException("risk storage failed raising an alert: SQLSTATE 08006"))) {
            Holder holder = holder();
            world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, holder.identity(), NOW.minus(Duration.ofHours(1))));
            world.failRaise.push(failure);
            long decisions = world.decisionsAnnounced();

            WithdrawalVerdict verdict = withdraw(holder, eur(10_00));
            assertThat(verdict.kind()).isEqualTo(WithdrawalVerdict.Kind.HOLD);
            assertThat(last().decision()).isEqualTo(RiskDecision.HOLD);
            assertThat(world.decisionsAnnounced()).isEqualTo(decisions + 1);
            assertThat(world.savepoints.subList(world.savepoints.size() - 2, world.savepoints.size()))
                    .containsExactly("open 250ms", "undo");
        }
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(world.unraised).hasSize(2);
        assertThat(world.unraised.get(0)).endsWith(" TIMEOUT");
        assertThat(world.unraised.get(1)).endsWith(" READ_FAILED");
    }

    @Test
    @DisplayName("the fail-safe raises nothing: with no WITHDRAWAL version a credential change's withdrawal is the"
            + " compiled ALLOW, recorded, and no rule ran")
    void theFailSafeRaisesNothing() {
        Holder holder = holder();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, holder.identity(), NOW.minus(Duration.ofHours(1))));
        assertThat(withdraw(holder, eur(10_00))).isEqualTo(WithdrawalVerdict.permit());
        assertThat(last().hits()).isEmpty();
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(world.savepoints).doesNotContain("undo");
    }

    // ------------------------------------------------------------------ the device

    @Test
    @DisplayName("an absent device never fires: no seam carries one (-008), so NEW_DEVICE is ABSENT on every judged"
            + " withdrawal and transfer - contributing nothing, refuting nothing; a hash carried and never counted is new")
    void anAbsentDeviceNeverFires() {
        world.activate(RiskOperation.WITHDRAWAL);
        world.activate(RiskOperation.TRANSFER);
        withdraw(holder(), eur(10_00));
        transfer(holder(), IDS.next(), eur(10_00));
        for (RiskAssessment sealed : world.assessmentStore.all()) {
            RuleHit device = hit(sealed, RuleKind.NEW_DEVICE);
            assertThat(device.outcome()).isEqualTo(RuleOutcome.ABSENT);
            assertThat(device.contribution()).isZero();
            assertThat(device.judged()).isEqualTo("ABSENT");
        }
        Holder holder = holder();
        RiskEvaluation.Assessed carried = world.evaluation.evaluate(UNTOUCHABLE, transferRequest(holder, IDS.next(),
                eur(10_00)).device(DeviceHash.of("the attacker's phone")).build());
        assertThat(hit(sealed(carried), RuleKind.NEW_DEVICE).judged()).isEqualTo("device:daysSinceFirstSeen=NEVER");
    }

    // ------------------------------------------------------------------ the response

    @Test
    @DisplayName("C10: nothing is proposed here and no credential reset exists - REVOKE_SESSIONS and SUSPEND_IDENTITY are"
            + " identity's existing commands, proposable from the ATO case by -016/-017 under four eyes")
    void theResponseIsIdentitysCommandsNeverAReset() {
        assertThat(Arrays.stream(ActionKind.values()).map(Enum::name))
                .noneMatch(name -> name.contains("RESET") || name.contains("CREDENTIAL") || name.contains("PASSWORD"));
        assertThat(ActionKind.REVOKE_SESSIONS.domain()).isEqualTo(ActionKind.Domain.IDENTITY);
        assertThat(ActionKind.SUSPEND_IDENTITY.domain()).isEqualTo(ActionKind.Domain.IDENTITY);
        assertThat(AlertKind.ACCOUNT_TAKEOVER.caseKind()).contains(com.finapp.risk.CaseKind.ATO);
    }

    // ------------------------------------------------------------------ the shapes

    private Holder holder() {
        Holder holder = new Holder(IDS.next(), IDS.next(), IDS.next(), LedgerAccountId.of(IDS.next()));
        owners.put(holder.wallet(), holder.account());
        return holder;
    }

    private WithdrawalRiskDecision.Judged judged(Holder holder, Money amount) {
        return new WithdrawalRiskDecision.Judged(WithdrawalId.next(IDS), holder.party(), IDS.next(), holder.wallet(),
                IDS.next(), amount);
    }

    private WithdrawalVerdict withdraw(Holder holder, Money amount) {
        WithdrawalRiskDecision.Judged judged = judged(holder, amount);
        return as(holder, () -> withdrawals.check(UNTOUCHABLE, judged));
    }

    private <T> T as(Holder holder, Supplier<T> call) {
        try (SecurityContext.Scope actor = SecurityContext.enter(
                        new Actor(holder.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return call.get();
        }
    }

    private RiskEvaluation.Assessed transfer(Holder holder, UUID destination, Money amount) {
        return world.evaluation.evaluate(UNTOUCHABLE, transferRequest(holder, destination, amount).build());
    }

    private static RiskEvaluationRequest.Builder transferRequest(Holder holder, UUID destination, Money amount) {
        return RiskEvaluationRequest.builder(RiskOperation.TRANSFER, CommandRef.of(IDS.next()), amount, holder.party(),
                        CorrelationId.of("flow-" + UUID.randomUUID()), CausationId.of("command"))
                .identity(holder.identity()).actor(new Actor(holder.identity().toString(), ActorType.CUSTOMER))
                .sourceAccount(holder.account()).destinationAccount(destination);
    }

    /** The TRANSFER fixture with {@code anchor} (24 h) as one more weighted rule - a version carrying both elements. */
    private static RiskPolicy transferWith(RuleKind anchor) {
        RiskPolicy fixture = RiskPolicyFixtures.v1(RiskOperation.TRANSFER);
        List<RiskRule> rules = new ArrayList<>(fixture.rules());
        rules.add(RiskRule.weighted(rules.size() + 1, anchor, RuleParameters.of(anchor,
                Map.<String, ParameterValue>of("lookback_hours", RiskPolicyFixtures.hours(24))), 100));
        return new RiskPolicy(fixture.operation(), fixture.engine(), fixture.currency(), rules, fixture.scoreBands(),
                fixture.ratingBands(), fixture.failSafeBands(), fixture.challengeAssurance(), fixture.overrideThreshold(),
                fixture.holdValidity());
    }

    private RiskAssessment last() {
        return world.assessmentStore.all().get(world.assessmentStore.all().size() - 1);
    }

    private RiskAssessment sealed(RiskEvaluation.Assessed assessed) {
        return world.assessmentStore.all().stream().filter(a -> a.id().equals(assessed.assessmentId())).findFirst()
                .orElseThrow();
    }

    private static RuleHit hit(RiskAssessment sealed, RuleKind kind) {
        return sealed.hits().stream().filter(hit -> hit.kind() == kind).findFirst().orElseThrow();
    }

    private RiskAlert onlyAlert() {
        assertThat(world.alertStore.all()).hasSize(1);
        return world.alertStore.all().get(0);
    }

    private int indexOf(String eventType) {
        for (int i = 0; i < world.envelopes.size(); i++) {
            if (world.envelopes.get(i).eventType().equals(eventType)) {
                return i;
            }
        }
        throw new AssertionError("no " + eventType + " announced");
    }

    private static RiskSignal signal(SignalKind kind, UUID identity, Instant observedAt) {
        SignalAttributes attributes = kind.attributes().isEmpty() ? SignalAttributes.none()
                : SignalAttributes.of(kind, Map.of("request_ref", IDS.next().toString(),
                        "channel_ref", IDS.next().toString()));
        return new RiskSignal(RiskSignalId.next(IDS), kind, SignalSubject.of(SubjectKind.IDENTITY, identity),
                new RiskSignal.SourceEvent(EventId.next(IDS), kind.sourceEventType(), 1, "identity",
                        CorrelationId.of("flow"), CausationId.of("cause")),
                observedAt, attributes);
    }
}
