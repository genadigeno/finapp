package com.finapp.platform.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The column mapping, without a database. {@link MoneyColumnsDatabaseTest} proves the same
 * values survive a real PostgreSQL round-trip; these cover the conversion and its refusals.
 */
class MoneyColumnsTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    @DisplayName("column names are prefixed so one table can hold several amounts")
    void columnNamesArePrefixed() {
        MoneyColumns.ColumnNames gross = MoneyColumns.columnsFor("gross");

        assertThat(gross.amountMinor()).isEqualTo("gross_amount_minor");
        assertThat(gross.currency()).isEqualTo("gross_currency");
        assertThat(gross.scale()).isEqualTo("gross_scale");

        // Two amounts in one table must not collide, and neither becomes "the" amount.
        assertThat(MoneyColumns.columnsFor("fee").amountMinor()).isEqualTo("fee_amount_minor");
    }

    @Test
    @DisplayName("the DDL fragment pins the types and makes every column NOT NULL")
    void ddlPinsTypesAndNullability() {
        String ddl = MoneyColumns.columnsFor("gross").ddl();

        assertThat(ddl)
                .contains("gross_amount_minor BIGINT NOT NULL")
                .contains("gross_currency CHAR(3) NOT NULL")
                .contains("gross_scale SMALLINT NOT NULL");
        // A partially populated amount is not less information, it is uninterpretable.
        assertThat(ddl.split("NOT NULL", -1)).hasSize(4);
    }

    @Test
    @DisplayName("the DDL constrains the currency and scale, because CHAR(3) does not")
    void ddlConstrainsValues() {
        String ddl = MoneyColumns.columnsFor("gross").ddl();

        // CHAR(3) pads rather than rejects, so it accepts 'US ' as readily as 'USD'. Without
        // a check constraint the only thing between a malformed code and a balance is the
        // application remembering to validate on read.
        assertThat(ddl).contains("CHECK (gross_currency ~ '^[A-Z]{3}$')");

        // Generated from Money's own bound so the two cannot drift.
        assertThat(ddl)
                .contains("CHECK (gross_scale BETWEEN 0 AND " + Money.MAX_SUPPORTED_SCALE + ")");
    }

    @Test
    @DisplayName("the nullable DDL keeps the value CHECKs and adds the all-or-nothing rule")
    void nullableDdlIsAllOrNothing() {
        String ddl = MoneyColumns.columnsFor("authorized").nullableDdl();

        // Nullable, same types - the fact arrives with a later transition (P5-TSK-008).
        assertThat(ddl)
                .contains("authorized_amount_minor BIGINT, ")
                .contains("authorized_currency CHAR(3), ")
                .contains("authorized_scale SMALLINT, ")
                .doesNotContain("NOT NULL");

        // The value CHECKs apply exactly when a value is present (a CHECK over NULL is not
        // false), and ddl()'s uninterpretable-partial-amount reasoning moves into its own
        // generated CHECK, NOT NULL no longer being there to carry it.
        assertThat(ddl)
                .contains("CHECK (authorized_currency ~ '^[A-Z]{3}$')")
                .contains("CHECK (authorized_scale BETWEEN 0 AND " + Money.MAX_SUPPORTED_SCALE
                        + ")")
                .contains("CHECK ((authorized_amount_minor IS NULL)"
                        + " = (authorized_currency IS NULL)"
                        + " AND (authorized_amount_minor IS NULL)"
                        + " = (authorized_scale IS NULL))");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("rejects a blank field name")
    void rejectsBlankFieldName(String fieldName) {
        assertThatThrownBy(() -> MoneyColumns.columnsFor(fieldName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a null field name or amount")
    void rejectsNulls() {
        assertThatThrownBy(() -> MoneyColumns.columnsFor(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.amountMinorOf(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.currencyOf(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyColumns.scaleOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "{0} writes minor units {1} and scale {2}")
    @CsvSource({"USD, 1234, 2", "JPY, 100, 0", "BHD, 1234, 3", "CLF, 1, 4"})
    @DisplayName("writes the three column values for every currency scale")
    void writesColumnValues(String code, long minorUnits, short scale) {
        Money money = Money.ofMinorUnits(minorUnits, CurrencyCode.of(code));

        assertThat(MoneyColumns.amountMinorOf(money)).isEqualTo(minorUnits);
        assertThat(MoneyColumns.currencyOf(money)).isEqualTo(code);
        assertThat(MoneyColumns.scaleOf(money)).isEqualTo(scale);
    }

    @Test
    @DisplayName("the currency value is exactly three characters, for a CHAR(3) column")
    void currencyValueFitsTheColumn() {
        assertThat(MoneyColumns.currencyOf(Money.ofMinorUnits(1L, USD))).hasSize(3);
    }

    @ParameterizedTest(name = "{0} round-trips in memory")
    @ValueSource(strings = {"USD", "JPY", "BHD", "CLF"})
    @DisplayName("write then read returns an equal amount")
    void roundTripsInMemory(String code) {
        CurrencyCode currency = CurrencyCode.of(code);

        for (long minorUnits : new long[] {0L, 1L, -1L, 123_456L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Money original = Money.ofMinorUnits(minorUnits, currency);

            Money restored =
                    MoneyColumns.read(
                            MoneyColumns.amountMinorOf(original),
                            MoneyColumns.currencyOf(original),
                            MoneyColumns.scaleOf(original));

            assertThat(restored).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("reads back the stored scale, not the currency's present one")
    void readsStoredScaleNotCurrentScale() {
        // The point of storing scale: this row was written when USD had three decimals. It
        // must still read as 1.234, not be reinterpreted as 12.34 under today's definition.
        Money restored = MoneyColumns.read(1234L, "USD", (short) 3);

        assertThat(restored.scale()).isEqualTo(3);
        assertThat(restored).isEqualTo(Money.ofPersisted(1234L, USD, 3));
        assertThat(restored).isNotEqualTo(Money.ofMinorUnits(1234L, USD));
    }

    @Test
    @DisplayName("a leading space is corruption, not padding, and is refused")
    void leadingWhitespaceIsNotPadding() {
        // CHAR pads on the right only. Accepting " US" would be coercing a value the column
        // type could not have produced.
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, " US", (short) 2));
    }

    @Test
    @DisplayName("tolerates CHAR(3) blank padding, which the column type adds")
    void tolerantOfCharPadding() {
        // Trimming padding the column type introduced is not the silent coercion
        // CurrencyCode forbids — that rule is about a caller supplying "usd".
        assertThat(MoneyColumns.read(100L, "USD  ", (short) 2))
                .isEqualTo(Money.ofMinorUnits(100L, USD));
    }

    @Test
    @DisplayName("refuses a null currency rather than guessing one")
    void refusesNullCurrency() {
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, null, (short) 2))
                .withMessageContaining("INV-MON-02");
    }

    @Test
    @DisplayName("refuses a corrupt row rather than returning an approximation")
    void refusesCorruptRows() {
        // An unknown currency, a pseudo-currency, and an impossible scale are all corruption.
        // Returning zero or defaulting the currency would convert a detectable problem into
        // a wrong balance.
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, "ZZZ", (short) 2));
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, "XXX", (short) 2));
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, "USD", (short) -1));
        assertThatExceptionOfType(MonetaryColumnException.class)
                .isThrownBy(() -> MoneyColumns.read(100L, "usd", (short) 2));
    }

    @Test
    @DisplayName("a corrupt row names its stored values so the row can be found")
    void corruptRowIsDiagnosable() {
        assertThatThrownBy(() -> MoneyColumns.read(999L, "ZZZ", (short) 2))
                .hasMessageContaining("999")
                .hasMessageContaining("ZZZ")
                .hasMessageContaining("2");
    }
}
