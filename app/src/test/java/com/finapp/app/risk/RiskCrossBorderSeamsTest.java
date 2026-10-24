package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.crossborder.CrossBorderMovements;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.CrossBorderVerdict;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssurance;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskEvaluation;
import com.finapp.risk.RiskEvaluationRequest;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskRating;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RuleHit;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalAttributes;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.UnavailableCause;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Crossborder's two seams answered by risk (`P13-TSK-011`; {@code PHASE_13_PLAN.md} sections 2.2 C1/C6, 7.3, 12.1, 12.3;
 * ADR-0108 decision 3; {@code INV-RSK-01}…{@code -04}, {@code INV-CON-03}), over a real {@link RiskEvaluation} on
 * in-memory stores ({@link RiskEvaluationWorld}) and the C8 {@code CROSS_BORDER} fixture: every decision mapped to the two
 * verdicts and the authorization's 422 - in its order (risk, then limit) and the reverse, the same code; the two seams'
 * refusals disjoint and together every non-permitting decision; one assessment and one increment per movement across both
 * seams; the corridor rule firing; the EUR judged amount for a EUR source, a EUR destination, and a corridor with no EUR
 * leg (the recorded fail-safe); the fail-safe reaching the seam; {@code RaceLost} propagating; the request the movement's
 * own; the movement's money rows taken before the evaluation; and the adapters writing nothing of their own.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the cross-border seams answered by risk: the mapping, one assessment, the EUR figure, the fail-safe"
        + " (P13-TSK-011)")
class RiskCrossBorderSeamsTest {

    private static final CorridorKey EUR_USD_US = CorridorKey.parse("EUR-USD-US");
    /** One of the C8 fixture's high-risk corridors. */
    private static final CorridorKey EUR_USD_PA = CorridorKey.parse("EUR-USD-PA");
    private static final CorridorKey USD_EUR_DE = CorridorKey.parse("USD-EUR-DE");
    /** A corridor of the O7 corridor policy with no EUR leg. */
    private static final CorridorKey GBP_USD_US = CorridorKey.parse("GBP-USD-US");

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<UUID, CrossBorderMovements.Movement> movements = new HashMap<>();
    private final List<UUID> locked = new ArrayList<>();
    private final CrossBorderRiskJudgement judgement = new CrossBorderRiskJudgement(world.evaluation, fakeMovements());
    private final RiskCrossBorderRiskDecision risk = new RiskCrossBorderRiskDecision(judgement);
    private final RiskCrossBorderLimitCheck limits = new RiskCrossBorderLimitCheck(judgement);
    private final List<String> consulted = new ArrayList<>();
    private Payer lastPayer;
    private CrossBorderInstruction lastInstruction;

    /** One customer: a party, an identity, the wallet product's customer account. */
    private record Payer(UUID party, UUID identity, Optional<UUID> account) {
        static Payer fresh() {
            return new Payer(IDS.next(), IDS.next(), Optional.of(IDS.next()));
        }

        static Payer withoutAWallet() {
            return new Payer(IDS.next(), IDS.next(), Optional.empty());
        }
    }

    // ------------------------------------------------------------------ the mapping

    @Test
    @DisplayName("every decision maps to its verdicts and 422: ALLOW authorises; an unsatisfied CHALLENGE is RiskRefused"
            + " (CHALLENGE_REQUIRED); the EUR 10,000 AMOUNT_BAND DENY and the eleventh's VELOCITY_COUNT REVIEW are"
            + " LimitRefused; a screening DENY, a PROHIBITED profile and a score band's REVIEW are RiskRefused; a tie is"
            + " LimitRefused - the same code in the authorization's order and the reverse")
    void everyDecisionMapsToItsVerdictsAndCode() {
        world.activate(RiskOperation.CROSS_BORDER);

        assertThat(authorised(Payer.fresh(), EUR_USD_US, eur(100_00), RiskDecision.ALLOW)).isEmpty();

        Payer challenged = Payer.fresh();
        assertThat(authorised(challenged, EUR_USD_US, eur(600_00), RiskDecision.CHALLENGE))
                .contains(CrossborderErrorCode.RISK_REFUSED);
        RiskEvaluation.Assessed unsatisfied = lastAssessed();
        assertThat(unsatisfied.refusalReason()).contains(ReasonCode.CHALLENGE_REQUIRED);
        assertThat(unsatisfied.limitDecided()).as("a challenge is never a limit refusal").isFalse();
        assertThat(world.count(VelocityDimension.PARTY, challenged.party(), WindowKind.D1))
                .as("a refused movement counts nothing").isZero();

        assertThat(authorised(Payer.fresh(), EUR_USD_US, eur(10_000_00), RiskDecision.DENY))
                .contains(CrossborderErrorCode.LIMIT_REFUSED);
        assertThat(consulted).as("the risk seam first, permitting what a limit decided; the limit seam refuses it")
                .endsWith("risk", "limits");

        Payer busy = Payer.fresh();
        for (int i = 0; i < 10; i++) {
            assertThat(authorised(busy, EUR_USD_US, eur(1_00), RiskDecision.ALLOW)).isEmpty();
        }
        assertThat(authorised(busy, EUR_USD_US, eur(1_00), RiskDecision.REVIEW))
                .as("the eleventh of the day from one wallet: VELOCITY_COUNT's own REVIEW")
                .contains(CrossborderErrorCode.LIMIT_REFUSED);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, busy.account().orElseThrow(), WindowKind.D1))
                .isEqualTo(10);

        world.screeningHit = Optional.of(true);
        assertThat(authorised(Payer.fresh(), EUR_USD_US, eur(100_00), RiskDecision.DENY))
                .contains(CrossborderErrorCode.RISK_REFUSED);
        assertThat(lastAssessed().reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(authorised(Payer.fresh(), EUR_USD_US, eur(10_000_00), RiskDecision.DENY))
                .as("a tie - the limit's own DENY beside the screening's: a limit refusal, as on transfers")
                .contains(CrossborderErrorCode.LIMIT_REFUSED);
        world.screeningHit = Optional.empty();

        Payer prohibited = Payer.fresh();
        world.profileStore.seedDisposition(prohibited.party(), RiskRating.PROHIBITED);
        assertThat(authorised(prohibited, EUR_USD_US, eur(100_00), RiskDecision.DENY))
                .contains(CrossborderErrorCode.RISK_REFUSED);

        int cases = world.cases.size();
        Payer toANewPayee = Payer.fresh();
        CrossBorderInstruction instruction = instruction(toANewPayee, EUR_USD_PA, eur(100_00));
        registeredDaysAgo(movements.get(instruction.quoteId()).beneficiary(), 3);
        assertThat(authorise(toANewPayee, instruction)).as("the corridor (400) and a new payee (250): the REVIEW band")
                .contains(CrossborderErrorCode.RISK_REFUSED);
        assertThat(lastAssessed().decision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(world.cases).as("REVIEW reaches the case opener (NONE in production until -016)")
                .hasSize(cases + 1);
    }

    @Test
    @DisplayName("the two seams' refusals are disjoint and together refuse exactly every non-permitting decision - so"
            + " neither order of consultation changes the code, and nothing permits what risk refused")
    void theTwoSeamsAreDisjointAndCovering() {
        world.activate(RiskOperation.CROSS_BORDER);
        Map<CrossBorderInstruction, Payer> judged = new java.util.LinkedHashMap<>();
        world.screeningHit = Optional.of(true);
        sealOne(judged, Payer.fresh(), eur(10_000_00));
        sealOne(judged, Payer.fresh(), eur(100_00));
        world.screeningHit = Optional.empty();
        world.sessions = (unitOfWork, actor) -> Optional.of(RiskAssurance.MULTI_FACTOR);
        sealOne(judged, Payer.fresh(), eur(600_00));
        world.sessions = (unitOfWork, actor) -> Optional.empty();
        sealOne(judged, Payer.fresh(), eur(600_00));
        sealOne(judged, Payer.fresh(), eur(10_000_00));
        sealOne(judged, Payer.fresh(), eur(100_00));
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        sealOne(judged, Payer.fresh(), eur(1_00));

        assertThat(world.assessmentStore.all()).extracting(RiskAssessment::decision)
                .as("the sealed decisions span ALLOW, a satisfied and an unsatisfied CHALLENGE, and DENY")
                .contains(RiskDecision.ALLOW, RiskDecision.CHALLENGE, RiskDecision.DENY);
        judged.forEach((instruction, payer) -> {
            RiskEvaluation.Assessed assessed = inFlow(payer, () -> judgement.judge(UNTOUCHABLE, instruction));
            boolean riskRefuses = RiskCrossBorderRiskDecision.verdict(assessed) == CrossBorderVerdict.REFUSE;
            boolean limitRefuses = RiskCrossBorderLimitCheck.verdict(assessed) == CrossBorderVerdict.REFUSE;
            assertThat(riskRefuses && limitRefuses).as("disjoint: %s", assessed).isFalse();
            assertThat(riskRefuses || limitRefuses).as("covering - refused exactly when not permitted: %s", assessed)
                    .isEqualTo(!assessed.permits());
            assertThat(limitRefuses).as("the limit seam's refusals are exactly the limit-decided: %s", assessed)
                    .isEqualTo(assessed.limitDecided());
        });
    }

    private void sealOne(Map<CrossBorderInstruction, Payer> judged, Payer payer, Money amount) {
        CrossBorderInstruction instruction = instruction(payer, EUR_USD_US, amount);
        inFlow(payer, () -> risk.check(UNTOUCHABLE, instruction));
        judged.put(instruction, payer);
    }

    // ------------------------------------------------------------------ one assessment

    @Test
    @DisplayName("both seams of one movement: ONE assessment, ONE RiskDecisionMade, ONE increment per window - the risk"
            + " seam evaluates, the limit seam's call is answered from the seal; the money rows taken before each"
            + " evaluation (INV-RSK-01, INV-CON-03)")
    void bothSeamsOneAssessmentOneIncrement() {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.fresh();
        CrossBorderInstruction instruction = instruction(payer, EUR_USD_US, eur(100_00));

        assertThat(inFlow(payer, () -> risk.check(UNTOUCHABLE, instruction))).isEqualTo(CrossBorderVerdict.PERMIT);
        assertThat(world.assessmentStore.log).as("the quote and the wallet locked, then T-eval's lookup and seal")
                .containsExactly("movement", "find", "insert");
        assertThat(inFlow(payer, () -> limits.check(UNTOUCHABLE, instruction))).isEqualTo(CrossBorderVerdict.PERMIT);
        assertThat(world.assessmentStore.log).as("the second seam re-takes rows it holds and reads the seal")
                .containsExactly("movement", "find", "insert", "movement", "find");
        assertThat(inFlow(payer, () -> judgement.judge(UNTOUCHABLE, instruction)).replayed()).isTrue();

        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.decisionsAnnounced()).isEqualTo(1);
        assertThat(world.windowStore.upserted).as("party, source account, beneficiary - four window kinds each, once")
                .hasSize(12);
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, payer.account().orElseThrow(), WindowKind.D1))
                .isEqualTo(1);
        assertThat(world.count(VelocityDimension.DESTINATION,
                movements.get(instruction.quoteId()).beneficiary(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.reports).as("the meters see one evaluation").hasSize(1);
    }

    @Test
    @DisplayName("ten movements of one party on ten quotes count ten in the PARTY window, each its own assessment")
    void tenMovementsOfOnePartyCountTen() {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.fresh();
        for (int i = 0; i < 10; i++) {
            assertThat(authorised(payer, EUR_USD_US, eur(1_00), RiskDecision.ALLOW)).isEmpty();
        }
        assertThat(world.assessmentStore.all()).hasSize(10);
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isEqualTo(10);
    }

    // ------------------------------------------------------------------ the corridor and the amount

    @Test
    @DisplayName("a corridor rule fires: EUR-USD-PA, one of the version's high-risk corridors, is CORRIDOR_HIGH_RISK's 400"
            + " - the CHALLENGE band - refused for a password session, authorised and counted for a MULTI_FACTOR one;"
            + " EUR-USD-US at the same amount is ALLOW")
    void aCorridorRuleFires() {
        world.activate(RiskOperation.CROSS_BORDER);
        assertThat(authorised(Payer.fresh(), EUR_USD_PA, eur(100_00), RiskDecision.CHALLENGE))
                .contains(CrossborderErrorCode.RISK_REFUSED);
        RiskAssessment sealed = sealedLast();
        assertThat(sealed.hits()).filteredOn(RuleHit::fired).extracting(RuleHit::kind)
                .containsExactly(RuleKind.CORRIDOR_HIGH_RISK);
        assertThat(sealed.inputsCanonical()).contains("EUR-USD-PA");

        world.sessions = (unitOfWork, actor) -> Optional.of(RiskAssurance.MULTI_FACTOR);
        Payer stepped = Payer.fresh();
        assertThat(authorised(stepped, EUR_USD_PA, eur(100_00), RiskDecision.CHALLENGE)).isEmpty();
        assertThat(world.count(VelocityDimension.PARTY, stepped.party(), WindowKind.D1)).isEqualTo(1);
        world.sessions = (unitOfWork, actor) -> Optional.empty();

        assertThat(authorised(Payer.fresh(), EUR_USD_US, eur(100_00), RiskDecision.ALLOW)).isEmpty();
    }

    @Test
    @DisplayName("the judged amount is the movement's EUR figure: a EUR source judges the quote's source amount (not the"
            + " total debit with the fee), a corridor converting to EUR judges the destination amount, and a corridor"
            + " with no EUR leg is decided by the recorded fail-safe - DENY, RISK_UNAVAILABLE, ENGINE_REFUSED")
    void theJudgedAmountIsTheMovementsEurFigure() {
        world.activate(RiskOperation.CROSS_BORDER);

        Payer fromEur = Payer.fresh();
        CrossBorderInstruction eurSource = instruction(fromEur, EUR_USD_US, eur(600_00));
        assertThat(eurSource.sourceAmount()).as("the instruction carries the total debit").isEqualTo(eur(602_50));
        assertThat(authorise(fromEur, eurSource)).contains(CrossborderErrorCode.RISK_REFUSED);
        assertThat(sealedLast().amount()).isEqualTo(eur(600_00));
        assertThat(sealedLast().decision()).as("judged, not failed safe").isEqualTo(RiskDecision.CHALLENGE);

        Payer fromUsd = Payer.fresh();
        CrossBorderInstruction toEur = instruction(fromUsd, USD_EUR_DE, money(700_00, "USD"), eur(600_00));
        assertThat(authorise(fromUsd, toEur)).contains(CrossborderErrorCode.RISK_REFUSED);
        assertThat(sealedLast().amount()).as("the figure the corridor converts to").isEqualTo(eur(600_00));
        assertThat(sealedLast().decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(sealedLast().unavailable()).isEmpty();

        Payer fromGbp = Payer.fresh();
        CrossBorderInstruction noEurLeg = instruction(fromGbp, GBP_USD_US, money(100_00, "GBP"), money(125_00, "USD"));
        assertThat(authorise(fromGbp, noEurLeg)).contains(CrossborderErrorCode.RISK_REFUSED);
        RiskAssessment failedSafe = sealedLast();
        assertThat(failedSafe.amount()).isEqualTo(money(100_00, "GBP"));
        assertThat(failedSafe.decision()).isEqualTo(RiskDecision.DENY);
        assertThat(failedSafe.failSafeReason()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(failedSafe.unavailable()).contains(UnavailableCause.ENGINE_REFUSED);
    }

    @Test
    @DisplayName("the EUR figure, per leg: source EUR - the source; destination EUR - the destination; neither - the"
            + " source as it is")
    void theJudgedAmountPerLeg() {
        assertThat(CrossBorderRiskJudgement.judgedAmount(eur(600_00), money(650_00, "USD"))).isEqualTo(eur(600_00));
        assertThat(CrossBorderRiskJudgement.judgedAmount(money(700_00, "USD"), eur(600_00))).isEqualTo(eur(600_00));
        assertThat(CrossBorderRiskJudgement.judgedAmount(money(100_00, "GBP"), money(125_00, "USD")))
                .isEqualTo(money(100_00, "GBP"));
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("the fail-safe reaches the seam: no policy is ALLOW(NO_POLICY_ACTIVE), counted, authorised; a timed-out"
            + " evaluation is C1's DENY at every band, recorded, RiskRefused, nothing counted")
    void theFailSafeReachesTheSeam() {
        Payer unpolicied = Payer.fresh();
        assertThat(authorised(unpolicied, EUR_USD_US, eur(50_000_00), RiskDecision.ALLOW)).isEmpty();
        assertThat(sealedLast().failSafeReason()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(sealedLast().velocity()).isEqualTo(VelocityOutcome.COUNTED);

        world.activate(RiskOperation.CROSS_BORDER);
        Payer timedOut = Payer.fresh();
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(authorised(timedOut, EUR_USD_US, eur(1_00), RiskDecision.DENY))
                .as("C1: cross-border DENY at every band, the smallest amount included")
                .contains(CrossborderErrorCode.RISK_REFUSED);
        assertThat(sealedLast().failSafeReason()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(sealedLast().unavailable()).contains(UnavailableCause.TIMEOUT);
        assertThat(world.count(VelocityDimension.PARTY, timedOut.party(), WindowKind.D1)).isZero();
    }

    // ------------------------------------------------------------------ what propagates, what is built, what is written

    @Test
    @DisplayName("RaceLost propagates from either seam - never swallowed into a verdict - so the transaction rolls back"
            + " with its count and the retry finds the seal")
    void raceLostPropagates() {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.fresh();
        CrossBorderInstruction instruction = instruction(payer, EUR_USD_US, eur(100_00));
        inFlow(payer, () -> risk.check(UNTOUCHABLE, instruction));
        world.assessmentStore.missNextFind = true;
        assertThatThrownBy(() -> inFlow(payer, () -> limits.check(UNTOUCHABLE, instruction)))
                .isInstanceOf(RiskEvaluation.RaceLost.class);
        assertThat(world.assessmentStore.all()).hasSize(1);
    }

    @Test
    @DisplayName("the request is the movement's own: the quote id as the command ref, the payer party, the acting"
            + " identity, the wallet's customer account, the beneficiary as the destination, the corridor, the flow's"
            + " correlation - and no device")
    void theRequestIsTheMovementsOwn() throws Exception {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.fresh();
        CrossBorderInstruction instruction = instruction(payer, EUR_USD_US, eur(42_00));
        CorrelationId flow = CorrelationId.generate(IDS);
        try (SecurityContext.Scope actor = SecurityContext.enter(actor(payer));
                CorrelationContext.Scope scope = CorrelationContext.enter(Correlation.startingWith(flow))) {
            risk.check(UNTOUCHABLE, instruction);
        }
        RiskAssessment sealed = sealedLast();
        assertThat(sealed.commandRef().value()).isEqualTo(instruction.quoteId().toString());
        assertThat(sealed.operation()).isEqualTo(RiskOperation.CROSS_BORDER);
        assertThat(sealed.amount()).isEqualTo(eur(42_00));
        assertThat(sealed.subjects().party()).isEqualTo(payer.party());
        assertThat(sealed.subjects().identity()).contains(payer.identity());
        assertThat(sealed.subjects().sourceAccount()).isEqualTo(payer.account());
        assertThat(sealed.subjects().destination()).contains(new RiskEvaluationRequest.Destination(
                RiskEvaluationRequest.DestinationKind.BENEFICIARY, movements.get(instruction.quoteId()).beneficiary()));
        assertThat(sealed.subjects().device()).isEmpty();
        assertThat(sealed.subjects().merchant()).isEmpty();
        assertThat(sealed.inputsCanonical()).contains("EUR-USD-US");
        assertThat(world.windowStore.upserted).noneMatch(key -> key.dimension() == VelocityDimension.DEVICE);
        assertThat(sealed.correlationId()).isEqualTo(flow.value());
        assertThat(world.envelopes).singleElement()
                .satisfies(event -> assertThat(event.correlationId()).isEqualTo(flow));
    }

    @Test
    @DisplayName("a party with no wallet in the corridor's source currency is judged with no source account - the"
            + " acceptance refuses it after the seams, rolling the judgement back")
    void noWalletIsNoSourceAccount() {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.withoutAWallet();
        assertThat(authorised(payer, EUR_USD_US, eur(100_00), RiskDecision.ALLOW)).isEmpty();
        assertThat(sealedLast().subjects().sourceAccount()).isEmpty();
        assertThat(world.windowStore.upserted).as("party and beneficiary - four window kinds each").hasSize(8);
    }

    @Test
    @DisplayName("the adapters write nothing of their own (INV-RSK-02): every seam call ran on a connection any use of"
            + " which fails - the only writes are risk's, through T-eval - and a refusal counts nothing")
    void theAdaptersWriteNothingOfTheirOwn() {
        world.activate(RiskOperation.CROSS_BORDER);
        Payer payer = Payer.fresh();
        CrossBorderInstruction refused = instruction(payer, EUR_USD_US, eur(10_000_00));
        assertThat(inFlow(payer, () -> risk.check(UNTOUCHABLE, refused))).isEqualTo(CrossBorderVerdict.PERMIT);
        assertThat(inFlow(payer, () -> limits.check(UNTOUCHABLE, refused))).isEqualTo(CrossBorderVerdict.REFUSE);
        assertThat(world.assessmentStore.all()).hasSize(1);
        assertThat(world.windowStore.upserted).as("a refusal counts nothing").isEmpty();
        assertThat(locked).as("the movement's rows taken by each seam's call").containsExactly(refused.quoteId(),
                refused.quoteId());
    }

    // ------------------------------------------------------------------ the authorization's order, mirrored

    /**
     * One movement through both seams in {@code PaymentAuthorization}'s order (risk, then limit), under the payer's actor
     * and a fresh flow: empty when it would proceed to the acceptance, else the 422 the authorization answers. The
     * reverse order is asserted to answer the same, and the sealed decision is asserted.
     */
    private Optional<CrossborderErrorCode> authorised(Payer payer, CorridorKey corridor, Money amount,
            RiskDecision expected) {
        Optional<CrossborderErrorCode> outcome = authorise(payer, instruction(payer, corridor, amount));
        assertThat(lastAssessed().decision()).as("the sealed decision").isEqualTo(expected);
        return outcome;
    }

    private Optional<CrossborderErrorCode> authorise(Payer payer, CrossBorderInstruction instruction) {
        Optional<CrossborderErrorCode> outcome = inFlow(payer, () -> authorizationOrder(instruction));
        assertThat(inFlow(payer, () -> reverseOrder(instruction))).as("the same code in either order").isEqualTo(outcome);
        assertThat(lastAssessed().commandRef().value()).isEqualTo(instruction.quoteId().toString());
        return outcome;
    }

    private Optional<CrossborderErrorCode> authorizationOrder(CrossBorderInstruction instruction) {
        consulted.add("risk");
        if (risk.check(UNTOUCHABLE, instruction) == CrossBorderVerdict.REFUSE) {
            return Optional.of(CrossborderErrorCode.RISK_REFUSED);
        }
        consulted.add("limits");
        if (limits.check(UNTOUCHABLE, instruction) == CrossBorderVerdict.REFUSE) {
            return Optional.of(CrossborderErrorCode.LIMIT_REFUSED);
        }
        return Optional.empty();
    }

    private Optional<CrossborderErrorCode> reverseOrder(CrossBorderInstruction instruction) {
        if (limits.check(UNTOUCHABLE, instruction) == CrossBorderVerdict.REFUSE) {
            return Optional.of(CrossborderErrorCode.LIMIT_REFUSED);
        }
        if (risk.check(UNTOUCHABLE, instruction) == CrossBorderVerdict.REFUSE) {
            return Optional.of(CrossborderErrorCode.RISK_REFUSED);
        }
        return Optional.empty();
    }

    /** An EUR-source instruction: the quote's source {@code amount}, crossborder's EUR 2.50 fee in the total debit. */
    private CrossBorderInstruction instruction(Payer payer, CorridorKey corridor, Money amount) {
        Money destination = Money.ofMinorUnits(amount.minorUnits(), corridor.destination());
        return instruction(payer, corridor, amount, destination);
    }

    private CrossBorderInstruction instruction(Payer payer, CorridorKey corridor, Money source, Money destination) {
        UUID quote = IDS.next();
        Money fee = Money.ofMinorUnits(2_50, corridor.source());
        CrossBorderInstruction instruction = new CrossBorderInstruction(payer.party(), quote, corridor, source.plus(fee));
        movements.put(quote, new CrossBorderMovements.Movement(payer.party(), quote, corridor, IDS.next(), source,
                destination, payer.account()));
        lastPayer = payer;
        lastInstruction = instruction;
        return instruction;
    }

    /** The movements the seams read: recorded in the assessment store's log, so the order against T-eval is visible. */
    private CrossBorderMovements fakeMovements() {
        return new CrossBorderMovements() {
            @Override
            public Movement locked(java.sql.Connection unitOfWork, CrossBorderInstruction instruction) {
                world.assessmentStore.log.add("movement");
                locked.add(instruction.quoteId());
                return movements.get(instruction.quoteId());
            }

            @Override
            public Optional<Movement> owned(java.sql.Connection unitOfWork, UUID party, UUID quote) {
                throw new AssertionError("the seams never read the door's way");
            }
        };
    }

    private void registeredDaysAgo(UUID beneficiary, int days) {
        world.signalStore.add(new RiskSignal(RiskSignalId.next(IDS), SignalKind.BENEFICIARY_REGISTERED,
                SignalSubject.of(SubjectKind.BENEFICIARY, beneficiary),
                new RiskSignal.SourceEvent(EventId.next(IDS), "crossborder.BeneficiaryRegistered", 1, "crossborder",
                        CorrelationId.of("flow"), CausationId.of("cause")),
                RiskEvaluationWorld.CLOCK.instant().minus(Duration.ofDays(days)),
                SignalAttributes.of(SignalKind.BENEFICIARY_REGISTERED, Map.of("country", "PA", "currency", "USD"))));
    }

    /** The last judged movement's answer, as the seams read it - replayed from its seal by the real judgement. */
    private RiskEvaluation.Assessed lastAssessed() {
        RiskEvaluation.Assessed replayed = inFlow(lastPayer, () -> judgement.judge(UNTOUCHABLE, lastInstruction));
        assertThat(replayed.replayed()).isTrue();
        return replayed;
    }

    private RiskAssessment sealedLast() {
        List<RiskAssessment> all = world.assessmentStore.all();
        return all.get(all.size() - 1);
    }

    private static <T> T inFlow(Payer payer, Supplier<T> work) {
        try (SecurityContext.Scope actor = SecurityContext.enter(actor(payer));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static Actor actor(Payer payer) {
        return new Actor(payer.identity().toString(), ActorType.CUSTOMER);
    }

    private static Money money(long minor, String currency) {
        return Money.ofMinorUnits(minor, CurrencyCode.of(currency));
    }
}
