package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.ScaleMismatchException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shapes a journal entry must refuse, each the smallest example of its class
 * (`P3-TSK-004`). The generated sweep is {@code JournalEntryPropertiesTest}; these are the
 * named cases a reviewer reads.
 */
@DisplayName("the JournalEntry construction rules (P3-TSK-004)")
class JournalEntryTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate POSTING = LocalDate.of(2026, 9, 13);
    private static final LocalDate VALUE = LocalDate.of(2026, 9, 15);

    @Test
    @DisplayName("a balanced two-line entry constructs, immutable and in order")
    void aBalancedEntryConstructs() {
        JournalLine debit = line(Direction.DEBIT, Money.ofMinorUnits(1500, USD));
        JournalLine credit = line(Direction.CREDIT, Money.ofMinorUnits(1500, USD));
        JournalEntry entry =
                JournalEntry.balanced(IDS, CLOCK, POSTING, VALUE, List.of(debit, credit));

        assertThat(entry.lines()).containsExactly(debit, credit);
        assertThat(entry.postingDate()).isEqualTo(POSTING);
        assertThat(entry.valueDate()).isEqualTo(VALUE);
        assertThatThrownBy(() -> entry.lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an unbalanced entry throws naming the currency and never the amounts")
    void anUnbalancedEntryThrowsWithoutAmounts() {
        assertThatThrownBy(
                        () ->
                                JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(987654, USD)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(123456, USD)))))
                .isInstanceOf(UnbalancedJournalEntryException.class)
                .hasMessageContaining("USD")
                // The message reaches logs and the amounts are RESTRICTED-FINANCIAL
                // (INV-AUD-02) - so the refusal names the fact, never the sums.
                .hasMessageNotContaining("987654")
                .hasMessageNotContaining("123456");
    }

    @Test
    @DisplayName("fewer than two lines is refused, whatever the sums say")
    void fewerThanTwoLinesIsRefused() {
        // A single line cannot balance, but the rule is INV-LED-02's own and is checked first:
        // an empty entry sums to zero on both sides of every currency, so a balance check
        // alone would wave it through - which is why the line-count refusal exists separately.
        assertThatThrownBy(
                        () ->
                                JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(100, USD)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-LED-02");
        assertThatThrownBy(
                        () -> JournalEntry.balanced(IDS, CLOCK, POSTING, VALUE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a multi-currency entry balances in each currency, never in total")
    void crossSubsidyBetweenCurrenciesIsRefused() {
        // The exact case "per currency" exists for: EUR over by 10, USD under by 10, "total"
        // zero - and a model that summed across currencies would accept it, which is
        // cross-currency arithmetic wearing a summary's clothes (INV-MON-04).
        assertThatThrownBy(
                        () ->
                                JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(110, EUR)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(100, EUR)),
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(100, USD)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(110, USD)))))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        // And balanced in each is accepted - one entry, two currencies, both books whole.
        assertThat(
                        JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(100, EUR)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(100, EUR)),
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(250, USD)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(250, USD))))
                                .lines())
                .hasSize(4);
    }

    @Test
    @DisplayName("mixed scales within one currency are refused, never normalised")
    void mixedScalesWithinOneCurrencyAreRefused() {
        // Two USD lines at scale 2 and scale 3 on ONE side: the sum is not computable, and
        // computing it anyway would be the implicit rescale INV-MON-03 forbids - so
        // Money.plus's own refusal propagates.
        assertThatThrownBy(
                        () ->
                                JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(100, USD)),
                                                line(Direction.DEBIT,
                                                        Money.ofPersisted(1000, USD, 3)),
                                                line(Direction.CREDIT,
                                                        Money.ofMinorUnits(200, USD)))))
                .isInstanceOf(ScaleMismatchException.class);

        // Across sides the mix never meets a plus(), so it surfaces as Money's own equality
        // refusing the comparison: 1.50 at scale 2 and 1.500 at scale 3 are not the same
        // stored fact (INV-MON-05), and an entry asserting they cancel is not balanced.
        assertThatThrownBy(
                        () ->
                                JournalEntry.balanced(
                                        IDS, CLOCK, POSTING, VALUE,
                                        List.of(
                                                line(Direction.DEBIT,
                                                        Money.ofMinorUnits(150, USD)),
                                                line(Direction.CREDIT,
                                                        Money.ofPersisted(1500, USD, 3)))))
                .isInstanceOf(UnbalancedJournalEntryException.class);
    }

    @Test
    @DisplayName("a line refuses zero and negative amounts: the direction carries the sign")
    void aLineRefusesZeroAndNegative() {
        assertThatThrownBy(
                        () -> line(Direction.DEBIT, Money.zero(USD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> line(Direction.CREDIT, Money.ofMinorUnits(-100, USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("both dates are demanded, which is what makes a clock-derived one impossible")
    void bothDatesAreDemanded() {
        List<JournalLine> lines =
                List.of(
                        line(Direction.DEBIT, Money.ofMinorUnits(100, USD)),
                        line(Direction.CREDIT, Money.ofMinorUnits(100, USD)));
        assertThatThrownBy(() -> JournalEntry.balanced(IDS, CLOCK, null, VALUE, lines))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("postingDate");
        assertThatThrownBy(() -> JournalEntry.balanced(IDS, CLOCK, POSTING, null, lines))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueDate");
    }

    @Test
    @DisplayName("no rendering of an entry or a line carries an amount")
    void noRenderingCarriesAnAmount() {
        // INV-AUD-02 at the type: a toString is what an incautious log statement prints, and
        // 987654 minor units is the searchable needle this asserts absent.
        JournalEntry entry =
                JournalEntry.balanced(
                        IDS, CLOCK, POSTING, VALUE,
                        List.of(
                                line(Direction.DEBIT, Money.ofMinorUnits(987654, USD)),
                                line(Direction.CREDIT, Money.ofMinorUnits(987654, USD))));
        assertThat(entry.toString()).doesNotContain("987654").doesNotContain("9876.54");
        for (JournalLine line : entry.lines()) {
            assertThat(line.toString()).doesNotContain("987654").doesNotContain("9876.54");
        }
    }

    private static JournalLine line(Direction direction, Money amount) {
        return new JournalLine(LedgerAccountId.next(IDS), direction, amount);
    }
}
