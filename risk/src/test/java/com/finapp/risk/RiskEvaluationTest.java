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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-eval and T-advise over fakes and the real engine with the policy v1 fixtures (`P13-TSK-004`; {@code PHASE_13_PLAN.md}
 * sections 7.1, 7.2, 7.6, 12.3; ADR-0108; {@code INV-RSK-01}, {@code INV-RSK-03}, {@code INV-RSK-04}): the order, every
 * decision per operation, the 0-band, a retry answered by its seal, every fail-safe sealed with its reason and cause, the
 * challenge with and without a satisfying session, the seal's digests, T-advise writing nothing, the events. The
 * concurrency and the savepoint itself are the owed {@code RiskEvaluationDatabaseTest}'s (C5).
 */
@DisplayName("T-eval: one sealed assessment per judged command, the fail-safe recorded, T-advise writing nothing"
        + " (P13-TSK-004)")
class RiskEvaluationTest {

    // ------------------------------------------------------------------ the order

    @Test
    @DisplayName("the T-eval order: find; the pin behind a savepoint with NO timeout; then behind a 250 ms savepoint the"
            + " subjects' locks (R2a), the database clock, the windows, the inputs, the count (R2); then the seal and"
            + " the event")
    void theOrder() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(100_00)));

        assertThat(assessed.decision()).isEqualTo(RiskDecision.ALLOW);
        List<String> expected = new ArrayList<>(List.of("find", "open -", "pin", "keep", "open 250ms",
                "serialise", "serialise", "serialise", "now", "windows", "inputs"));
        for (int i = 0; i < 12; i++) {
            expected.add("upsert"); // three dimensions (party, source, destination) times four window kinds
        }
        expected.addAll(List.of("keep", "insert", "event risk.RiskDecisionMade"));
        assertThat(world.journal).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ every decision

    @Test
    @DisplayName("a transfer: ALLOW counted; CHALLENGE at EUR 500 refused without a satisfying session and counted with"
            + " one; DENY at EUR 10,000 with LimitBreached; REVIEW at the eleventh movement of a day opening the case")
    void everyTransferDecision() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);

        RiskEvaluation.Assessed allow = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(100_00)));
        assertThat(allow.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(allow.permits()).isTrue();
        assertThat(allow.velocity()).isEqualTo(VelocityOutcome.COUNTED);
        assertThat(allow.reasons()).isEmpty();

        RiskEvaluation.Assessed challenged = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(600_00)));
        assertThat(challenged.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(challenged.challengeSatisfied()).contains(false);
        assertThat(challenged.permits()).isFalse();
        assertThat(challenged.refusalReason()).contains(ReasonCode.CHALLENGE_REQUIRED);
        assertThat(challenged.velocity()).as("an unsatisfied challenge counts nothing").isEqualTo(VelocityOutcome.NOT_PERMITTED);

        world.external = new RiskInputs.Readings(Reading.of(RiskAssurance.MULTI_FACTOR), Reading.absent(),
                Reading.absent(), Reading.absent(), Reading.absent(), Map.of());
        RiskEvaluation.Assessed satisfied = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(600_00)));
        assertThat(satisfied.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(satisfied.challengeSatisfied()).contains(true);
        assertThat(satisfied.permits()).isTrue();
        assertThat(satisfied.refusalReason()).isEmpty();
        assertThat(satisfied.velocity()).isEqualTo(VelocityOutcome.COUNTED);
        world.external = RiskInputs.Readings.none();

        int events = world.envelopes.size();
        RiskEvaluation.Assessed denied = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_000_00)));
        assertThat(denied.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(denied.velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
        assertThat(world.envelopes.subList(events, world.envelopes.size()))
                .extracting(e -> e.eventType()).containsExactly("risk.RiskDecisionMade", "risk.LimitBreached");
        assertThat(world.payloads.get(world.payloads.size() - 1)).containsEntry("ruleCode", "AMOUNT_BAND")
                .containsEntry("ruleOrdinal", "2").containsOnlyKeys("assessmentId", "operation", "ruleCode", "ruleOrdinal");

        UUID party = IDS.next();
        UUID source = IDS.next();
        for (int i = 0; i < 10; i++) {
            assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.TRANSFER, eur(1_00), party)
                    .sourceAccount(source).destinationAccount(IDS.next()).build()).decision())
                    .isEqualTo(RiskDecision.ALLOW);
        }
        RiskEvaluation.Assessed reviewed = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.TRANSFER, eur(1_00), party).sourceAccount(source).destinationAccount(IDS.next()).build());
        assertThat(reviewed.decision()).as("the eleventh: ten already counted today").isEqualTo(RiskDecision.REVIEW);
        assertThat(reviewed.reasons()).containsExactly(ReasonCode.VELOCITY_COUNT);
        assertThat(world.cases).extracting(CaseOpener.Decided::assessment).containsExactly(reviewed.assessmentId());
        assertThat(world.windowStore.count(VelocityDimension.SOURCE_ACCOUNT, source.toString(), WindowKind.D1))
                .as("ten counted, the refused eleventh not").isEqualTo(10);
    }

    @Test
    @DisplayName("a withdrawal: HOLD at EUR 2,500 opening its case with LimitBreached; HOLD on a credential changed within"
            + " 24 h (the signal, read on the identity); DENY with KYC not approved; CHALLENGE from the score band on a"
            + " young account")
    void everyWithdrawalDecision() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        RiskEvaluation.Assessed held = world.evaluation.evaluate(UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(2_500_00), IDS.next()).sourceAccount(IDS.next())
                        .build());
        assertThat(held.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(world.cases).hasSize(1);
        assertThat(world.envelopes).extracting(e -> e.eventType()).contains("risk.LimitBreached");

        UUID identity = IDS.next();
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.CREDENTIAL_CHANGED,
                SignalSubject.of(SubjectKind.IDENTITY, identity), source("identity.CredentialChanged", "identity"),
                EvaluationWorld.CLOCK.instant().minus(Duration.ofHours(3)), SignalAttributes.none()));
        RiskEvaluation.Assessed changed = world.evaluation.evaluate(UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(10_00), IDS.next()).identity(identity)
                        .sourceAccount(IDS.next()).build());
        assertThat(changed.decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(changed.reasons()).containsExactly(ReasonCode.RECENT_CREDENTIAL_CHANGE);
        assertThat(sealed(world, changed).inputsCanonical()).contains("NEVER").contains("\"3\"");

        world.external = new RiskInputs.Readings(Reading.absent(), Reading.absent(), Reading.of(false),
                Reading.absent(), Reading.absent(), Map.of());
        RiskEvaluation.Assessed refused = world.evaluation.evaluate(UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(10_00), IDS.next()).sourceAccount(IDS.next())
                        .build());
        assertThat(refused.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(refused.reasons()).containsExactly(ReasonCode.KYC_NOT_APPROVED);
        world.external = RiskInputs.Readings.none();

        UUID young = IDS.next();
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.ACCOUNT_OPENED,
                SignalSubject.of(SubjectKind.ACCOUNT, young), source("accounts.AccountOpened", "accounts"),
                EvaluationWorld.CLOCK.instant().minus(Duration.ofDays(2)),
                SignalAttributes.of(SignalKind.ACCOUNT_OPENED, Map.of("product", "WALLET"))));
        UUID recovered = IDS.next();
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.RECOVERY_COMPLETED,
                SignalSubject.of(SubjectKind.IDENTITY, recovered), source("identity.RecoveryCompleted", "identity"),
                EvaluationWorld.CLOCK.instant().minus(Duration.ofHours(10)),
                SignalAttributes.of(SignalKind.RECOVERY_COMPLETED,
                        Map.of("request_ref", IDS.next().toString(), "channel_ref", IDS.next().toString()))));
        RiskEvaluation.Assessed challenged = world.evaluation.evaluate(UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(10_00), IDS.next()).identity(recovered)
                        .sourceAccount(young).build());
        assertThat(challenged.decision()).as("recovery 300 + young account 200 = 500: the CHALLENGE band")
                .isEqualTo(RiskDecision.CHALLENGE);
        assertThat(challenged.reasons()).containsExactly(ReasonCode.RECENT_RECOVERY, ReasonCode.ACCOUNT_AGE_BELOW);
    }

    @Test
    @DisplayName("card and wallet payments: ALLOW; CHALLENGE at EUR 500; DENY for a suspended merchant. Cross-border:"
            + " ALLOW; DENY on a screening hit; the corridor sealed; a beneficiary first seen 2 days ago is a new payee")
    void thePaymentsAndCrossBorder() {
        for (RiskOperation payment : List.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT)) {
            EvaluationWorld world = new EvaluationWorld();
            world.activate(payment);
            UUID merchant = IDS.next();
            assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(payment, eur(20_00), IDS.next())
                    .sourceAccount(IDS.next()).merchant(merchant).build()).decision()).isEqualTo(RiskDecision.ALLOW);
            assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(payment, eur(500_00), IDS.next())
                    .sourceAccount(IDS.next()).merchant(merchant).build()).decision())
                    .isEqualTo(RiskDecision.CHALLENGE);
            world.external = new RiskInputs.Readings(Reading.absent(), Reading.absent(), Reading.absent(),
                    Reading.of(true), Reading.absent(), Map.of());
            assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(payment, eur(20_00), IDS.next())
                    .sourceAccount(IDS.next()).merchant(merchant).build()).reasons())
                    .containsExactly(ReasonCode.MERCHANT_SUSPENDED);
        }

        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.CROSS_BORDER);
        RiskEvaluation.Assessed allowed = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.CROSS_BORDER, eur(20_00), IDS.next()).sourceAccount(IDS.next())
                .destinationBeneficiary(IDS.next()).corridor("EUR-GBP-GB").build());
        assertThat(allowed.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(sealed(world, allowed).inputsCanonical()).contains("EUR-GBP-GB");

        UUID beneficiary = IDS.next();
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.BENEFICIARY_REGISTERED,
                SignalSubject.of(SubjectKind.BENEFICIARY, beneficiary),
                source("crossborder.BeneficiaryRegistered", "crossborder"),
                EvaluationWorld.CLOCK.instant().minus(Duration.ofDays(2)),
                SignalAttributes.of(SignalKind.BENEFICIARY_REGISTERED, Map.of("country", "GB", "currency", "GBP"))));
        RiskEvaluation.Assessed newPayee = world.evaluation.evaluate(UOW, EvaluationWorld.request(
                RiskOperation.CROSS_BORDER, eur(20_00), IDS.next()).sourceAccount(IDS.next())
                .destinationBeneficiary(beneficiary).build());
        assertThat(newPayee.reasons()).containsExactly(ReasonCode.NEW_PAYEE);

        world.external = new RiskInputs.Readings(Reading.absent(), Reading.of(true), Reading.absent(),
                Reading.absent(), Reading.absent(), Map.of());
        assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.CROSS_BORDER, eur(20_00),
                IDS.next()).sourceAccount(IDS.next()).build()).decision()).isEqualTo(RiskDecision.DENY);
    }

    @Test
    @DisplayName("every decision each operation admits is reached: a withdrawal's ALLOW and REVIEW (the eleventh of a"
            + " day, a case opened), a cross-border payment's CHALLENGE and REVIEW")
    void theRemainingDecisions() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        world.activate(RiskOperation.CROSS_BORDER);
        assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(10_00),
                IDS.next()).sourceAccount(IDS.next()).build()).decision()).isEqualTo(RiskDecision.ALLOW);
        for (RiskOperation operation : List.of(RiskOperation.WITHDRAWAL, RiskOperation.CROSS_BORDER)) {
            UUID party = IDS.next();
            UUID source = IDS.next();
            for (int i = 0; i < 10; i++) {
                world.evaluation.evaluate(UOW, EvaluationWorld.request(operation, eur(1_00), party).sourceAccount(source)
                        .build());
            }
            RiskEvaluation.Assessed eleventh = world.evaluation.evaluate(UOW,
                    EvaluationWorld.request(operation, eur(1_00), party).sourceAccount(source).build());
            assertThat(eleventh.decision()).as(operation.name()).isEqualTo(RiskDecision.REVIEW);
            assertThat(world.cases).extracting(CaseOpener.Decided::assessment).contains(eleventh.assessmentId());
        }
        assertThat(world.evaluation.evaluate(UOW, EvaluationWorld.request(RiskOperation.CROSS_BORDER, eur(500_00),
                IDS.next()).sourceAccount(IDS.next()).build()).decision()).isEqualTo(RiskDecision.CHALLENGE);
    }

    @Test
    @DisplayName("the 0-band decides when nothing fires: a version that challenges everything challenges a EUR 1"
            + " transfer at score 0 - no compiled ALLOW")
    void theZeroBandDecides() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(new RiskPolicy(RiskOperation.TRANSFER, RiskEngine.RISK_ENGINE_V1, EvaluationWorld.EUR,
                List.of(RiskRule.deciding(1, RuleKind.SCREENING_HIT, RuleParameters.none(RuleKind.SCREENING_HIT),
                        RiskDecision.DENY)),
                List.of(new ScoreBand(0, 1000, RiskDecision.CHALLENGE)), List.of(),
                RiskPolicyFixtures.failSafe(1_000_00, RiskDecision.ALLOW, RiskDecision.DENY),
                Optional.of(RiskAssurance.MULTI_FACTOR), Optional.of(eur(1_000_00)), Optional.empty()));
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(1_00)));
        assertThat(assessed.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(assessed.reasons()).isEmpty();
        RiskAssessment sealed = sealed(world, assessed);
        assertThat(sealed.score()).isEqualTo(Score.ZERO);
        assertThat(sealed.hits()).singleElement().satisfies(hit -> assertThat(hit.outcome()).isEqualTo(RuleOutcome.ABSENT));
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("a retry of the command is answered by its seal: the same assessment and decision, replayed - no pin,"
            + " no read, no second count, no second insert or event (INV-RSK-01, INV-RSK-03)")
    void aRetryIsAnsweredByItsSeal() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = EvaluationWorld.transfer(eur(100_00));
        RiskEvaluation.Assessed first = world.evaluation.evaluate(UOW, request);
        int upserts = (int) world.count("upsert");
        world.clear();

        RiskEvaluation.Assessed retry = world.evaluation.evaluate(UOW, request);
        assertThat(retry.replayed()).isTrue();
        assertThat(first.replayed()).isFalse();
        assertThat(retry.assessmentId()).isEqualTo(first.assessmentId());
        assertThat(retry.decision()).isEqualTo(first.decision());
        assertThat(world.journal).as("the second seam of one transaction reads the row the first sealed")
                .containsExactly("find");
        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.windowStore.count(VelocityDimension.PARTY, request.party().toString(), WindowKind.D1)).isEqualTo(1);
        assertThat(upserts).isEqualTo(12);
        assertThat(world.envelopes).hasSize(1);
    }

    @Test
    @DisplayName("a duplicate the caller's lock did not serialise loses at the seal: RaceLost aborts the caller, whose"
            + " count rolls back with it - one assessment")
    void anUnserialisedDuplicateLosesAtTheSeal() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = EvaluationWorld.transfer(eur(100_00));
        world.evaluation.evaluate(UOW, request);
        world.assessmentStore.missNextFind = true;
        assertThatThrownBy(() -> world.evaluation.evaluate(UOW, request)).isInstanceOf(RiskEvaluation.RaceLost.class);
        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.envelopes).hasSize(1);
    }

    // ------------------------------------------------------------------ the fail-safe, per cause

    @Test
    @DisplayName("no ACTIVE policy: ALLOW under NO_POLICY_ACTIVE, cause NO_POLICY, no version named, the windows"
            + " counted - recorded, never silent")
    void noPolicyRecordsAllow() {
        EvaluationWorld world = new EvaluationWorld();
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(5_000_00)));
        RiskAssessment sealed = sealed(world, assessed);
        assertThat(assessed.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(assessed.failSafe()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(sealed.unavailable()).contains(UnavailableCause.NO_POLICY);
        assertThat(sealed.pin()).isEmpty();
        assertThat(sealed.reasons()).containsExactly(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(sealed.velocity()).isEqualTo(VelocityOutcome.COUNTED);
        assertThat(world.reports).singleElement()
                .satisfies(report -> assertThat(report.failSafe()).contains(ReasonCode.NO_POLICY_ACTIVE));
        assertThat(world.journal).containsSubsequence("open -", "pin", "pin", "keep", "open 250ms", "serialise",
                "upsert", "keep", "insert");
    }

    @Test
    @DisplayName("the pin's read failing: its savepoint undone, the compiled ALLOW under RISK_UNAVAILABLE (never"
            + " NO_POLICY_ACTIVE), cause PIN_UNREADABLE, no version named, the movement counted")
    void aPinThatCannotBeReadIsRecorded() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        world.failPin.push(EvaluationWorld.broken());
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW,
                EvaluationWorld.request(RiskOperation.WITHDRAWAL, eur(100_00), IDS.next()).sourceAccount(IDS.next())
                        .build());
        RiskAssessment sealed = sealed(world, assessed);
        assertThat(assessed.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(sealed.unavailable()).contains(UnavailableCause.PIN_UNREADABLE);
        assertThat(sealed.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
        assertThat(sealed.pin()).isEmpty();
        assertThat(world.journal).startsWith("find", "open -", "pin", "undo", "open 250ms");
    }

    @Test
    @DisplayName("a read timing out (SQLSTATE 57014): the savepoint undone, the band's decision under RISK_UNAVAILABLE,"
            + " cause TIMEOUT, the version named; below EUR 1,000 an ALLOW counted behind a savepoint of its own; at or"
            + " above a DENY counting nothing")
    void aTimeoutIsTheBandsDecision() {
        EvaluationWorld world = new EvaluationWorld();
        RiskPolicyVersionId pinned = world.activate(RiskOperation.TRANSFER);
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskEvaluation.Assessed allowed = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(999_99)));
        RiskAssessment sealed = sealed(world, allowed);
        assertThat(allowed.decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(sealed.unavailable()).contains(UnavailableCause.TIMEOUT);
        assertThat(sealed.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
        assertThat(sealed.pin()).map(RiskAssessment.Pin::id).contains(pinned);
        assertThat(sealed.velocity()).isEqualTo(VelocityOutcome.COUNTED);
        assertThat(world.journal).containsSubsequence("open -", "pin", "keep", "open 250ms", "serialise", "undo",
                "open 250ms", "serialise", "upsert", "keep", "insert");

        world.clear();
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskEvaluation.Assessed denied = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(1_000_00)));
        assertThat(denied.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(sealed(world, denied).velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
        assertThat(world.count("upsert")).isZero();
        assertThat(world.count("open")).as("a refused fail-safe attempts no count").isEqualTo(2);
    }

    @Test
    @DisplayName("a read failing otherwise - the windows' store, RiskInputs - is READ_FAILED; the engine refusing an"
            + " amount in another currency is ENGINE_REFUSED and answered by the open-ended band (DENY)")
    void everyOtherCauseIsRecorded() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        world.failFind.push(EvaluationWorld.broken());
        assertThat(sealed(world, world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_00)))).unavailable())
                .contains(UnavailableCause.READ_FAILED);
        world.failInputs.push(new IllegalStateException("kyc unavailable"));
        assertThat(sealed(world, world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_00)))).unavailable())
                .contains(UnavailableCause.READ_FAILED);

        RiskEvaluation.Assessed dollars = world.evaluation.evaluate(UOW,
                EvaluationWorld.transfer(Money.ofMinorUnits(5_00, CurrencyCode.of("USD"))));
        RiskAssessment sealed = sealed(world, dollars);
        assertThat(sealed.unavailable()).contains(UnavailableCause.ENGINE_REFUSED);
        assertThat(dollars.decision()).as("a USD 5 cannot be placed below EUR 1,000: the top band").isEqualTo(RiskDecision.DENY);
        assertThat(sealed.reasons()).containsExactly(ReasonCode.RISK_UNAVAILABLE);
    }

    @Test
    @DisplayName("a permitted fail-safe whose own count fails too is recorded UNCOUNTED - the money moves, the row says"
            + " the windows did not count it")
    void aFailSafeWhoseCountFailsIsUncounted() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        world.failSerialise.push(EvaluationWorld.timeout());
        world.failSerialise.push(EvaluationWorld.timeout());
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, EvaluationWorld.transfer(eur(10_00)));
        RiskAssessment sealed = sealed(world, assessed);
        assertThat(assessed.permits()).isTrue();
        assertThat(sealed.velocity()).isEqualTo(VelocityOutcome.UNCOUNTED);
        assertThat(sealed.cutAt()).isEmpty();
        assertThat(world.windowStore.rows()).isZero();
        assertThat(world.count("undo")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the seal

    @Test
    @DisplayName("the seal: the inputs' text parses back to itself and its digest is its own; the verdict's digest is the"
            + " rows'; the same command judged in two worlds seals the same bytes")
    void theSealIsStable() {
        RiskEvaluationRequest request = EvaluationWorld.request(RiskOperation.TRANSFER, eur(1_200_00), IDS.next())
                .sourceAccount(IDS.next()).destinationAccount(IDS.next()).build();
        List<RiskAssessment> twice = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            EvaluationWorld world = new EvaluationWorld();
            world.activate(RiskOperation.TRANSFER);
            twice.add(sealed(world, world.evaluation.evaluate(UOW, request)));
        }
        RiskAssessment sealed = twice.get(0);
        assertThat(CanonicalEvaluation.render(CanonicalEvaluation.parseInputs(sealed.inputsCanonical())))
                .isEqualTo(sealed.inputsCanonical());
        assertThat(sealed.sealHolds()).isTrue();
        assertThat(sealed.verdictSha256()).isEqualTo(CanonicalEvaluation.sha256(CanonicalEvaluation.render(sealed.verdict())));
        assertThat(twice.get(1).inputsSha256()).isEqualTo(sealed.inputsSha256());
        assertThat(twice.get(1).verdictSha256()).isEqualTo(sealed.verdictSha256());
        assertThat(sealed.hits()).hasSize(RiskPolicyFixtures.v1(RiskOperation.TRANSFER).rules().size());
        assertThat(sealed.reasons()).containsExactly(ReasonCode.AMOUNT_BAND, ReasonCode.AMOUNT_BAND);
    }

    @Test
    @DisplayName("RiskDecisionMade carries identifiers and codes only - the assessment, the operation, the command, the"
            + " decision and each reason code - never a score, an amount or a subject (INV-RSK-08)")
    void theEventCarriesCodesOnly() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluationRequest request = EvaluationWorld.transfer(eur(1_200_00));
        RiskEvaluation.Assessed assessed = world.evaluation.evaluate(UOW, request);
        assertThat(world.payloads).singleElement().satisfies(payload -> {
            assertThat(payload).containsOnlyKeys("assessmentId", "operation", "commandRef", "decision", "reasonCount",
                    "reason1", "reason2");
            assertThat(payload).containsEntry("assessmentId", assessed.assessmentId().value().toString())
                    .containsEntry("commandRef", request.commandRef().value()).containsEntry("decision", "CHALLENGE")
                    .containsEntry("reason1", "AMOUNT_BAND").containsEntry("reasonCount", "2");
            assertThat(payload.values()).doesNotContain(request.party().toString(), "120000");
        });
        assertThat(world.envelopes).singleElement().satisfies(envelope -> {
            assertThat(envelope.aggregateType()).isEqualTo("risk_assessment");
            assertThat(envelope.correlationId()).isEqualTo(request.correlation());
        });
    }

    // ------------------------------------------------------------------ T-advise

    @Test
    @DisplayName("T-advise: the same decision as T-eval would reach, the step-up named - and no lock on a subject, no"
            + " count, no row, no event")
    void adviseWritesNothing() {
        EvaluationWorld world = new EvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskEvaluation.Advice advice = world.evaluation.advise(UOW, EvaluationWorld.transfer(eur(600_00)));
        assertThat(advice.decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(advice.stepUpRequired()).isTrue();
        assertThat(advice.permits()).isFalse();
        assertThat(world.journal).doesNotContain("serialise", "upsert", "insert", "find")
                .noneMatch(entry -> entry.startsWith("event"));
        assertThat(world.assessmentStore.all()).isEmpty();
        assertThat(world.windowStore.rows()).isZero();

        world.clear();
        RiskEvaluation.Advice none = new EvaluationWorld().evaluation.advise(UOW, EvaluationWorld.transfer(eur(1_00)));
        assertThat(none.failSafe()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(none.permits()).isTrue();
    }

    // ------------------------------------------------------------------ the request

    @Test
    @DisplayName("the request refuses what T-eval cannot judge: an operation that moves no money, a command ref of free"
            + " text, a corridor that is not one; its toString names no subject")
    void theRequestIsJudgedWhole() {
        assertThatThrownBy(() -> EvaluationWorld.request(RiskOperation.MONITORING, eur(1_00), IDS.next()).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommandRef.of("transfer 1; DROP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvaluationWorld.request(RiskOperation.CROSS_BORDER, eur(1_00), IDS.next())
                .corridor("EURUSD").build()).isInstanceOf(IllegalArgumentException.class);
        UUID party = IDS.next();
        RiskEvaluationRequest request = RiskEvaluationRequest.builder(RiskOperation.TRANSFER, CommandRef.of("t-1"),
                eur(1_00), party, CorrelationId.of("flow"), CausationId.of("cause"))
                .actor(new Actor("c", ActorType.CUSTOMER)).build();
        assertThat(request.toString()).doesNotContain(party.toString());
        assertThat(RiskEvaluation.causeOf(new RiskStorageException("x: SQLSTATE 57014"))).isEqualTo(UnavailableCause.TIMEOUT);
        assertThat(RiskEvaluation.causeOf(new RiskStorageException("x: SQLSTATE 40001"))).isEqualTo(UnavailableCause.READ_FAILED);
        assertThat(RiskEvaluation.causeOf(new RuntimeException(new java.sql.SQLException("cancelled", "57014"))))
                .isEqualTo(UnavailableCause.TIMEOUT);
    }

    // ------------------------------------------------------------------

    static RiskAssessment sealed(EvaluationWorld world, RiskEvaluation.Assessed assessed) {
        return world.assessmentStore.all().stream().filter(a -> a.id().equals(assessed.assessmentId())).findFirst()
                .orElseThrow();
    }

    private static RiskSignal.SourceEvent source(String type, String producer) {
        return new RiskSignal.SourceEvent(EventId.next(IDS), type, 1, producer, CorrelationId.of("flow"),
                CausationId.of("cause"));
    }
}
