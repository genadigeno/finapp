package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.sourcejoin.ExpectedLeg;
import com.finapp.platform.sourcejoin.SourceFigures;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The comparison arithmetic (`P15-TSK-035`): an entry's lines against a record's figures, per currency, in minor units - the entry's
 * "comparison arithmetic" hermetic case, and the probe "the comparison ignoring currency".
 */
@DisplayName("the source-join comparison: per currency, in minor units (P15-TSK-035)")
class SourceJoinComparisonTest {

    private static final String USD = "USD";
    private static final String JPY = "JPY";

    private static JournalShapes.ShapeLine line(Side side, String purpose, String currency, int scale, long minor) {
        return new JournalShapes.ShapeLine(1, purpose, side, currency, scale, BigInteger.valueOf(minor));
    }

    @Test
    @DisplayName("equal totals in the currency agree; a different amount, in the same currency, does not")
    void equalTotalsAgree() {
        List<JournalShapes.ShapeLine> lines = List.of(
                line(Side.DEBIT, "CUSTOMER_WALLET", USD, 2, 12_500),
                line(Side.CREDIT, "CUSTOMER_WALLET", USD, 2, 12_500));
        assertThat(SourceJoinComparison.mismatch(lines, SourceFigures.total(USD, 2, 12_500))).isEmpty();
        assertThat(SourceJoinComparison.mismatch(lines, SourceFigures.total(USD, 2, 12_499))).isPresent();
    }

    @Test
    @DisplayName("the currency is the comparison: the right number of the wrong currency is a mismatch, never a pass (the probe)")
    void theCurrencyIsTheComparison() {
        List<JournalShapes.ShapeLine> lines = List.of(
                line(Side.DEBIT, "CUSTOMER_WALLET", JPY, 0, 12_500),
                line(Side.CREDIT, "CUSTOMER_WALLET", JPY, 0, 12_500));
        assertThat(SourceJoinComparison.mismatch(lines, SourceFigures.total(USD, 0, 12_500)))
                .as("12,500 JPY against a record of 12,500 USD").isPresent();
    }

    @Test
    @DisplayName("a line at another scale in the currency is a mismatch: minor units are not compared across scales")
    void anotherScaleIsAMismatch() {
        List<JournalShapes.ShapeLine> lines = List.of(
                line(Side.DEBIT, "CUSTOMER_WALLET", USD, 3, 125_000),
                line(Side.CREDIT, "CUSTOMER_WALLET", USD, 3, 125_000));
        assertThat(SourceJoinComparison.mismatch(lines, SourceFigures.total(USD, 2, 12_500))).isPresent();
    }

    @Test
    @DisplayName("two currencies, each its own figure: FX legs agree per currency, and one wrong figure in either is a mismatch")
    void fxLegsCompareInEachCurrency() {
        List<JournalShapes.ShapeLine> lines = List.of(
                line(Side.DEBIT, "CUSTOMER_WALLET", USD, 2, 10_000),
                line(Side.CREDIT, "FX_POSITION", USD, 2, 10_000),
                line(Side.DEBIT, "FX_POSITION", JPY, 0, 1_500_000),
                line(Side.CREDIT, "CUSTOMER_WALLET", JPY, 0, 1_500_000));
        SourceFigures figures = SourceFigures.of(
                ExpectedLeg.exactOn(ExpectedLeg.Side.DEBIT, Set.of("CUSTOMER_WALLET"), USD, 2, 10_000),
                ExpectedLeg.exactOn(ExpectedLeg.Side.CREDIT, Set.of("CUSTOMER_WALLET"), JPY, 0, 1_500_000));
        assertThat(SourceJoinComparison.mismatch(lines, figures)).isEmpty();
        assertThat(SourceJoinComparison.mismatch(lines, SourceFigures.of(
                ExpectedLeg.exactOn(ExpectedLeg.Side.DEBIT, Set.of("CUSTOMER_WALLET"), USD, 2, 10_000),
                ExpectedLeg.exactOn(ExpectedLeg.Side.CREDIT, Set.of("CUSTOMER_WALLET"), JPY, 0, 1_499_999)))).isPresent();
    }

    @Test
    @DisplayName("a fee taken net posts on other purposes: a figure on its purposes is unmoved by them, and moved if the gross is wrong")
    void aFigureOnItsPurposesIgnoresTheFeeLines() {
        List<JournalShapes.ShapeLine> capture = List.of(
                line(Side.DEBIT, "SETTLEMENT_CLEARING", USD, 2, 10_000),
                line(Side.CREDIT, "MERCHANT_PAYABLE", USD, 2, 10_000),
                line(Side.DEBIT, "MERCHANT_PAYABLE", USD, 2, 300),
                line(Side.CREDIT, "FEE_REVENUE", USD, 2, 300));
        SourceFigures gross = SourceFigures.of(
                ExpectedLeg.exactOn(ExpectedLeg.Side.DEBIT, Set.of("SETTLEMENT_CLEARING"), USD, 2, 10_000),
                ExpectedLeg.exactOn(ExpectedLeg.Side.CREDIT, Set.of("CUSTOMER_WALLET", "MERCHANT_PAYABLE"), USD, 2, 10_000));
        assertThat(SourceJoinComparison.mismatch(capture, gross)).isEmpty();
        assertThat(SourceJoinComparison.mismatch(capture, SourceFigures.of(
                ExpectedLeg.exactOn(ExpectedLeg.Side.DEBIT, Set.of("SETTLEMENT_CLEARING"), USD, 2, 10_001),
                ExpectedLeg.exactOn(ExpectedLeg.Side.CREDIT, Set.of("CUSTOMER_WALLET", "MERCHANT_PAYABLE"), USD, 2, 10_000)))).isPresent();
    }

    @Test
    @DisplayName("at most: within the ceiling agrees, over it does not, and no line in the ceiling's currency is not within it")
    void atMostIsACeilingNotAnAbsence() {
        SourceFigures ceiling = SourceFigures.of(ExpectedLeg.atMost(ExpectedLeg.Side.DEBIT, USD, 2, 5_000),
                ExpectedLeg.atMost(ExpectedLeg.Side.CREDIT, USD, 2, 5_000));
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "MERCHANT_PAYABLE", USD, 2, 4_999), line(Side.CREDIT, "CHARGEBACK_RECOVERABLE", USD, 2, 4_999)), ceiling)).isEmpty();
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "MERCHANT_PAYABLE", USD, 2, 5_001), line(Side.CREDIT, "CHARGEBACK_RECOVERABLE", USD, 2, 5_001)), ceiling)).isPresent();
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "MERCHANT_PAYABLE", JPY, 0, 5_000), line(Side.CREDIT, "CHARGEBACK_RECOVERABLE", JPY, 0, 5_000)), ceiling))
                .as("all of it in another currency").isPresent();
    }

    @Test
    @DisplayName("a side that should be zero must be: a flipped sign on a purpose-held figure is a mismatch")
    void aFlippedSignIsAMismatch() {
        // A statement's cash fold: net positive, so a debit to cash of 900 and nothing on the credit side.
        SourceFigures figures = SourceFigures.of(
                ExpectedLeg.exactOn(ExpectedLeg.Side.DEBIT, Set.of("CASH_AT_BANK"), USD, 2, 900),
                ExpectedLeg.exactOn(ExpectedLeg.Side.CREDIT, Set.of("CASH_AT_BANK"), USD, 2, 0));
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "CASH_AT_BANK", USD, 2, 900), line(Side.CREDIT, "SETTLEMENT_CLEARING", USD, 2, 900)), figures)).isEmpty();
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "SETTLEMENT_CLEARING", USD, 2, 900), line(Side.CREDIT, "CASH_AT_BANK", USD, 2, 900)), figures))
                .as("the same amount on the opposite side").isPresent();
    }

    @Test
    @DisplayName("presence alone compares nothing; a record that should state a figure and does not is a mismatch")
    void presenceAndNoFigure() {
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "CUSTOMER_WALLET", USD, 2, 1)), SourceFigures.presenceOnly("a stated reason"))).isEmpty();
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "CUSTOMER_WALLET", USD, 2, 1)), SourceFigures.noFigure())).isPresent();
    }

    @Test
    @DisplayName("the reason names a side, a currency and purposes: never an amount")
    void theReasonNamesNoAmount() {
        assertThat(SourceJoinComparison.mismatch(List.of(line(Side.DEBIT, "CUSTOMER_WALLET", USD, 2, 12_345)), SourceFigures.total(USD, 2, 98_765)))
                .hasValueSatisfying(reason -> assertThat(reason).doesNotContain("12345", "98765", "12,345", "98,765").contains("DEBIT", USD));
    }
}
