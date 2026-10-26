package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.RiskBatteryOracles.Expected;
import com.finapp.risk.RiskBatteryOracles.Line;
import com.finapp.risk.RiskBatteryOracles.Statement;
import com.finapp.risk.RiskBatteryOracles.Step;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy battery's oracles against hand-computed cases (`P13-TST-002`; {@code PHASE_13_PLAN.md} sections 12.1-12.3,
 * C1, C8): every rule kind on its edge, firing, quiet and absent; the composition - the clamp, the precedence, a band
 * never softening a hard decision, the step that decided; the challenge's answer; the fail-safe by cause and band; the
 * refusals. Every expected value below is worked out from the plan's statement of the rule, by hand - never by running the
 * engine - and the oracle must give it. Then the independence itself, mechanically: the oracles' and the generator's class
 * files name none of the engine's types or methods, so the battery cannot pass by asking the code it judges.
 */
@DisplayName("P13-TST-002 - the battery's oracles: hand-computed cases, and independent of the engine by construction")
class RiskBatteryOraclesTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    // ------------------------------------------------------------------ each kind

    @Test
    @DisplayName("each rule kind on its edge: at or above a threshold fires, one short does not; lookbacks fewer than"
            + " the named units; never seen new, never happened not recent; an absent reading never fires")
    void eachKindOnItsEdge() {
        Line amount = line(RuleKind.AMOUNT_BAND, "threshold_minor", 500_00L);
        assertThat(outcome(amount, in().amount(eur(499_99)))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(amount, in().amount(eur(500_00)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(RiskBatteryOracles.outcome(amount, EvaluationInputs.builder(RiskOperation.MONITORING).build()))
                .isEqualTo(RuleOutcome.ABSENT);

        Line count = line(RuleKind.VELOCITY_COUNT, "dimension", VelocityDimension.SOURCE_ACCOUNT, "window", WindowKind.D1,
                "threshold_count", 10L);
        assertThat(outcome(count, in().velocity(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1, 9, 0)))
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(count, in().velocity(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1, 10, 0)))
                .isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(count, in().velocity(VelocityDimension.PARTY, WindowKind.D1, 50, 0)))
                .as("another dimension's window is no reading of this one").isEqualTo(RuleOutcome.ABSENT);

        Line sum = line(RuleKind.VELOCITY_SUM, "dimension", VelocityDimension.PARTY, "window", WindowKind.D1,
                "threshold_minor", 5_000_00L);
        assertThat(outcome(sum, in().velocity(VelocityDimension.PARTY, WindowKind.D1, 3, 4_999_99)))
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(sum, in().velocity(VelocityDimension.PARTY, WindowKind.D1, 3, 5_000_00)))
                .isEqualTo(RuleOutcome.FIRED);

        Line payee = line(RuleKind.NEW_PAYEE, "lookback_days", 30);
        assertThat(outcome(payee, in().payeeFirstSeen(FirstSeen.never()))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(payee, in().payeeFirstSeen(FirstSeen.daysAgo(29)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(payee, in().payeeFirstSeen(FirstSeen.daysAgo(30)))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(payee, in())).isEqualTo(RuleOutcome.ABSENT);
        Line device = line(RuleKind.NEW_DEVICE, "lookback_days", 30);
        assertThat(outcome(device, in().deviceFirstSeen(FirstSeen.daysAgo(0)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(device, in().deviceFirstSeen(FirstSeen.daysAgo(30)))).isEqualTo(RuleOutcome.NOT_FIRED);

        Line below = line(RuleKind.SESSION_ASSURANCE_BELOW, "assurance", RiskAssurance.MULTI_FACTOR);
        assertThat(outcome(below, in().sessionAssurance(RiskAssurance.SINGLE_FACTOR))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(below, in().sessionAssurance(RiskAssurance.MULTI_FACTOR))).isEqualTo(RuleOutcome.NOT_FIRED);

        Line failures = line(RuleKind.RECENT_AUTH_FAILURES, "window", WindowKind.H1, "threshold_count", 3L);
        assertThat(outcome(failures, in().authFailures(WindowKind.H1, 2))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(failures, in().authFailures(WindowKind.H1, 3))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(failures, in().authFailures(WindowKind.D1, 9))).isEqualTo(RuleOutcome.ABSENT);

        Line credential = line(RuleKind.RECENT_CREDENTIAL_CHANGE, "lookback_hours", 24);
        assertThat(outcome(credential, in().credentialChange(LastSeen.hoursAgo(23)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(credential, in().credentialChange(LastSeen.hoursAgo(24)))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(credential, in().credentialChange(LastSeen.never()))).isEqualTo(RuleOutcome.NOT_FIRED);
        Line recovery = line(RuleKind.RECENT_RECOVERY, "lookback_hours", 72);
        assertThat(outcome(recovery, in().recovery(LastSeen.hoursAgo(71)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(recovery, in().recovery(LastSeen.hoursAgo(72)))).isEqualTo(RuleOutcome.NOT_FIRED);

        Line age = line(RuleKind.ACCOUNT_AGE_BELOW, "days", 7);
        assertThat(outcome(age, in().accountAgeDays(6))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(age, in().accountAgeDays(7))).isEqualTo(RuleOutcome.NOT_FIRED);

        assertThat(outcome(line(RuleKind.KYC_NOT_APPROVED), in().kycApproved(false))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(line(RuleKind.KYC_NOT_APPROVED), in().kycApproved(true))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(line(RuleKind.SCREENING_HIT), in().screeningHit(true))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(line(RuleKind.SCREENING_HIT), in().screeningHit(false))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(line(RuleKind.MERCHANT_SUSPENDED), in().merchantSuspended(true))).isEqualTo(RuleOutcome.FIRED);

        Line prohibited = line(RuleKind.PROFILE_RATING, "rating", RiskRating.PROHIBITED);
        Line high = line(RuleKind.PROFILE_RATING, "rating", RiskRating.HIGH);
        assertThat(outcome(prohibited, in().profileRating(RiskRating.HIGH))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(prohibited, in().profileRating(RiskRating.PROHIBITED))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(high, in().profileRating(RiskRating.MEDIUM))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(high, in().profileRating(RiskRating.PROHIBITED))).as("at or above").isEqualTo(RuleOutcome.FIRED);

        Line corridor = line(RuleKind.CORRIDOR_HIGH_RISK, "corridors", RiskBatteryOracles.HIGH_RISK);
        assertThat(outcome(corridor, in().corridor("EUR-USD-PA"))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(corridor, in().corridor("EUR-USD-US"))).isEqualTo(RuleOutcome.NOT_FIRED);
    }

    @Test
    @DisplayName("the sweep's kinds on their edges: dormancy AND the day's sum, the rapid in-out share by"
            + " cross-multiplication, structuring's count AND strictly-above sum, a positive round multiple, the ratios'"
            + " count floor AND share, the BNPL count")
    void theSweepsKindsOnTheirEdges() {
        Line dormant = line(RuleKind.DORMANT_THEN_ACTIVE, "dormant_days", 90, "window", WindowKind.D1, "threshold_minor",
                1_000_00L);
        assertThat(outcome(dormant, monitoring(90, 1_000_00))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(dormant, monitoring(89, 1_000_00))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(dormant, monitoring(90, 999_99))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(RiskBatteryOracles.outcome(dormant, EvaluationInputs.builder(RiskOperation.MONITORING)
                .velocity(VelocityDimension.PARTY, WindowKind.D1, 1, 5_000_00).build()))
                .as("no dormancy sealed").isEqualTo(RuleOutcome.ABSENT);

        Line rapid = line(RuleKind.RAPID_IN_OUT, "window", WindowKind.D1, "ratio_bps", 8_000);
        assertThat(outcome(rapid, flow(1_000_00, 800_00))).as("exactly 80 %").isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(rapid, flow(1_000_00, 799_99))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(rapid, flow(0, 5_00))).as("no inflow, no share").isEqualTo(RuleOutcome.NOT_FIRED);

        Line structuring = line(RuleKind.STRUCTURING, "window", WindowKind.D1, "below_minor", 1_000_00L,
                "threshold_count", 3L, "sum_minor", 2_500_00L);
        assertThat(outcome(structuring, amounts(900_00, 900_00, 700_00))).as("2,500.00 is not above 2,500.00")
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(structuring, amounts(900_00, 900_00, 700_01))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(structuring, amounts(999_99, 999_99, 1_000_00, 5_000_00))).as("1,000.00 is not below")
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(structuring, amounts(800_00, 800_00, 800_00, 800_00))).isEqualTo(RuleOutcome.FIRED);

        Line round = line(RuleKind.ROUND_AMOUNT, "modulus_minor", 1_000_00L);
        assertThat(outcome(round, in().amount(eur(2_000_00)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(round, in().amount(eur(1_999_99)))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(round, in().amount(eur(0)))).as("zero is no round amount").isEqualTo(RuleOutcome.NOT_FIRED);

        Line refunds = line(RuleKind.REFUND_RATIO, "window", WindowKind.D30, "ratio_bps", 2_000, "threshold_count", 20L);
        assertThat(outcome(refunds, shares(new Ratio(20, 100), null))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(refunds, shares(new Ratio(19, 95), null))).as("20 % over 19 - below the count floor")
                .isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(refunds, shares(new Ratio(20, 101), null))).as("19.8 %").isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(refunds, shares(new Ratio(20, 0), null))).as("no denominator, no share")
                .isEqualTo(RuleOutcome.NOT_FIRED);
        Line chargebacks = line(RuleKind.CHARGEBACK_RATIO, "window", WindowKind.D30, "ratio_bps", 100, "threshold_count",
                10L);
        assertThat(outcome(chargebacks, shares(null, new Ratio(10, 1_000)))).isEqualTo(RuleOutcome.FIRED);
        assertThat(outcome(chargebacks, shares(null, new Ratio(10, 1_001)))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(chargebacks, shares(new Ratio(50, 50), null))).as("the refunds are no chargebacks")
                .isEqualTo(RuleOutcome.ABSENT);

        Line bnpl = line(RuleKind.BNPL_MULTI_APPLICATION, "window", WindowKind.D7, "threshold_count", 3L);
        assertThat(outcome(bnpl, bnpl(2))).isEqualTo(RuleOutcome.NOT_FIRED);
        assertThat(outcome(bnpl, bnpl(3))).isEqualTo(RuleOutcome.FIRED);
    }

    // ------------------------------------------------------------------ the composition

    @Test
    @DisplayName("the composition under TRANSFER v1, worked by hand: a band raises a hard CHALLENGE to REVIEW and DENY, a"
            + " screening hit's DENY stands at score 0, the clamp at 1000, a satisfied and an unsatisfied challenge")
    void theCompositionByHand() {
        Statement transfer = RiskBatteryOracles.v1(RiskOperation.TRANSFER);
        // EUR 2,000 to a never-paid payee from a never-seen device, multi-factor: rules 1 (CHALLENGE), 5 (250), 6 (200),
        // 7 (200) fire - 650, the REVIEW band (600-799): REVIEW is stricter than the hard CHALLENGE - the band decided.
        Expected review = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 2_000_00)
                .payeeFirstSeen(FirstSeen.never()).deviceFirstSeen(FirstSeen.never()).build());
        assertThat(review.score()).isEqualTo(650);
        assertThat(review.decision()).hasValue(RiskDecision.REVIEW);
        assertThat(review.step()).isEqualTo(Step.SCORE_BAND);
        assertThat(review.bandPosition()).isEqualTo(3);
        assertThat(fired(review)).containsExactly(1, 5, 6, 7);
        // The same with a HIGH profile (300): 950, the DENY band.
        Expected deny = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 2_000_00)
                .payeeFirstSeen(FirstSeen.never()).deviceFirstSeen(FirstSeen.never()).profileRating(RiskRating.HIGH)
                .build());
        assertThat(deny.score()).isEqualTo(950);
        assertThat(deny.decision()).hasValue(RiskDecision.DENY);
        assertThat(deny.bandPosition()).isEqualTo(4);
        // A screening hit on EUR 10: nothing weighs, score 0, the ALLOW band - the hard DENY stands (rule 9 decided).
        Expected screened = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 10_00).screeningHit(true)
                .build());
        assertThat(screened.score()).isZero();
        assertThat(screened.decision()).hasValue(RiskDecision.DENY);
        assertThat(screened.step()).isEqualTo(Step.HARD_RULE);
        assertThat(screened.deciding()).containsExactly(9);
        assertThat(screened.permits()).isFalse();
        // EUR 600, multi-factor: the hard CHALLENGE over the ALLOW band - satisfied, permitted.
        Expected satisfied = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 600_00).build());
        assertThat(satisfied.decision()).hasValue(RiskDecision.CHALLENGE);
        assertThat(satisfied.deciding()).containsExactly(1);
        assertThat(satisfied.challengeSatisfied()).hasValue(true);
        assertThat(satisfied.permits()).isTrue();
        // The same single-factor: rule 8 fires too (100, still ALLOW) - unsatisfied, refused.
        Expected unsatisfied = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 600_00)
                .sessionAssurance(RiskAssurance.SINGLE_FACTOR).build());
        assertThat(unsatisfied.score()).isEqualTo(100);
        assertThat(unsatisfied.challengeSatisfied()).hasValue(false);
        assertThat(unsatisfied.permits()).isFalse();
        // Everything weighted at once: 400 + 250 + 200 + 200 + 100 + 300 + 200 = 1,650, clamped to 1,000.
        Expected clamped = RiskBatteryOracles.judge(transfer, quiet(RiskOperation.TRANSFER, 2_000_00)
                .velocity(VelocityDimension.PARTY, WindowKind.D1, 3, 6_000_00).payeeFirstSeen(FirstSeen.never())
                .deviceFirstSeen(FirstSeen.never()).sessionAssurance(RiskAssurance.SINGLE_FACTOR)
                .profileRating(RiskRating.HIGH).recovery(LastSeen.hoursAgo(1)).build());
        assertThat(clamped.score()).isEqualTo(1000);
        assertThat(clamped.decision()).hasValue(RiskDecision.DENY);
    }

    @Test
    @DisplayName("the precedence DENY > HOLD > REVIEW > CHALLENGE > ALLOW: a withdrawal of EUR 2,500 ten hours after a"
            + " credential change is HOLD by both hard rules; the sweep's verdict is a rating; another currency and an"
            + " unadmitted decision are refusals")
    void thePrecedenceAndTheRefusals() {
        assertThat(RiskBatteryOracles.PRECEDENCE).containsExactly(RiskDecision.DENY, RiskDecision.HOLD,
                RiskDecision.REVIEW, RiskDecision.CHALLENGE, RiskDecision.ALLOW);
        Expected hold = RiskBatteryOracles.judge(RiskBatteryOracles.v1(RiskOperation.WITHDRAWAL),
                quiet(RiskOperation.WITHDRAWAL, 2_500_00).credentialChange(LastSeen.hoursAgo(10)).build());
        assertThat(hold.decision()).hasValue(RiskDecision.HOLD);
        assertThat(hold.step()).isEqualTo(Step.HARD_RULE);
        assertThat(hold.deciding()).containsExactly(1, 2);
        Expected kyc = RiskBatteryOracles.judge(RiskBatteryOracles.v1(RiskOperation.WITHDRAWAL),
                quiet(RiskOperation.WITHDRAWAL, 2_500_00).credentialChange(LastSeen.hoursAgo(10)).kycApproved(false)
                        .build());
        assertThat(kyc.decision()).as("DENY beats HOLD").hasValue(RiskDecision.DENY);
        assertThat(kyc.deciding()).containsExactly(8);

        Statement monitoring = RiskBatteryOracles.v1(RiskOperation.MONITORING);
        assertThat(RiskBatteryOracles.judge(monitoring, EvaluationInputs.builder(RiskOperation.MONITORING)
                .amount(eur(1_000_00)).build()).rating()).as("a round EUR 1,000 alone: 100, LOW").hasValue(RiskRating.LOW);
        assertThat(RiskBatteryOracles.judge(monitoring, EvaluationInputs.builder(RiskOperation.MONITORING)
                .amount(eur(1_000_00)).screeningHit(true).build()).rating()).as("600 + 100: HIGH")
                .hasValue(RiskRating.HIGH);
        Expected medium = RiskBatteryOracles.judge(monitoring, EvaluationInputs.builder(RiskOperation.MONITORING)
                .corridor("EUR-AED-AE").build());
        assertThat(medium.score()).isEqualTo(300);
        assertThat(medium.rating()).as("exactly 300: MEDIUM").hasValue(RiskRating.MEDIUM);
        assertThat(medium.decision()).isEmpty();

        assertThat(RiskBatteryOracles.judge(RiskBatteryOracles.v1(RiskOperation.TRANSFER), quiet(RiskOperation.TRANSFER,
                0).amount(Money.ofMinorUnits(5_00, CurrencyCode.of("USD"))).build()).refusal()).hasValue("CURRENCY");
        Statement holdingWallet = RiskBatteryOracles.v1(RiskOperation.WALLET_PAYMENT).withLine(new Line(3,
                RuleKind.MERCHANT_SUSPENDED, new TreeMap<>(), OptionalInt.empty(), Optional.of(RiskDecision.HOLD)));
        assertThat(RiskBatteryOracles.judge(holdingWallet, quiet(RiskOperation.WALLET_PAYMENT, 10_00)
                .merchantSuspended(true).build()).refusal()).as("a wallet admits no HOLD").hasValue("ADMISSION");
    }

    @Test
    @DisplayName("the fail-safe by cause and band (C1): the compiled ALLOW with no policy or no pin, else the pinned"
            + " version's band for the amount - the open last band for another currency")
    void theFailSafeByCauseAndBand() {
        Optional<Statement> transfer = Optional.of(RiskBatteryOracles.v1(RiskOperation.TRANSFER));
        assertFailSafe(RiskBatteryOracles.failSafe(transfer, RiskOperation.TRANSFER, UnavailableCause.READ_FAILED,
                eur(999_99)), RiskDecision.ALLOW, 1, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(transfer, RiskOperation.TRANSFER, UnavailableCause.TIMEOUT,
                eur(1_000_00)), RiskDecision.DENY, 2, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(transfer, RiskOperation.TRANSFER, UnavailableCause.ENGINE_REFUSED,
                Money.ofMinorUnits(1_00, CurrencyCode.of("USD"))), RiskDecision.DENY, 2, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(Optional.empty(), RiskOperation.WITHDRAWAL, UnavailableCause.NO_POLICY,
                eur(5_000_00)), RiskDecision.ALLOW, 0, "NO_POLICY_ACTIVE");
        assertFailSafe(RiskBatteryOracles.failSafe(Optional.empty(), RiskOperation.CROSS_BORDER,
                UnavailableCause.PIN_UNREADABLE, eur(5_000_00)), RiskDecision.ALLOW, 0, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(Optional.of(RiskBatteryOracles.v1(RiskOperation.WITHDRAWAL)),
                RiskOperation.WITHDRAWAL, UnavailableCause.READ_FAILED, eur(1)), RiskDecision.DENY, 1, "RISK_UNAVAILABLE");
        Optional<Statement> card = Optional.of(RiskBatteryOracles.v1(RiskOperation.CARD_PAYMENT));
        assertFailSafe(RiskBatteryOracles.failSafe(card, RiskOperation.CARD_PAYMENT, UnavailableCause.TIMEOUT,
                eur(249_99)), RiskDecision.ALLOW, 1, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(card, RiskOperation.CARD_PAYMENT, UnavailableCause.TIMEOUT,
                eur(250_00)), RiskDecision.DENY, 2, "RISK_UNAVAILABLE");
        assertFailSafe(RiskBatteryOracles.failSafe(Optional.of(RiskBatteryOracles.v1(RiskOperation.CROSS_BORDER)),
                RiskOperation.CROSS_BORDER, UnavailableCause.ENGINE_REFUSED, eur(0)), RiskDecision.DENY, 1,
                "RISK_UNAVAILABLE");
    }

    @Test
    @DisplayName("the successor versions are valid and differ from v1 exactly where the battery says")
    void theSuccessorsDifferWhereStated() {
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            Statement v1 = RiskBatteryOracles.v1(operation);
            Statement v2 = RiskBatteryOracles.v2(operation);
            assertThat(RiskBattery.policy(v2)).as(v2.name() + " is a valid version").isNotNull();
            int changedLines = 0;
            for (int i = 0; i < v1.lines().size(); i++) {
                changedLines += v1.lines().get(i).equals(v2.lines().get(i)) ? 0 : 1;
            }
            assertThat(changedLines).as(v2.name() + " changes one or two rules").isBetween(1, 2);
        }
    }

    // ------------------------------------------------------------------ independence, mechanically

    /** The engine's code - types the oracles and the generator must never name. */
    static final List<String> ENGINE_TYPES = List.of("RiskEngineV1", "RuleEvaluation", "Score", "ScoreBand",
            "RatingBand", "FailSafeBand", "RiskPolicy", "RiskRule", "RuleParameters", "ParameterValue", "RiskEvaluation",
            "VelocityWindows", "WindowStarts", "AssessmentReplay", "Explanation", "CanonicalEvaluation", "Verdict",
            "RuleHit", "RiskBattery", "testing/RiskPolicyFixtures");

    /** The engine's comparisons on the vocabulary the oracles may hold - methods they must never call. */
    static final List<String> ENGINE_METHODS = List.of("strictest", "isAdmittedBy", "admittedBy", "atLeast", "satisfies",
            "within", "outAtLeast", "countBelow", "sumBelow", "failSafeFor", "covers", "failSafeUnplaced",
            "failSafeUnpinned");

    @Test
    @DisplayName("the oracles' and the generator's class files name none of the engine's types - the engine, its rule"
            + " evaluations, its score and bands, the policy model, T-eval, the replay, the explanation, the canonical form,"
            + " the fixtures - nor its comparisons on the vocabulary")
    void theOraclesNameNoEngineCode() throws IOException {
        List<String> named = new ArrayList<>();
        List<Class<?>> classes = new ArrayList<>(List.of(RiskBatteryOracles.class.getNestMembers()));
        classes.addAll(List.of(RiskBatteryWorld.class.getNestMembers()));
        assertThat(classes).as("the classes read").hasSizeGreaterThan(10);
        for (Class<?> type : classes) {
            String text = classFile(type);
            assertThat(text).as(type.getName() + " was read").contains("com/finapp/risk/");
            for (String engineType : ENGINE_TYPES) {
                Matcher matcher = Pattern.compile("com/finapp/risk/" + Pattern.quote(engineType) + "(?![A-Za-z0-9_$])")
                        .matcher(text);
                if (matcher.find()) {
                    named.add(type.getSimpleName() + " names " + engineType);
                }
            }
            for (String method : ENGINE_METHODS) {
                if (Pattern.compile("(?<![A-Za-z0-9_$])" + method + "(?![A-Za-z0-9_$])").matcher(text).find()) {
                    named.add(type.getSimpleName() + " names " + method);
                }
            }
        }
        assertThat(named).as("engine code the oracles or the generator name").isEmpty();
        // The check can see: the battery itself, which runs the engine, names it.
        assertThat(classFile(RiskBattery.class)).contains("com/finapp/risk/RiskEngineV1");
    }

    // ------------------------------------------------------------------ plumbing

    private static String classFile(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static void assertFailSafe(Expected expected, RiskDecision decision, int position, String reason) {
        assertThat(expected.decision()).hasValue(decision);
        assertThat(expected.bandPosition()).isEqualTo(position);
        assertThat(expected.failSafeReason()).hasValue(reason);
        assertThat(expected.step()).isEqualTo(Step.FAIL_SAFE);
        assertThat(expected.rules()).isEmpty();
        assertThat(expected.permits()).isEqualTo(decision == RiskDecision.ALLOW);
    }

    private static List<Integer> fired(Expected expected) {
        return expected.rules().stream().filter(rule -> rule.outcome() == RuleOutcome.FIRED)
                .map(RiskBatteryOracles.Judged::ordinal).toList();
    }

    /** A judged movement with every reading quiet: zero windows, seen payee and device, multi-factor, LOW, clean. */
    private static EvaluationInputs.Builder quiet(RiskOperation operation, long minor) {
        EvaluationInputs.Builder in = EvaluationInputs.builder(operation)
                .subject(SignalSubject.of(SubjectKind.PARTY, new UUID(1, 1))).amount(eur(minor))
                .payeeFirstSeen(FirstSeen.daysAgo(400)).deviceFirstSeen(FirstSeen.daysAgo(400))
                .sessionAssurance(RiskAssurance.MULTI_FACTOR).credentialChange(LastSeen.never())
                .recovery(LastSeen.never()).accountAgeDays(400).kycApproved(true).screeningHit(false)
                .profileRating(RiskRating.LOW).merchantSuspended(false);
        for (VelocityDimension dimension : VelocityDimension.values()) {
            for (WindowKind window : WindowKind.values()) {
                in.velocity(dimension, window, 0, 0);
            }
        }
        for (WindowKind window : WindowKind.values()) {
            in.authFailures(window, 0);
        }
        return in;
    }

    private static EvaluationInputs.Builder in() {
        return EvaluationInputs.builder(RiskOperation.TRANSFER).amount(eur(1));
    }

    private static RuleOutcome outcome(Line line, EvaluationInputs.Builder in) {
        return RiskBatteryOracles.outcome(line, in.build());
    }

    private static EvaluationInputs.Builder monitoring(int dormantDays, long partySum) {
        return EvaluationInputs.builder(RiskOperation.MONITORING)
                .velocity(VelocityDimension.PARTY, WindowKind.D1, 1, partySum)
                .monitoring(new MonitoringReadings(Reading.of(dormantDays), Map.of(), Map.of(), Map.of(), Map.of(),
                        Map.of()));
    }

    private static EvaluationInputs.Builder flow(long in, long out) {
        return EvaluationInputs.builder(RiskOperation.MONITORING).monitoring(new MonitoringReadings(Reading.absent(),
                Map.of(WindowKind.D1, new Flow(in, out)), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    private static EvaluationInputs.Builder amounts(long... minor) {
        return EvaluationInputs.builder(RiskOperation.MONITORING).monitoring(new MonitoringReadings(Reading.absent(),
                Map.of(), Map.of(WindowKind.D1, Amounts.of(minor)), Map.of(), Map.of(), Map.of()));
    }

    private static EvaluationInputs.Builder shares(Ratio refunds, Ratio chargebacks) {
        Map<WindowKind, Ratio> refundShares = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Ratio> chargebackShares = new EnumMap<>(WindowKind.class);
        if (refunds != null) {
            refundShares.put(WindowKind.D30, refunds);
        }
        if (chargebacks != null) {
            chargebackShares.put(WindowKind.D30, chargebacks);
        }
        return EvaluationInputs.builder(RiskOperation.MONITORING).monitoring(new MonitoringReadings(Reading.absent(),
                Map.of(), Map.of(), refundShares, chargebackShares, Map.of()));
    }

    private static EvaluationInputs.Builder bnpl(long count) {
        return EvaluationInputs.builder(RiskOperation.MONITORING).monitoring(new MonitoringReadings(Reading.absent(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(WindowKind.D7, count)));
    }

    private static Line line(RuleKind kind, Object... parameters) {
        SortedMap<String, Object> map = new TreeMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            Object value = parameters[i + 1];
            map.put((String) parameters[i], value instanceof java.util.SortedSet<?> set ? new TreeSet<>(set) : value);
        }
        return new Line(1, kind, map, OptionalInt.of(100), Optional.empty());
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
