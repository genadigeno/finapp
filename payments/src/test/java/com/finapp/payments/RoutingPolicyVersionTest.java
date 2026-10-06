package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The routing policy version and its pure decision function (`P7-TSK-003`, ADR-0060):
 * construction refuses the shapes no operator may write, matching is exact, eligibility is
 * judged from declared capabilities in the ADR's own order, and — the invariant's heart —
 * <strong>recomputing a version over the same inputs reproduces the plan exactly</strong>
 * (`INV-RAIL-02`), across versions that decide differently.
 */
@DisplayName("the routing policy version and its decision (P7-TSK-003)")
class RoutingPolicyVersionTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final RailId CARD = SimulatedCardPspAdapter.RAIL.id();
    private static final RailId PUSH_RAIL = RailId.of("push-test");
    private static final RailId BOOK_RAIL = RailId.of("book-test");

    /** A coherent synthetic push declaration — restricted to EUR, ceilinged at 50.00. */
    private static final PaymentRail PUSH_DECLARED =
            new PaymentRail(
                    PUSH_RAIL,
                    3,
                    new RailCapabilities(
                            InteractionModel.PUSH,
                            RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                            Set.of(),
                            RailCapabilities.RefundMode.RETURN_PAYMENT,
                            RailCapabilities.SettlementModel.SCHEME_REPORTED,
                            Optional.of(Duration.ofMinutes(1)),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.of(Set.of(EUR)),
                            Map.of(EUR, Money.ofMinorUnits(50_00, EUR)),
                            Optional.of(AccountPurpose.SETTLEMENT_CLEARING)));

    private static final PaymentRail BOOK_DECLARED =
            new PaymentRail(
                    BOOK_RAIL,
                    1,
                    new RailCapabilities(
                            InteractionModel.BOOK,
                            RailCapabilities.Finality.FINAL_ON_POSTING,
                            Set.of(),
                            RailCapabilities.RefundMode.BOOK_REFUND,
                            RailCapabilities.SettlementModel.NONE,
                            Optional.empty(),
                            RailCapabilities.DisputeModel.NONE,
                            Optional.empty(),
                            Map.of(),
                            Optional.empty()));

    private static final PaymentRails RAILS =
            PaymentRails.of(
                    List.of(SimulatedCardPspAdapter.RAIL, PUSH_DECLARED, BOOK_DECLARED));

    // ----------------------------------------------------------------- construction

    @Test
    @DisplayName("construction refuses what no operator may write: empty, unreasoned,"
            + " backdated, incoherent ceilings, duplicate candidates")
    void constructionRefusesTheIllegalShapes() {
        assertThatThrownBy(() -> version(1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one rule");
        assertThatThrownBy(() -> RoutingPolicyVersion.create(
                        IDS, 1, List.of(cardRule()), Optional.empty(), "op-1", " ", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> RoutingPolicyVersion.create(
                        IDS, 0, List.of(cardRule()), Optional.empty(), "op-1", "why", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numbered from 1");
        assertThatThrownBy(() -> RoutingPolicyVersion.create(
                        IDS,
                        1,
                        List.of(cardRule()),
                        Optional.of(Instant.now(CLOCK).minusSeconds(3600)),
                        "op-1",
                        "why",
                        CLOCK))
                .isInstanceOf(BackdatedRoutingPolicyVersionException.class);

        assertThatThrownBy(() -> rule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                        Optional.empty(), Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one candidate");
        assertThatThrownBy(() -> rule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                        Optional.empty(), Optional.empty(), List.of(CARD, CARD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
        assertThatThrownBy(() -> rule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                        Optional.empty(), Optional.of(Money.ofMinorUnits(10_00, EUR)),
                        List.of(CARD)))
                .as("a ceiling without a currency is not a number")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("any-currency");
        assertThatThrownBy(() -> rule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                        Optional.of(USD), Optional.of(Money.ofMinorUnits(10_00, EUR)),
                        List.of(CARD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priced in the currency");
        assertThatThrownBy(() -> rule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                        Optional.of(EUR), Optional.of(Money.ofMinorUnits(0, EUR)),
                        List.of(CARD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-positive ceiling");

        // Rehydration refuses non-contiguous indexes: position and index bind each other.
        assertThatThrownBy(() -> RoutingPolicyVersion.rehydrate(
                        RoutingPolicyVersionId.next(IDS),
                        1,
                        List.of(new RoutingRule(
                                RoutingRuleId.next(IDS), 1, PaymentDirection.PAY_IN,
                                InstrumentKind.CARD_TOKEN, Optional.empty(), Optional.empty(),
                                List.of(CARD))),
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "op-1",
                        "why"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    @DisplayName("the instrument-model table is pinned: cards ride two-step, bank accounts"
            + " push, wallets book (ADR-0060 section 3)")
    void theCarriageTableIsPinned() {
        assertThat(InstrumentKind.CARD_TOKEN.carriedBy(InteractionModel.TWO_STEP)).isTrue();
        assertThat(InstrumentKind.CARD_TOKEN.carriedBy(InteractionModel.PUSH)).isFalse();
        assertThat(InstrumentKind.CARD_TOKEN.carriedBy(InteractionModel.BOOK)).isFalse();
        assertThat(InstrumentKind.BANK_ACCOUNT.carriedBy(InteractionModel.PUSH)).isTrue();
        assertThat(InstrumentKind.BANK_ACCOUNT.carriedBy(InteractionModel.TWO_STEP)).isFalse();
        assertThat(InstrumentKind.WALLET.carriedBy(InteractionModel.BOOK)).isTrue();
        assertThat(InstrumentKind.WALLET.carriedBy(InteractionModel.PUSH)).isFalse();
    }

    // ----------------------------------------------------------------- matching

    @Test
    @DisplayName("a rule matches on direction, kind, currency and ceiling - first rule wins,"
            + " and a mis-scaled policy fails loudly, never silently unbounded")
    void matchingIsExactAndOrdered() {
        RoutingPolicyVersion two = version(
                1,
                List.of(
                        new RoutingPolicyVersion.NewRule(
                                PaymentDirection.PAY_IN,
                                InstrumentKind.CARD_TOKEN,
                                Optional.of(EUR),
                                Optional.of(Money.ofMinorUnits(10_00, EUR)),
                                List.of(PUSH_RAIL)),
                        cardRule()));

        // Under the ceiling the first rule matches; its candidate is a push rail, which the
        // card token cannot ride - recorded, and the rule does NOT fall through to rule 2:
        // matching picks the rule, eligibility judges its candidates (ADR-0060 section 3).
        RoutingPlan small = two.decide(inputs(Money.ofMinorUnits(9_99, EUR)), RAILS, Map.of());
        assertThat(small.matchedRuleIndex()).contains(0);
        assertThat(small.chosen()).isEmpty();
        assertThat(small.steps()).hasSize(1);
        assertThat(small.steps().get(0).rejection())
                .contains(RoutingRejection.MODEL_CANNOT_CARRY_INSTRUMENT);

        // Above the ceiling rule 0 does not match and rule 1 routes to the card.
        RoutingPlan large = two.decide(inputs(Money.ofMinorUnits(10_01, EUR)), RAILS, Map.of());
        assertThat(large.matchedRuleIndex()).contains(1);
        assertThat(large.chosen()).contains(CARD);

        // A USD payment misses the EUR rule and lands on the any-currency rule.
        RoutingPlan usd = two.decide(inputs(Money.ofMinorUnits(5_00, USD)), RAILS, Map.of());
        assertThat(usd.matchedRuleIndex()).contains(1);

        // A payment whose direction or kind matches nothing is a recorded no-rule refusal.
        RoutingPlan unmatched = version(1, List.of(cardRule()))
                .decide(
                        new RoutingInputs(
                                PaymentDirection.PAY_OUT,
                                InstrumentKind.BANK_ACCOUNT,
                                Money.ofMinorUnits(1_00, EUR),
                                Optional.of(true)),
                        RAILS,
                        Map.of());
        assertThat(unmatched.matchedRuleIndex()).isEmpty();
        assertThat(unmatched.steps()).isEmpty();
        assertThat(unmatched.chosen()).isEmpty();

        // The scale trap: a policy priced at a scale the platform does not mint is a loud
        // configuration fault at the first payment it would misjudge.
        RoutingPolicyVersion misScaled = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_IN,
                        InstrumentKind.CARD_TOKEN,
                        Optional.of(EUR),
                        Optional.of(Money.ofPersisted(10_000, EUR, 3)),
                        List.of(CARD))));
        assertThatThrownBy(() -> misScaled.decide(
                        inputs(Money.ofMinorUnits(5_00, EUR)), RAILS, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scale");
    }

    // ----------------------------------------------------------------- eligibility

    @Test
    @DisplayName("every rejection is judged from a stored or declared fact, in ADR-0060"
            + " section 3's order, and the first eligible candidate wins")
    void eligibilityJudgesEveryReason() {
        RoutingPolicyVersion policy = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_IN,
                        InstrumentKind.CARD_TOKEN,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(PUSH_RAIL, RailId.of("retired-rail"), CARD))));

        RoutingPlan plan = policy.decide(inputs(Money.ofMinorUnits(7_00, EUR)), RAILS, Map.of());
        assertThat(plan.chosen()).contains(CARD);
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.steps().get(0).rejection())
                .as("a card token cannot ride the push rail")
                .contains(RoutingRejection.MODEL_CANNOT_CARRY_INSTRUMENT);
        assertThat(plan.steps().get(1).rejection())
                .as("a policy may outlive a build's declarations - recorded, skipped")
                .contains(RoutingRejection.UNDECLARED_BY_BUILD);
        assertThat(plan.steps().get(1).descriptorVersion()).isEmpty();
        assertThat(plan.steps().get(2).verdict()).isEqualTo(RoutingStepVerdict.CHOSEN);
        assertThat(plan.steps().get(2).descriptorVersion())
                .contains(SimulatedCardPspAdapter.RAIL.declarationVersion());

        // The recorded operator fact rejects, and the step freezes the observation it used.
        RoutingPlan disabled = policy.decide(
                inputs(Money.ofMinorUnits(7_00, EUR)),
                RAILS,
                Map.of(CARD, new RailAvailability(CARD, false, "incident", "op-1",
                        Instant.now(CLOCK))));
        assertThat(disabled.chosen()).isEmpty();
        assertThat(disabled.steps().get(2).rejection()).contains(RoutingRejection.UNAVAILABLE);
        assertThat(disabled.steps().get(2).railAvailable()).isFalse();

        // Currency, ceiling and destination, each from the declaration or the stored input.
        RoutingPolicyVersion pushPolicy = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_OUT,
                        InstrumentKind.BANK_ACCOUNT,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(PUSH_RAIL))));
        assertThat(pushPolicy.decide(
                                payOut(Money.ofMinorUnits(5_00, USD), Optional.of(true)),
                                RAILS,
                                Map.of())
                        .steps().get(0).rejection())
                .contains(RoutingRejection.CURRENCY_UNSUPPORTED);
        assertThat(pushPolicy.decide(
                                payOut(Money.ofMinorUnits(50_01, EUR), Optional.of(true)),
                                RAILS,
                                Map.of())
                        .steps().get(0).rejection())
                .contains(RoutingRejection.AMOUNT_EXCEEDS_CEILING);
        assertThat(pushPolicy.decide(
                                payOut(Money.ofMinorUnits(5_00, EUR), Optional.of(false)),
                                RAILS,
                                Map.of())
                        .steps().get(0).rejection())
                .contains(RoutingRejection.DESTINATION_UNREACHABLE);
        assertThat(pushPolicy.decide(
                                payOut(Money.ofMinorUnits(5_00, EUR), Optional.of(true)),
                                RAILS,
                                Map.of())
                        .chosen())
                .contains(PUSH_RAIL);
    }

    @Test
    @DisplayName("a PAY_IN rule naming the corridor rail is refused DIRECTION_UNSUPPORTED - before every"
            + " other reason - while a PAY_OUT on it is judged as usual (P9-TSK-014, ADR-0080 section 1)")
    void aPayInOnACreditsOnlyRailIsRefused() {
        RailId corridor = SimulatedCorridorAdapter.RAIL.id();
        PaymentRails rails = PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, SimulatedCorridorAdapter.RAIL));
        RoutingPolicyVersion payIn = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_IN,
                        InstrumentKind.BANK_ACCOUNT,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(corridor))));
        RoutingPlan refused = payIn.decide(
                new RoutingInputs(
                        PaymentDirection.PAY_IN, InstrumentKind.BANK_ACCOUNT, Money.ofMinorUnits(5_00, USD),
                        Optional.empty()),
                rails,
                Map.of());
        assertThat(refused.chosen()).isEmpty();
        assertThat(refused.steps()).singleElement().satisfies(step -> {
            assertThat(step.rejection()).contains(RoutingRejection.DIRECTION_UNSUPPORTED);
            assertThat(step.descriptorVersion()).contains(SimulatedCorridorAdapter.RAIL.declarationVersion());
        });
        // First among the reasons: a EUR pay-in (a currency the corridor does not carry) is refused
        // for its direction, not its currency.
        assertThat(payIn.decide(
                                new RoutingInputs(
                                        PaymentDirection.PAY_IN, InstrumentKind.BANK_ACCOUNT,
                                        Money.ofMinorUnits(5_00, EUR), Optional.empty()),
                                rails,
                                Map.of())
                        .steps().get(0).rejection())
                .contains(RoutingRejection.DIRECTION_UNSUPPORTED);
        // The control: a PAY_OUT on the same rail passes routing (the withdrawal's lookup refuses it).
        RoutingPolicyVersion payOutPolicy = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_OUT,
                        InstrumentKind.BANK_ACCOUNT,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(corridor))));
        assertThat(payOutPolicy.decide(payOut(Money.ofMinorUnits(5_00, USD), Optional.of(true)), rails, Map.of())
                        .chosen())
                .contains(corridor);
    }

    @Test
    @DisplayName("a rule requiring a destination country matches only a payment naming one: ahead of the"
            + " domestic pay-out it routes the cross-border credit, by per-candidate reachability, and lets a"
            + " domestic pay-out fall through to its own rule (P9-TSK-019, ADR-0080 section 5b)")
    void theCrossBorderRuleMatchesOnlyADestinationCountry() {
        RailId corridor = SimulatedCorridorAdapter.RAIL.id();
        CurrencyCode jpy = CurrencyCode.of("JPY");
        PaymentRails rails = PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, PUSH_DECLARED, SimulatedCorridorAdapter.RAIL));
        RoutingPolicyVersion policy = version(
                5,
                List.of(
                        cardRule(),
                        new RoutingPolicyVersion.NewRule(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT,
                                Optional.empty(), Optional.empty(), List.of(corridor), true),
                        new RoutingPolicyVersion.NewRule(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT,
                                Optional.empty(), Optional.empty(), List.of(PUSH_RAIL))));

        RoutingPlan domestic = policy.decide(payOut(Money.ofMinorUnits(5_00, EUR), Optional.empty()), rails, Map.of());
        assertThat(domestic.matchedRuleIndex()).as("no country: the cross-border rule never matches").contains(2);
        assertThat(domestic.chosen()).contains(PUSH_RAIL);

        RoutingInputs abroad = new RoutingInputs(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT,
                Money.ofMinorUnits(15_000, jpy), Optional.empty(),
                Optional.of(com.finapp.sharedkernel.money.CountryCode.of("JP")), Optional.of(Set.of(corridor)));
        RoutingPlan routed = policy.decide(abroad, rails, Map.of());
        assertThat(routed.matchedRuleIndex()).contains(1);
        assertThat(routed.chosen()).contains(corridor);
        assertThat(policy.decide(abroad, rails, Map.of())).as("recomputed").isEqualTo(routed);

        RoutingPlan unreachable = policy.decide(new RoutingInputs(PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT,
                        Money.ofMinorUnits(15_000, jpy), Optional.empty(),
                        Optional.of(com.finapp.sharedkernel.money.CountryCode.of("JP")), Optional.of(Set.of(PUSH_RAIL))),
                rails, Map.of());
        assertThat(unreachable.chosen()).as("the beneficiary's issuing rail is the only reachable one").isEmpty();
        assertThat(unreachable.steps()).singleElement()
                .satisfies(step -> assertThat(step.rejection()).contains(RoutingRejection.DESTINATION_UNREACHABLE));
    }

    // ----------------------------------------------------------------- recomputation

    @Test
    @DisplayName("recomputation reproduces the plan exactly, and two versions decide the"
            + " same payment differently - INV-RAIL-02's verification")
    void recomputationReproducesTheChoice() {
        RoutingInputs judged = payOut(Money.ofMinorUnits(5_00, EUR), Optional.of(true));
        Map<RailId, RailAvailability> observed = Map.of(
                BOOK_RAIL,
                new RailAvailability(BOOK_RAIL, false, "why not", "op-2", Instant.now(CLOCK)));

        RoutingPolicyVersion one = version(
                1,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_OUT,
                        InstrumentKind.BANK_ACCOUNT,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(PUSH_RAIL))));
        RoutingPolicyVersion two = version(
                2,
                List.of(new RoutingPolicyVersion.NewRule(
                        PaymentDirection.PAY_OUT,
                        InstrumentKind.BANK_ACCOUNT,
                        Optional.of(EUR),
                        Optional.of(Money.ofMinorUnits(4_00, EUR)),
                        List.of(PUSH_RAIL)),
                        new RoutingPolicyVersion.NewRule(
                                PaymentDirection.PAY_OUT,
                                InstrumentKind.BANK_ACCOUNT,
                                Optional.empty(),
                                Optional.empty(),
                                List.of(CARD, PUSH_RAIL))));

        RoutingPlan first = one.decide(judged, RAILS, observed);
        RoutingPlan second = two.decide(judged, RAILS, observed);

        // The pinned version reproduces its own plan, byte for byte - the determinism test.
        assertThat(one.decide(judged, RAILS, observed)).isEqualTo(first);
        assertThat(two.decide(judged, RAILS, observed)).isEqualTo(second);

        // And the versions genuinely disagree, so the pin is load-bearing (INV-HIST-04):
        // version 1 sends it straight on the push rail; version 2's tighter band does not
        // match, its catch-all tries the card first (rejected: a bank account cannot ride
        // two-step) and then the push rail.
        assertThat(first.matchedRuleIndex()).contains(0);
        assertThat(first.steps()).hasSize(1);
        assertThat(second.matchedRuleIndex()).contains(1);
        assertThat(second.steps()).hasSize(2);
        assertThat(second.steps().get(0).rejection())
                .contains(RoutingRejection.MODEL_CANNOT_CARRY_INSTRUMENT);
        assertThat(first.chosen()).contains(PUSH_RAIL);
        assertThat(second.chosen()).contains(PUSH_RAIL);
    }

    @Test
    @DisplayName("the SQL fragments are pinned until V013's reconciliation consumes them")
    void theSqlFragmentsArePinned() {
        assertThat(PaymentDirection.sqlValueList()).isEqualTo("'PAY_IN', 'PAY_OUT'");
        assertThat(InstrumentKind.sqlValueList())
                .isEqualTo("'CARD_TOKEN', 'BANK_ACCOUNT', 'WALLET'");
        assertThat(RoutingStepVerdict.sqlValueList())
                .isEqualTo("'CHOSEN', 'REJECTED', 'ABANDONED'");
        assertThat(RoutingRejection.sqlValueList())
                .isEqualTo("'UNAVAILABLE', 'CURRENCY_UNSUPPORTED', 'AMOUNT_EXCEEDS_CEILING',"
                        + " 'MODEL_CANNOT_CARRY_INSTRUMENT', 'DESTINATION_UNREACHABLE',"
                        + " 'NOTHING_SENT', 'UNDECLARED_BY_BUILD', 'DIRECTION_UNSUPPORTED'");
        assertThatCode(() -> RoutingPolicyVersion.create(
                        IDS, 1, List.of(cardRule()), Optional.empty(), "op-1", "why", CLOCK))
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------------------- fixtures

    private static RoutingPolicyVersion.NewRule cardRule() {
        return new RoutingPolicyVersion.NewRule(
                PaymentDirection.PAY_IN,
                InstrumentKind.CARD_TOKEN,
                Optional.empty(),
                Optional.empty(),
                List.of(CARD));
    }

    private static RoutingPolicyVersion version(
            int number, List<RoutingPolicyVersion.NewRule> rules) {
        return RoutingPolicyVersion.create(
                IDS, number, rules, Optional.empty(), "op-1", "the test's reason", CLOCK);
    }

    private static RoutingRule rule(
            PaymentDirection direction,
            InstrumentKind kind,
            Optional<CurrencyCode> currency,
            Optional<Money> ceiling,
            List<RailId> rails) {
        return new RoutingRule(
                RoutingRuleId.next(IDS), 0, direction, kind, currency, ceiling, rails);
    }

    private static RoutingInputs inputs(Money amount) {
        return new RoutingInputs(
                PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN, amount, Optional.empty());
    }

    private static RoutingInputs payOut(Money amount, Optional<Boolean> reachable) {
        return new RoutingInputs(
                PaymentDirection.PAY_OUT, InstrumentKind.BANK_ACCOUNT, amount, reachable);
    }
}
