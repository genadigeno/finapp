package com.finapp.risk;

import static com.finapp.risk.EvaluationWorld.IDS;
import static com.finapp.risk.EvaluationWorld.UOW;
import static com.finapp.risk.EvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six account-takeover rule kinds - each one's parameters, its reading and its source (`P13-TSK-013`;
 * {@code PHASE_13_PLAN.md} sections 12.2, 12.5, 12.6; {@code INV-RSK-04}): judged through the real T-eval over fakes.
 * {@code RECENT_CREDENTIAL_CHANGE} and {@code RECENT_RECOVERY} read the acting identity's signals (judged and monitored
 * alike); {@code NEW_PAYEE} an account destination's first counted window and a beneficiary's registration;
 * {@code NEW_DEVICE} the device hash's first counted window, {@code ABSENT} with none carried; {@code
 * SESSION_ASSURANCE_BELOW} the session; {@code RECENT_AUTH_FAILURES} has no source and is {@code ABSENT}. An absent
 * reading never fires and says so on its hit.
 */
@DisplayName("the account-takeover rule kinds: parameters, readings, sources (P13-TSK-013)")
class AtoRulesTest {

    private static final Instant NOW = EvaluationWorld.CLOCK.instant();

    @Test
    @DisplayName("each kind's parameter schema - the anchors' lookback in hours, the novelties' in days, the session's"
            + " assurance, the failures' window and count - and the anchors admitted for MONITORING, the novelties not")
    void theParameters() {
        assertThat(RuleKind.RECENT_CREDENTIAL_CHANGE.parameters()).containsExactly(
                new RuleKind.Parameter("lookback_hours", RuleKind.ParameterType.HOURS));
        assertThat(RuleKind.RECENT_RECOVERY.parameters()).containsExactly(
                new RuleKind.Parameter("lookback_hours", RuleKind.ParameterType.HOURS));
        assertThat(RuleKind.NEW_PAYEE.parameters()).containsExactly(
                new RuleKind.Parameter("lookback_days", RuleKind.ParameterType.DAYS));
        assertThat(RuleKind.NEW_DEVICE.parameters()).containsExactly(
                new RuleKind.Parameter("lookback_days", RuleKind.ParameterType.DAYS));
        assertThat(RuleKind.SESSION_ASSURANCE_BELOW.parameters()).containsExactly(
                new RuleKind.Parameter("assurance", RuleKind.ParameterType.ASSURANCE));
        assertThat(RuleKind.RECENT_AUTH_FAILURES.parameters()).extracting(RuleKind.Parameter::name)
                .containsExactly("window", "threshold_count");
        for (RuleKind anchor : AtoSequence.ANCHORS) {
            assertThat(anchor.operations()).as(anchor.name()).contains(RiskOperation.MONITORING,
                    RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CROSS_BORDER);
        }
        for (RuleKind judgedOnly : List.of(RuleKind.NEW_PAYEE, RuleKind.NEW_DEVICE, RuleKind.SESSION_ASSURANCE_BELOW,
                RuleKind.RECENT_AUTH_FAILURES)) {
            assertThat(judgedOnly.appliesTo(RiskOperation.MONITORING)).as(judgedOnly.name()).isFalse();
        }
        RiskPolicy monitoring = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        assertThat(monitoring.rules()).extracting(RiskRule::kind).contains(RuleKind.RECENT_RECOVERY)
                .doesNotContain(RuleKind.RECENT_CREDENTIAL_CHANGE);
        assertThatThrownBy(() -> new RiskPolicy(RiskOperation.MONITORING, RiskEngine.RISK_ENGINE_V1, EvaluationWorld.EUR,
                List.of(RiskRule.weighted(1, RuleKind.NEW_PAYEE, RuleParameters.of(RuleKind.NEW_PAYEE,
                        Map.of("lookback_days", RiskPolicyFixtures.days(30))), 100)),
                List.of(), monitoring.ratingBands(), List.of(), Optional.empty(), Optional.empty(), Optional.empty()))
                .as("a novelty is a judged movement's reading, never the sweep's").hasMessageContaining("NEW_PAYEE");
    }

    @Test
    @DisplayName("RECENT_CREDENTIAL_CHANGE reads the acting identity's latest CredentialChanged in whole hours: 3 h fires"
            + " the withdrawal's HOLD; none is NEVER (not fired); another identity's is not this one's; no identity is"
            + " ABSENT")
    void theCredentialChange() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        UUID identity = IDS.next();
        world.signalStore.add(signal(SignalKind.CREDENTIAL_CHANGED, identity, NOW.minus(Duration.ofMinutes(185))));

        assertThat(hit(world, withdrawal(Optional.of(identity)), RuleKind.RECENT_CREDENTIAL_CHANGE))
                .satisfies(hit -> {
                    assertThat(hit.outcome()).isEqualTo(RuleOutcome.FIRED);
                    assertThat(hit.judged()).isEqualTo("credentialChange:hoursAgo=3");
                    assertThat(hit.hardDecision()).contains(RiskDecision.HOLD);
                });
        assertThat(hit(world, withdrawal(Optional.of(IDS.next())), RuleKind.RECENT_CREDENTIAL_CHANGE))
                .satisfies(hit -> {
                    assertThat(hit.outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
                    assertThat(hit.judged()).isEqualTo("credentialChange:hoursAgo=NEVER");
                });
        assertThat(hit(world, withdrawal(Optional.empty()), RuleKind.RECENT_CREDENTIAL_CHANGE).outcome())
                .as("a system flow names no identity: we did not look").isEqualTo(RuleOutcome.ABSENT);
    }

    @Test
    @DisplayName("RECENT_RECOVERY reads the completed recovery only - a recovery merely initiated set no credential")
    void theRecovery() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        UUID initiated = IDS.next();
        world.signalStore.add(signal(SignalKind.RECOVERY_INITIATED, initiated, NOW.minus(Duration.ofHours(2))));
        assertThat(hit(world, withdrawal(Optional.of(initiated)), RuleKind.RECENT_RECOVERY).judged())
                .isEqualTo("recovery:hoursAgo=NEVER");

        UUID completed = IDS.next();
        world.signalStore.add(signal(SignalKind.RECOVERY_COMPLETED, completed, NOW.minus(Duration.ofHours(10))));
        RuleHit hit = hit(world, withdrawal(Optional.of(completed)), RuleKind.RECENT_RECOVERY);
        assertThat(hit.outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(hit.judged()).isEqualTo("recovery:hoursAgo=10");
        assertThat(hit.contribution()).isEqualTo(300);
    }

    @Test
    @DisplayName("NEW_PAYEE for an account: new until its first counted movement - never counted is NEVER; counted today"
            + " is 0 days and still new; first counted 40 days ago is not; a refused movement counts nothing, so its"
            + " payee stays new")
    void anAccountPayee() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        UUID payee = IDS.next();
        assertThat(hit(world, transfer(payee, eur(10_000_00)), RuleKind.NEW_PAYEE).judged())
                .as("the DENY at EUR 10,000 - refused, counted nothing").isEqualTo("payee:daysSinceFirstSeen=NEVER");
        RuleHit first = hit(world, transfer(payee, eur(10_00)), RuleKind.NEW_PAYEE);
        assertThat(first.outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(first.judged()).as("the refusal before counted nothing").isEqualTo("payee:daysSinceFirstSeen=NEVER");
        RuleHit second = hit(world, transfer(payee, eur(10_00)), RuleKind.NEW_PAYEE);
        assertThat(second.judged()).as("its first counted window is this hour's").isEqualTo("payee:daysSinceFirstSeen=0");
        assertThat(second.outcome()).isEqualTo(RuleOutcome.FIRED);

        UUID known = IDS.next();
        countedAt(world, VelocityDimension.DESTINATION, known.toString(), NOW.minus(Duration.ofDays(40)));
        RuleHit old = hit(world, transfer(known, eur(10_00)), RuleKind.NEW_PAYEE);
        assertThat(old.outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(old.judged()).isEqualTo("payee:daysSinceFirstSeen=40");
    }

    @Test
    @DisplayName("NEW_PAYEE for a beneficiary: its registration's age (crossborder's BeneficiaryRegistered)")
    void aBeneficiaryPayee() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CROSS_BORDER);
        UUID beneficiary = IDS.next();
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.BENEFICIARY_REGISTERED,
                SignalSubject.of(SubjectKind.BENEFICIARY, beneficiary), source("crossborder.BeneficiaryRegistered"),
                NOW.minus(Duration.ofDays(45)),
                SignalAttributes.of(SignalKind.BENEFICIARY_REGISTERED, Map.of("country", "GB", "currency", "GBP"))));
        RuleHit hit = hit(world, EvaluationWorld.request(RiskOperation.CROSS_BORDER, eur(20_00), IDS.next())
                .sourceAccount(IDS.next()).destinationBeneficiary(beneficiary).build(), RuleKind.NEW_PAYEE);
        assertThat(hit.outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(hit.judged()).isEqualTo("payee:daysSinceFirstSeen=45");
    }

    @Test
    @DisplayName("NEW_DEVICE only by identity's hash: no device carried is ABSENT on a transfer and a withdrawal alike;"
            + " a hash never counted is NEVER and new; first counted 45 days ago is not")
    void aDevice() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        world.activate(RiskOperation.WITHDRAWAL);
        RuleHit none = hit(world, transfer(IDS.next(), eur(10_00)), RuleKind.NEW_DEVICE);
        assertThat(none.outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(none.judged()).isEqualTo("ABSENT");
        assertThat(none.contribution()).isZero();
        assertThat(hit(world, withdrawal(Optional.of(IDS.next())), RuleKind.NEW_DEVICE).outcome())
                .isEqualTo(RuleOutcome.ABSENT);

        DeviceHash fresh = DeviceHash.of("device-" + UUID.randomUUID());
        RuleHit novel = hit(world, EvaluationWorld.request(RiskOperation.TRANSFER, eur(10_00), IDS.next())
                .sourceAccount(IDS.next()).destinationAccount(IDS.next()).device(fresh).build(), RuleKind.NEW_DEVICE);
        assertThat(novel.outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(novel.judged()).isEqualTo("device:daysSinceFirstSeen=NEVER");

        DeviceHash known = DeviceHash.of("device-" + UUID.randomUUID());
        countedAt(world, VelocityDimension.DEVICE, known.value(), NOW.minus(Duration.ofDays(45)));
        RuleHit old = hit(world, EvaluationWorld.request(RiskOperation.TRANSFER, eur(10_00), IDS.next())
                .sourceAccount(IDS.next()).destinationAccount(IDS.next()).device(known).build(), RuleKind.NEW_DEVICE);
        assertThat(old.outcome()).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(old.judged()).isEqualTo("device:daysSinceFirstSeen=45");
    }

    @Test
    @DisplayName("SESSION_ASSURANCE_BELOW reads the session's assurance: a password session fires, a multi-factor one"
            + " does not, no session is ABSENT")
    void theSessionAssurance() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        world.external = new RiskInputs.Readings(Reading.of(RiskAssurance.SINGLE_FACTOR), Reading.absent(),
                Reading.absent(), Reading.absent(), Map.of());
        RuleHit single = hit(world, transfer(IDS.next(), eur(10_00)), RuleKind.SESSION_ASSURANCE_BELOW);
        assertThat(single.outcome()).isEqualTo(RuleOutcome.FIRED);
        assertThat(single.judged()).isEqualTo("assurance=SINGLE_FACTOR");
        world.external = new RiskInputs.Readings(Reading.of(RiskAssurance.MULTI_FACTOR), Reading.absent(),
                Reading.absent(), Reading.absent(), Map.of());
        assertThat(hit(world, transfer(IDS.next(), eur(10_00)), RuleKind.SESSION_ASSURANCE_BELOW).outcome())
                .isEqualTo(RuleOutcome.NOT_FIRED);
        world.external = RiskInputs.Readings.none();
        assertThat(hit(world, transfer(IDS.next(), eur(10_00)), RuleKind.SESSION_ASSURANCE_BELOW).outcome())
                .isEqualTo(RuleOutcome.ABSENT);
    }

    @Test
    @DisplayName("RECENT_AUTH_FAILURES has no source (identity's failure event names no identity, its throttle counts"
            + " consecutive failures): ABSENT, never fired, never a contribution")
    void theAuthenticationFailures() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CARD_PAYMENT);
        RuleHit hit = hit(world, EvaluationWorld.request(RiskOperation.CARD_PAYMENT, eur(10_00), IDS.next())
                .sourceAccount(IDS.next()).merchant(IDS.next()).build(), RuleKind.RECENT_AUTH_FAILURES);
        assertThat(hit.outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(hit.contribution()).isZero();
        assertThat(hit.hardDecision()).isEmpty();
    }

    // ------------------------------------------------------------------ the shapes

    private static RuleHit hit(EvaluationWorld world, RiskEvaluationRequest request, RuleKind kind) {
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, request);
        RiskAssessment sealed = world.assessmentStore.read(UOW, assessed.assessmentId()).orElseThrow();
        return sealed.hits().stream().filter(hit -> hit.kind() == kind).findFirst().orElseThrow();
    }

    private static RiskEvaluationRequest withdrawal(Optional<UUID> identity) {
        RiskEvaluationRequest.Builder request = EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(10_00), IDS.next())
                .sourceAccount(IDS.next());
        identity.ifPresent(id -> request.identity(id).actor(new Actor(id.toString(), ActorType.CUSTOMER)));
        return request.build();
    }

    private static RiskEvaluationRequest transfer(UUID destination, com.finapp.sharedkernel.money.Money amount) {
        return EvaluationWorld.request(RiskOperation.TRANSFER, amount, IDS.next()).sourceAccount(IDS.next())
                .destinationAccount(destination).build();
    }

    /** One movement counted for {@code subject} in {@code dimension}'s hour window at {@code at}. */
    static void countedAt(EvaluationWorld world, VelocityDimension dimension, String subject, Instant at) {
        VelocityWindowKey key = new VelocityWindowKey(dimension, subject, WindowKind.H1,
                WindowStarts.start(WindowKind.H1, at));
        world.windowStore.delegate.plant(new VelocityWindow(key, 1, eur(1_00), at));
    }

    static RiskSignal signal(SignalKind kind, UUID identity, Instant observedAt) {
        SignalAttributes attributes = kind.attributes().isEmpty() ? SignalAttributes.none()
                : SignalAttributes.of(kind, Map.of("request_ref", IDS.next().toString(),
                        "channel_ref", IDS.next().toString()));
        return new RiskSignal(RiskSignalId.next(IDS), kind, SignalSubject.of(SubjectKind.IDENTITY, identity),
                source(kind.sourceEventType()), observedAt, attributes);
    }

    static RiskSignal.SourceEvent source(String type) {
        return new RiskSignal.SourceEvent(EventId.next(IDS), type, 1, type.substring(0, type.indexOf('.')),
                CorrelationId.of("flow"), CausationId.of("cause"));
    }
}
