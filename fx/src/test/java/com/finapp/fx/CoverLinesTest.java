package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cover's lines (`P9-TSK-012`; PHASE_9_PLAN.md sections 12.4(b) and (f), ADR-0077 section 6;
 * INV-FX-06, INV-FX-08): the plan's position legs closed EXACTLY onto the provider's clearing, the
 * difference to realised gains or losses in that leg's own currency - each currency balanced, nothing
 * converted, the off-plan flag the fixed leg's.
 */
@DisplayName("a cover's lines close exactly the plan's legs (P9-TSK-012)")
class CoverLinesTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Map<LedgerAccountId, String> NAMES = new LinkedHashMap<>();
    private static final CoverLines.Accounts ACCOUNTS = new CoverLines.Accounts(
            account("FX_POSITION(S)"), account("FX_POSITION(B)"), account("CLEARING(S)"), account("CLEARING(B)"),
            account("GAINS(S)"), account("LOSSES(S)"), account("GAINS(B)"), account("LOSSES(B)"));

    @Test
    @DisplayName("(b) at the plan: FX_POSITION closes onto fx-sim-a's clearing at 1,000.00 EUR / 1,085.02 USD - no result line")
    void atThePlanNoResult() {
        CoverLines.Plan plan = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        List<JournalLine> lines = CoverLines.compose(plan, new CoverLines.Execution(money("1000.00", EUR), money("1085.02", USD)), ACCOUNTS);
        assertThat(rendered(lines)).containsExactly(
                "FX_POSITION(S) DEBIT 1000.00 EUR", "CLEARING(S) CREDIT 1000.00 EUR",
                "CLEARING(B) DEBIT 1085.02 USD", "FX_POSITION(B) CREDIT 1085.02 USD");
        assertThat(CoverLines.realised(plan, new CoverLines.Execution(money("1000.00", EUR), money("1085.02", USD))))
                .isEqualTo(new CoverLines.Realised(0, 0, false));
    }

    @Test
    @DisplayName("(f) the cover slips: the requote buys 1,084.10 USD - FX_POSITION still closes at 1,085.02 and the"
            + " 0.92 USD is a realised loss, on plan (the fixed leg held)")
    void theCoverSlips() {
        CoverLines.Plan plan = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        CoverLines.Execution executed = new CoverLines.Execution(money("1000.00", EUR), money("1084.10", USD));
        assertThat(rendered(CoverLines.compose(plan, executed, ACCOUNTS))).containsExactly(
                "FX_POSITION(S) DEBIT 1000.00 EUR", "CLEARING(S) CREDIT 1000.00 EUR",
                "CLEARING(B) DEBIT 1084.10 USD", "FX_POSITION(B) CREDIT 1085.02 USD", "LOSSES(B) DEBIT 0.92 USD");
        assertThat(CoverLines.realised(plan, executed)).isEqualTo(new CoverLines.Realised(0, -92, false));
    }

    @Test
    @DisplayName("a better requote is a realised GAIN in the computed leg - never netted, never in the other currency")
    void aBetterRequoteIsAGain() {
        CoverLines.Plan plan = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        CoverLines.Execution executed = new CoverLines.Execution(money("1000.00", EUR), money("1086.00", USD));
        assertThat(rendered(CoverLines.compose(plan, executed, ACCOUNTS)))
                .contains("GAINS(B) CREDIT 0.98 USD")
                .noneMatch(line -> line.startsWith("LOSSES") || line.startsWith("GAINS(S)"));
    }

    @Test
    @DisplayName("a provider deviating on the FIXED leg is off plan in either direction - booked exactly, the"
            + " difference realised in that leg's currency")
    void theFixedLegDeviatingIsOffPlan() {
        CoverLines.Plan bySource = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        CoverLines.Execution soldLess = new CoverLines.Execution(money("999.00", EUR), money("1085.02", USD));
        assertThat(CoverLines.realised(bySource, soldLess)).isEqualTo(new CoverLines.Realised(100, 0, true));
        assertThat(rendered(CoverLines.compose(bySource, soldLess, ACCOUNTS))).contains("GAINS(S) CREDIT 1.00 EUR");

        CoverLines.Plan byDestination = new CoverLines.Plan(money("921.64", EUR), money("1000.00", USD), FixedSide.FIXED_DESTINATION);
        CoverLines.Execution boughtLess = new CoverLines.Execution(money("921.64", EUR), money("999.99", USD));
        assertThat(CoverLines.realised(byDestination, boughtLess)).isEqualTo(new CoverLines.Realised(0, -1, true));
        CoverLines.Execution computedMoved = new CoverLines.Execution(money("922.00", EUR), money("1000.00", USD));
        assertThat(CoverLines.realised(byDestination, computedMoved))
                .as("a fixed-destination requote moves only the source - on plan")
                .isEqualTo(new CoverLines.Realised(-36, 0, false));
        assertThat(rendered(CoverLines.compose(byDestination, computedMoved, ACCOUNTS))).contains("LOSSES(S) DEBIT 0.36 EUR");
    }

    @Test
    @DisplayName("zero and three minor units: JPY and BHD legs balance at their own scales")
    void zeroAndThreeMinorUnits() {
        CoverLines.Plan plan = new CoverLines.Plan(money("12.345", BHD), money("4907", JPY), FixedSide.FIXED_SOURCE);
        CoverLines.Execution executed = new CoverLines.Execution(money("12.345", BHD), money("4905", JPY));
        List<JournalLine> lines = CoverLines.compose(plan, executed, ACCOUNTS);
        assertThat(rendered(lines)).containsExactly(
                "FX_POSITION(S) DEBIT 12.345 BHD", "CLEARING(S) CREDIT 12.345 BHD",
                "CLEARING(B) DEBIT 4905 JPY", "FX_POSITION(B) CREDIT 4907 JPY", "LOSSES(B) DEBIT 2 JPY");
    }

    @Test
    @DisplayName("every composition balances per currency - debits equal credits in each, over a sweep of executions")
    void everyCompositionBalancesPerCurrency() {
        CoverLines.Plan plan = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        for (long sold = 99_000; sold <= 101_000; sold += 250) {
            for (long bought = 107_000; bought <= 110_000; bought += 333) {
                List<JournalLine> lines = CoverLines.compose(plan,
                        new CoverLines.Execution(Money.ofPersisted(sold, EUR, 2), Money.ofPersisted(bought, USD, 2)), ACCOUNTS);
                for (CurrencyCode currency : List.of(EUR, USD)) {
                    long net = lines.stream()
                            .filter(line -> line.amount().currency().equals(currency))
                            .mapToLong(line -> line.direction() == Direction.DEBIT
                                    ? line.amount().minorUnits() : -line.amount().minorUnits())
                            .sum();
                    assertThat(net).as("%s balances at sold %d, bought %d", currency, sold, bought).isZero();
                }
                assertThat(lines).filteredOn(line -> NAMES.get(line.account()).startsWith("FX_POSITION"))
                        .extracting(line -> line.amount().minorUnits())
                        .as("FX_POSITION always closes exactly the plan").containsExactly(100_000L, 108_502L);
            }
        }
    }

    @Test
    @DisplayName("an execution in other currencies, or at another scale, is not about this plan: incoherent, composes nothing")
    void anIncoherentExecutionComposesNothing() {
        CoverLines.Plan plan = new CoverLines.Plan(money("1000.00", EUR), money("1085.02", USD), FixedSide.FIXED_SOURCE);
        CoverLines.Execution swapped = new CoverLines.Execution(money("1085.02", USD), money("1000.00", EUR));
        assertThat(CoverLines.coherent(plan, swapped)).isFalse();
        assertThatThrownBy(() -> CoverLines.compose(plan, swapped, ACCOUNTS)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CoverLines.coherent(plan, new CoverLines.Execution(money("1000.00", EUR), money("1085.02", USD)))).isTrue();
    }

    // -----------------------------------------------------------------

    private static LedgerAccountId account(String name) {
        LedgerAccountId id = LedgerAccountId.next(IDS);
        NAMES.put(id, name);
        return id;
    }

    private static Money money(String amount, CurrencyCode currency) {
        return Money.of(new BigDecimal(amount), currency);
    }

    private static List<String> rendered(List<JournalLine> lines) {
        return lines.stream()
                .map(line -> NAMES.get(line.account()) + " " + line.direction() + " "
                        + line.amount().toBigDecimal().toPlainString() + " " + line.amount().currency().code())
                .toList();
    }
}
