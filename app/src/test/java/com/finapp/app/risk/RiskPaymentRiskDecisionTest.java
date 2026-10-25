package com.finapp.app.risk;

import static com.finapp.app.risk.RiskEvaluationWorld.IDS;
import static com.finapp.app.risk.RiskEvaluationWorld.UNTOUCHABLE;
import static com.finapp.app.risk.RiskEvaluationWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentRiskDecision;
import com.finapp.payments.PaymentVerdict;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.Reading;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskInputs;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.UnavailableCause;
import com.finapp.risk.VelocityDimension;
import com.finapp.risk.VelocityOutcome;
import com.finapp.risk.WindowKind;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
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
 * Payments' checkout payment seam answered by risk, over a real {@code RiskEvaluation} with in-memory stores and the C8
 * {@code CARD_PAYMENT} and {@code WALLET_PAYMENT} fixtures (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1,
 * 12.3; ADR-0108; {@code INV-RSK-01}…{@code -04}, {@code INV-CON-03}): every decision mapped to its verdict - ALLOW and a
 * satisfied CHALLENGE permit and count; a DENY, a suspended merchant and an unsatisfied CHALLENGE refuse and count
 * nothing; a card HOLD holds for the version's validity and counts nothing - one assessment per intent (a re-evaluation
 * replays the sealed decision, a refusal included), the fail-safe (no policy permits, recorded; a timeout is C1's band:
 * below EUR 250 ALLOW, at or above DENY), the request carrying the payment's own facts (the merchant for both, the
 * wallet's customer account for the wallet, no device), the top-up and the pay-by-bank unjudged, and nothing of
 * payments' written on the caller's connection. The real confirmation, its locks and the concurrency are the owed
 * {@code PaymentRiskSeamDatabaseTest}'s (C5).
 */
@DisplayName("the checkout payment seam answered by risk: the mapping, one assessment per intent, the fail-safe"
        + " (P13-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RiskPaymentRiskDecisionTest {

    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final Map<LedgerAccountId, RiskPaymentRiskDecision.Accounts.Account> ledger = new HashMap<>();
    private final List<LedgerAccountId> accountsAsked = new ArrayList<>();
    private final RiskPaymentRiskDecision seam = new RiskPaymentRiskDecision(world.evaluation,
            (unitOfWork, account) -> {
                assertThat(unitOfWork).isSameAs(UNTOUCHABLE);
                accountsAsked.add(account);
                return ledger.get(account);
            });

    /** A payer: the party, the identity, the wallet's customer account and its ledger account. */
    private record Payer(UUID party, UUID identity, UUID customerAccount, LedgerAccountId wallet) {}

    /** A merchant: its id and its payable. */
    private record Merchant(UUID id, LedgerAccountId payable) {}

    private Payer payer() {
        Payer payer = new Payer(IDS.next(), IDS.next(), IDS.next(), LedgerAccountId.of(IDS.next()));
        ledger.put(payer.wallet(), new RiskPaymentRiskDecision.Accounts.Account(AccountPurpose.CUSTOMER_WALLET,
                Optional.of(payer.customerAccount())));
        return payer;
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant(IDS.next(), LedgerAccountId.of(IDS.next()));
        ledger.put(merchant.payable(), new RiskPaymentRiskDecision.Accounts.Account(AccountPurpose.MERCHANT_PAYABLE,
                Optional.of(merchant.id())));
        return merchant;
    }

    private PaymentRiskDecision.Judged byCard(Payer payer, Merchant merchant, Money amount) {
        return new PaymentRiskDecision.Judged(PaymentIntentId.next(IDS), payer.party(), InstrumentKind.CARD_TOKEN,
                Optional.empty(), merchant.payable(), amount);
    }

    private PaymentRiskDecision.Judged fromWallet(Payer payer, Merchant merchant, Money amount) {
        return new PaymentRiskDecision.Judged(PaymentIntentId.next(IDS), payer.party(), InstrumentKind.WALLET,
                Optional.of(payer.wallet()), merchant.payable(), amount);
    }

    private <T> T as(Payer payer, Supplier<T> call) {
        try (SecurityContext.Scope actor = SecurityContext.enter(
                        new Actor(payer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return call.get();
        }
    }

    private PaymentVerdict judged(Payer payer, PaymentRiskDecision.Judged payment) {
        return as(payer, () -> seam.check(UNTOUCHABLE, payment));
    }

    private RiskAssessment last() {
        return world.assessmentStore.all().get(world.assessmentStore.all().size() - 1);
    }

    // ------------------------------------------------------------------ the mapping

    @Test
    @DisplayName("every decision maps to its verdict on a card: ALLOW permits and counts the party and the merchant;"
            + " the EUR 500 CHALLENGE unsatisfied, the EUR 10,000 DENY and a screening DENY refuse and count nothing")
    void everyCardDecisionMapsToItsVerdict() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant shop = merchant();

        Payer ordinary = payer();
        assertThat(judged(ordinary, byCard(ordinary, shop, eur(40_00)))).isEqualTo(PaymentVerdict.permit());
        assertThat(last().operation()).isEqualTo(RiskOperation.CARD_PAYMENT);
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(world.count(VelocityDimension.PARTY, ordinary.party(), WindowKind.D1)).isEqualTo(1);
        assertThat(world.count(VelocityDimension.MERCHANT, shop.id(), WindowKind.D1)).isEqualTo(1);

        Payer challenged = payer();
        assertThat(judged(challenged, byCard(challenged, shop, eur(600_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(last().challengeSatisfied()).contains(false);
        assertThat(world.count(VelocityDimension.PARTY, challenged.party(), WindowKind.D1)).isZero();

        Payer large = payer();
        assertThat(judged(large, byCard(large, shop, eur(10_000_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.DENY);
        assertThat(last().velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);

        world.screeningHit = Optional.of(true);
        Payer screened = payer();
        assertThat(judged(screened, byCard(screened, shop, eur(10_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(world.count(VelocityDimension.PARTY, screened.party(), WindowKind.D1)).isZero();
        assertThat(world.count(VelocityDimension.MERCHANT, shop.id(), WindowKind.D1))
                .as("only the permitted payment counted for the merchant").isEqualTo(1);
    }

    @Test
    @DisplayName("a suspended merchant (the MERCHANT_SUSPENDED reading, from the payable's own merchant) is a DENY -"
            + " REFUSE, for the card and the wallet alike")
    void aSuspendedMerchantIsRefused() {
        world.activate(RiskOperation.CARD_PAYMENT);
        world.activate(RiskOperation.WALLET_PAYMENT);
        Merchant suspended = merchant();
        RiskInputs base = world.inputs;
        world.inputs = (unitOfWork, request, judged, now) -> {
            RiskInputs.Readings readings = base.read(unitOfWork, request, judged, now);
            return new RiskInputs.Readings(readings.sessionAssurance(), readings.screeningHit(),
                    readings.kycApproved(),
                    request.merchant().filter(suspended.id()::equals).map(m -> Reading.of(true))
                            .orElseGet(Reading::absent),
                    readings.authFailures());
        };
        Payer payer = payer();
        assertThat(judged(payer, byCard(payer, suspended, eur(10_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().reasons()).containsExactly(ReasonCode.MERCHANT_SUSPENDED);
        assertThat(judged(payer, fromWallet(payer, suspended, eur(10_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().operation()).isEqualTo(RiskOperation.WALLET_PAYMENT);
    }

    @Test
    @DisplayName("a CHALLENGE permits, counted, under a MULTI_FACTOR session - the wallet's judged with its customer"
            + " account as the source")
    void aChallengeIsSatisfiedOnlyByAMultiFactorSession() throws Exception {
        world.activate(RiskOperation.WALLET_PAYMENT);
        Merchant shop = merchant();
        Payer stepped = payer();
        var session = RiskEvaluationWorld.session(stepped.identity(), AssuranceLevel.MULTI_FACTOR, null);
        world.sessions = new IdentitySessionAssurance(RiskEvaluationWorld.liveSessions(new ArrayList<>(), session));
        try (AutoCloseable presented = RiskEvaluationWorld.presenting(session)) {
            assertThat(judged(stepped, fromWallet(stepped, shop, eur(600_00)))).isEqualTo(PaymentVerdict.permit());
        }
        assertThat(last().decision()).isEqualTo(RiskDecision.CHALLENGE);
        assertThat(last().challengeSatisfied()).contains(true);
        assertThat(world.count(VelocityDimension.SOURCE_ACCOUNT, stepped.customerAccount(), WindowKind.D1))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("slice 2: a card HOLD (an EUR 2,500 amount rule) holds for the version's own validity, counts nothing"
            + " and reaches the case opener; a replay holds alike")
    void aCardHoldHoldsForTheVersionsValidity() {
        world.activate(holdingCardPolicy(Duration.ofHours(48)));
        Merchant shop = merchant();
        Payer payer = payer();
        PaymentRiskDecision.Judged payment = byCard(payer, shop, eur(3_000_00));

        PaymentVerdict held = judged(payer, payment);
        assertThat(held.kind()).isEqualTo(PaymentVerdict.Kind.HOLD);
        assertThat(held.holdValidity()).as("the pinned version's, never a payments constant")
                .contains(Duration.ofHours(48));
        assertThat(last().decision()).isEqualTo(RiskDecision.HOLD);
        assertThat(last().velocity()).isEqualTo(VelocityOutcome.NOT_PERMITTED);
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).isZero();
        assertThat(world.cases).hasSize(1);
        assertThat(judged(payer, payment)).as("the replay").isEqualTo(held);
        assertThat(world.assessmentStore.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ one assessment per intent

    @Test
    @DisplayName("one assessment per intent: the command ref is the intent's id, and a re-evaluation is answered from"
            + " the seal - a refused intent re-judged replays its DENY, one row, one event, nothing counted")
    void oneAssessmentPerIntent() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant shop = merchant();
        Payer payer = payer();
        PaymentRiskDecision.Judged refused = byCard(payer, shop, eur(10_000_00));
        for (int i = 0; i < 3; i++) {
            assertThat(judged(payer, refused)).isEqualTo(PaymentVerdict.refuse());
        }
        assertThat(world.assessmentStore.all()).singleElement().satisfies(sealed -> {
            assertThat(sealed.operation()).isEqualTo(RiskOperation.CARD_PAYMENT);
            assertThat(sealed.commandRef().value()).isEqualTo(refused.intentId().value().toString());
            assertThat(sealed.decision()).isEqualTo(RiskDecision.DENY);
        });
        assertThat(world.decisionsAnnounced()).isEqualTo(1);

        PaymentRiskDecision.Judged permitted = byCard(payer, shop, eur(20_00));
        judged(payer, permitted);
        judged(payer, permitted);
        assertThat(world.count(VelocityDimension.PARTY, payer.party(), WindowKind.D1)).as("counted once").isEqualTo(1);
    }

    // ------------------------------------------------------------------ the fail-safe

    @Test
    @DisplayName("no policy is the production default: ALLOW(NO_POLICY_ACTIVE) recorded and counted - PERMIT")
    void noPolicyPermitsRecorded() {
        Merchant shop = merchant();
        Payer payer = payer();
        assertThat(judged(payer, byCard(payer, shop, eur(5_000_00)))).isEqualTo(PaymentVerdict.permit());
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(last().failSafeReason()).contains(ReasonCode.NO_POLICY_ACTIVE);
        assertThat(last().unavailable()).contains(UnavailableCause.NO_POLICY);
    }

    @Test
    @DisplayName("risk unavailable - the 250 ms budget breached - is C1's checkout band: below EUR 250 ALLOW recorded"
            + " and permitted, at or above DENY recorded and refused")
    void aTimeoutIsTheCheckoutBand() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Merchant shop = merchant();
        Payer small = payer();
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(judged(small, byCard(small, shop, eur(249_99)))).isEqualTo(PaymentVerdict.permit());
        assertThat(last().decision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(last().failSafeReason()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(last().unavailable()).contains(UnavailableCause.TIMEOUT);

        Payer large = payer();
        world.failSerialise.push(RiskEvaluationWorld.timeout());
        assertThat(judged(large, byCard(large, shop, eur(250_00)))).isEqualTo(PaymentVerdict.refuse());
        assertThat(last().decision()).isEqualTo(RiskDecision.DENY);
        assertThat(last().failSafeReason()).contains(ReasonCode.RISK_UNAVAILABLE);
        assertThat(world.count(VelocityDimension.PARTY, large.party(), WindowKind.D1)).isZero();
    }

    // ------------------------------------------------------------------ the request and the unjudged

    @Test
    @DisplayName("the request is the payment's own: the intent's id, the party, the acting identity, the merchant from"
            + " the payable for both, the wallet's customer account for the wallet - and no device")
    void theRequestIsThePaymentsOwn() {
        world.activate(RiskOperation.CARD_PAYMENT);
        world.activate(RiskOperation.WALLET_PAYMENT);
        Merchant shop = merchant();
        Payer payer = payer();

        judged(payer, byCard(payer, shop, eur(10_00)));
        RiskAssessment card = last();
        assertThat(card.subjects().party()).isEqualTo(payer.party());
        assertThat(card.subjects().identity()).contains(payer.identity());
        assertThat(card.subjects().merchant()).contains(shop.id());
        assertThat(card.subjects().sourceAccount()).as("a card is no account of the platform's").isEmpty();
        assertThat(card.subjects().device()).as("no per-device identifier exists (-008's finding)").isEmpty();
        assertThat(accountsAsked).containsExactly(shop.payable());

        accountsAsked.clear();
        judged(payer, fromWallet(payer, shop, eur(10_00)));
        RiskAssessment wallet = last();
        assertThat(wallet.operation()).isEqualTo(RiskOperation.WALLET_PAYMENT);
        assertThat(wallet.subjects().sourceAccount()).contains(payer.customerAccount());
        assertThat(wallet.subjects().merchant()).contains(shop.id());
        assertThat(wallet.subjects().device()).isEmpty();
        assertThat(accountsAsked).containsExactly(shop.payable(), payer.wallet());
    }

    @Test
    @DisplayName("a top-up (the pay-in: the payer's own wallet credited) and a pay-by-bank checkout are not judged in"
            + " Phase 13 - PERMIT, no assessment, nothing counted")
    void theUnjudgedPermitWithNoAssessment() {
        world.activate(RiskOperation.CARD_PAYMENT);
        Payer payer = payer();
        PaymentRiskDecision.Judged topUp = new PaymentRiskDecision.Judged(PaymentIntentId.next(IDS), payer.party(),
                InstrumentKind.CARD_TOKEN, Optional.empty(), payer.wallet(), eur(10_000_00));
        assertThat(judged(payer, topUp)).isEqualTo(PaymentVerdict.permit());
        Merchant shop = merchant();
        PaymentRiskDecision.Judged byBank = new PaymentRiskDecision.Judged(PaymentIntentId.next(IDS), payer.party(),
                InstrumentKind.BANK_ACCOUNT, Optional.empty(), shop.payable(), eur(10_000_00));
        assertThat(judged(payer, byBank)).isEqualTo(PaymentVerdict.permit());
        assertThat(world.assessmentStore.all()).isEmpty();
        assertThat(world.windowStore.upserted).isEmpty();
    }

    @Test
    @DisplayName("the verdict carries nothing of the judgement: PERMIT, HOLD (with its validity) or REFUSE")
    void theVerdictCarriesNoReason() {
        world.activate(holdingCardPolicy(Duration.ofHours(72)));
        Payer payer = payer();
        PaymentVerdict held = judged(payer, byCard(payer, merchant(), eur(3_000_00)));
        assertThat(held.toString()).isEqualTo("PaymentVerdict[HOLD, PT72H]");
    }

    /** The C8 card fixture plus a hard HOLD at EUR 2,500 - slice 2's test version, naming its hold validity. */
    private static RiskPolicy holdingCardPolicy(Duration validity) {
        RiskPolicy base = RiskPolicyFixtures.v1(RiskOperation.CARD_PAYMENT);
        List<RiskRule> rules = new ArrayList<>(base.rules());
        rules.add(RiskRule.deciding(rules.size() + 1, RuleKind.AMOUNT_BAND, RuleParameters.of(RuleKind.AMOUNT_BAND,
                Map.of("threshold_minor", RiskPolicyFixtures.minor(2_500_00))), RiskDecision.HOLD));
        return new RiskPolicy(RiskOperation.CARD_PAYMENT, base.engine(), base.currency(), rules, base.scoreBands(),
                List.of(), base.failSafeBands(), base.challengeAssurance(), base.overrideThreshold(),
                Optional.of(validity));
    }
}
