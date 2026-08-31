package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the platform's most consequential type. A defect here reaches every posting the
 * ledger will ever write, so these cover the failure modes rather than the happy path: what
 * happens across currencies, at the representable limits, and with more precision than a
 * currency has.
 */
class MoneyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");
    private static final CurrencyCode BHD = CurrencyCode.of("BHD");

    @Nested
    @DisplayName("construction")
    class Construction {

        @ParameterizedTest(name = "{0} has scale {1}")
        @CsvSource({"USD, 2", "EUR, 2", "JPY, 0", "BHD, 3", "CLF, 4"})
        @DisplayName("takes its scale from the currency, across 0-, 2-, 3- and 4-decimal currencies")
        void scaleComesFromCurrency(String code, int expectedScale) {
            Money money = Money.ofMinorUnits(1L, CurrencyCode.of(code));

            assertThat(money.scale()).isEqualTo(expectedScale);
            assertThat(money.minorUnits()).isEqualTo(1L);
        }

        @Test
        @DisplayName("requires an explicit currency — there is no ambient default")
        void requiresExplicitCurrency() {
            assertThatThrownBy(() -> Money.ofMinorUnits(1L, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("currency");

            assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("currency");
        }

        @Test
        @DisplayName("zero is currency-scoped, never a bare zero")
        void zeroCarriesCurrency() {
            assertThat(Money.zero(USD).currency()).isEqualTo(USD);
            assertThat(Money.zero(USD).isZero()).isTrue();
            assertThat(Money.zero(USD)).isNotEqualTo(Money.zero(EUR));
        }

        @Test
        @DisplayName("converts an exact decimal amount, trailing zeros included")
        void acceptsExactDecimalAmounts() {
            assertThat(Money.of(new BigDecimal("12.34"), USD).minorUnits()).isEqualTo(1234L);
            // 12.3400 is the same value at a longer scale; reducing it loses nothing.
            assertThat(Money.of(new BigDecimal("12.3400"), USD).minorUnits()).isEqualTo(1234L);
            assertThat(Money.of(new BigDecimal("100"), JPY).minorUnits()).isEqualTo(100L);
            assertThat(Money.of(new BigDecimal("1.234"), BHD).minorUnits()).isEqualTo(1234L);
        }

        @Test
        @DisplayName("refuses an amount with more precision than the currency has")
        void rejectsAmountsNeedingRounding() {
            // Choosing between 12.34 and 12.35 is a rounding decision with a named mode
            // behind it (P0-TSK-010), not something a constructor may make.
            assertThatExceptionOfType(InexactAmountException.class)
                    .isThrownBy(() -> Money.of(new BigDecimal("12.345"), USD))
                    .satisfies(
                            e -> {
                                assertThat(e.currency()).isEqualTo(USD);
                                assertThat(e.amount()).isEqualByComparingTo(new BigDecimal("12.345"));
                            })
                    .withMessageContaining("rounding mode");

            assertThatExceptionOfType(InexactAmountException.class)
                    .isThrownBy(() -> Money.of(new BigDecimal("100.5"), JPY));
        }

        @Test
        @DisplayName("every monetary failure is catchable as MonetaryException")
        void allMonetaryFailuresShareOneSupertype() {
            // A caller should not have to know which JDK exception leaks out of which path.
            assertThatThrownBy(() -> Money.of(new BigDecimal("12.345"), USD))
                    .isInstanceOf(MonetaryException.class);
            assertThatThrownBy(() -> Money.ofMinorUnits(1L, USD).plus(Money.ofMinorUnits(1L, EUR)))
                    .isInstanceOf(MonetaryException.class);
            assertThatThrownBy(() -> Money.ofMinorUnits(1L, USD).plus(Money.ofPersisted(1L, USD, 3)))
                    .isInstanceOf(MonetaryException.class);
            assertThatThrownBy(() -> Money.ofMinorUnits(Long.MAX_VALUE, USD).times(2L))
                    .isInstanceOf(MonetaryException.class);
        }

        @Test
        @DisplayName("refuses a decimal amount outside the representable range")
        void rejectsUnrepresentableDecimalAmounts() {
            BigDecimal tooLarge = new BigDecimal("99999999999999999999.99");

            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> Money.of(tooLarge, USD));
        }

        @Test
        @DisplayName("rehydrates a stored amount with the scale it was written with")
        void rehydratesStoredScale() {
            // The whole point of storing scale: this amount was written when the currency had
            // three decimal places, and must still read as 1.234 if that data later changes.
            Money historical = Money.ofPersisted(1234L, USD, 3);

            assertThat(historical.scale()).isEqualTo(3);
            assertThat(historical.toBigDecimal()).isEqualByComparingTo(new BigDecimal("1.234"));
        }

        @Test
        @DisplayName("refuses an absurd scale rather than storing it")
        void rejectsImpossibleScale() {
            assertThatThrownBy(() -> Money.ofPersisted(1L, USD, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Scale");

            assertThatThrownBy(() -> Money.ofPersisted(1L, USD, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Scale");
        }
    }

    @Nested
    @DisplayName("arithmetic across currencies (INV-MON-04)")
    class CurrencyMismatch {

        @Test
        @DisplayName("addition across currencies throws and names both")
        void additionAcrossCurrenciesThrows() {
            Money dollars = Money.ofMinorUnits(100L, USD);
            Money euros = Money.ofMinorUnits(100L, EUR);

            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> dollars.plus(euros))
                    .satisfies(
                            e -> {
                                assertThat(e.left()).isEqualTo(USD);
                                assertThat(e.right()).isEqualTo(EUR);
                            })
                    .withMessageContaining("USD")
                    .withMessageContaining("EUR");
        }

        @Test
        @DisplayName("subtraction across currencies throws")
        void subtractionAcrossCurrenciesThrows() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.ofMinorUnits(100L, USD).minus(Money.ofMinorUnits(1L, JPY)));
        }

        @Test
        @DisplayName("comparison across currencies throws rather than inventing an order")
        void comparisonAcrossCurrenciesThrows() {
            // 100 USD vs 100 JPY has no answer that is not an exchange rate.
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(
                            () -> Money.ofMinorUnits(100L, USD).compareTo(Money.ofMinorUnits(100L, JPY)));
        }

        @Test
        @DisplayName("never coerces: the operands are unchanged after a rejected operation")
        void rejectedOperationLeavesOperandsUntouched() {
            Money dollars = Money.ofMinorUnits(100L, USD);
            Money euros = Money.ofMinorUnits(250L, EUR);

            assertThatThrownBy(() -> dollars.plus(euros)).isInstanceOf(CurrencyMismatchException.class);

            assertThat(dollars.minorUnits()).isEqualTo(100L);
            assertThat(dollars.currency()).isEqualTo(USD);
            assertThat(euros.minorUnits()).isEqualTo(250L);
            assertThat(euros.currency()).isEqualTo(EUR);
        }
    }

    @Nested
    @DisplayName("arithmetic across scales")
    class ScaleMismatch {

        @Test
        @DisplayName("same currency, different scale throws a distinct error")
        void differentScaleThrows() {
            Money current = Money.ofMinorUnits(1234L, USD); // scale 2
            Money historical = Money.ofPersisted(1234L, USD, 3);

            assertThatExceptionOfType(ScaleMismatchException.class)
                    .isThrownBy(() -> current.plus(historical))
                    .satisfies(
                            e -> {
                                assertThat(e.currency()).isEqualTo(USD);
                                assertThat(e.leftScale()).isEqualTo(2);
                                assertThat(e.rightScale()).isEqualTo(3);
                            });
        }

        @Test
        @DisplayName("subtraction and comparison reject a scale mismatch too, not just addition")
        void everyBinaryOperationRejectsScaleMismatch() {
            Money current = Money.ofMinorUnits(1234L, USD);
            Money historical = Money.ofPersisted(1234L, USD, 3);

            assertThatExceptionOfType(ScaleMismatchException.class)
                    .isThrownBy(() -> current.minus(historical));
            assertThatExceptionOfType(ScaleMismatchException.class)
                    .isThrownBy(() -> current.compareTo(historical));
        }

        @Test
        @DisplayName("scale mismatch is not reported as a currency mismatch")
        void scaleMismatchIsItsOwnFailure() {
            // Different causes need different diagnostics: the currencies agree here.
            assertThatThrownBy(
                            () -> Money.ofMinorUnits(1L, USD).plus(Money.ofPersisted(1L, USD, 3)))
                    .isNotInstanceOf(CurrencyMismatchException.class)
                    .isInstanceOf(ScaleMismatchException.class);
        }
    }

    @Nested
    @DisplayName("overflow is rejected, never wrapped (INV-MON-06)")
    class Overflow {

        @Test
        @DisplayName("addition beyond the representable range throws")
        void additionOverflowThrows() {
            Money max = Money.ofMinorUnits(Long.MAX_VALUE, USD);

            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> max.plus(Money.ofMinorUnits(1L, USD)));
        }

        @Test
        @DisplayName("subtraction below the representable range throws")
        void subtractionOverflowThrows() {
            Money min = Money.ofMinorUnits(Long.MIN_VALUE, USD);

            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> min.minus(Money.ofMinorUnits(1L, USD)));
        }

        @Test
        @DisplayName("multiplication overflow throws")
        void multiplicationOverflowThrows() {
            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> Money.ofMinorUnits(Long.MAX_VALUE, USD).times(2L));
        }

        @Test
        @DisplayName("negating the most negative value throws rather than returning itself")
        void negationOverflowThrows() {
            // Math.negateExact(Long.MIN_VALUE) has no positive counterpart; wrapping would
            // return Long.MIN_VALUE again, turning a debit into a debit of the same sign.
            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> Money.ofMinorUnits(Long.MIN_VALUE, USD).negated());
        }

        @Test
        @DisplayName("absolute value of the most negative amount throws rather than staying negative")
        void absoluteValueOverflowThrows() {
            // |Long.MIN_VALUE| is not representable. Returning the input unchanged would leave
            // a negative "absolute value", which is worse than failing.
            assertThatExceptionOfType(MonetaryOverflowException.class)
                    .isThrownBy(() -> Money.ofMinorUnits(Long.MIN_VALUE, USD).absoluteValue());
        }

        @Test
        @DisplayName("a wrapped result is never produced")
        void neverWraps() {
            Money max = Money.ofMinorUnits(Long.MAX_VALUE, USD);

            assertThatThrownBy(() -> max.plus(Money.ofMinorUnits(1L, USD)))
                    .isInstanceOf(MonetaryOverflowException.class);
            // Had it wrapped, the result would have been Long.MIN_VALUE — a large credit
            // silently becoming a large debit.
            assertThat(max.minorUnits()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("exact arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts exactly")
        void addsAndSubtracts() {
            Money a = Money.of(new BigDecimal("10.01"), USD);
            Money b = Money.of(new BigDecimal("0.02"), USD);

            assertThat(a.plus(b)).isEqualTo(Money.of(new BigDecimal("10.03"), USD));
            assertThat(a.minus(b)).isEqualTo(Money.of(new BigDecimal("9.99"), USD));
        }

        @Test
        @DisplayName("accumulating a tenth a thousand times is exact")
        void accumulationIsExact() {
            // The canonical floating-point failure: 0.10 added 1000 times drifts from 100.00
            // in binary floating point. With integer minor units it cannot.
            Money total = Money.zero(USD);
            Money tenCents = Money.of(new BigDecimal("0.10"), USD);
            for (int i = 0; i < 1000; i++) {
                total = total.plus(tenCents);
            }

            assertThat(total).isEqualTo(Money.of(new BigDecimal("100.00"), USD));
            assertThat(total.minorUnits()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("multiplies by a whole quantity")
        void multipliesByQuantity() {
            assertThat(Money.of(new BigDecimal("1.99"), USD).times(3L))
                    .isEqualTo(Money.of(new BigDecimal("5.97"), USD));
        }

        @Test
        @DisplayName("negates and takes absolute value")
        void negatesAndAbsolutes() {
            Money debit = Money.of(new BigDecimal("-25.00"), USD);

            assertThat(debit.negated()).isEqualTo(Money.of(new BigDecimal("25.00"), USD));
            assertThat(debit.absoluteValue()).isEqualTo(Money.of(new BigDecimal("25.00"), USD));
            assertThat(debit.absoluteValue().absoluteValue())
                    .isEqualTo(Money.of(new BigDecimal("25.00"), USD));
        }

        @Test
        @DisplayName("addition is commutative and associative")
        void additionIsCommutativeAndAssociative() {
            Money a = Money.of(new BigDecimal("1.11"), USD);
            Money b = Money.of(new BigDecimal("2.22"), USD);
            Money c = Money.of(new BigDecimal("3.33"), USD);

            assertThat(a.plus(b)).isEqualTo(b.plus(a));
            assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
        }

        @Test
        @DisplayName("orders amounts of the same currency")
        void ordersSameCurrency() {
            Money small = Money.ofMinorUnits(100L, USD);
            Money large = Money.ofMinorUnits(200L, USD);

            assertThat(small).isLessThan(large);
            assertThat(large).isGreaterThan(small);
            assertThat(small).isEqualByComparingTo(Money.ofMinorUnits(100L, USD));
        }

        @Test
        @DisplayName("reports sign")
        void reportsSign() {
            assertThat(Money.ofMinorUnits(1L, USD).isPositive()).isTrue();
            assertThat(Money.ofMinorUnits(-1L, USD).isNegative()).isTrue();
            assertThat(Money.zero(USD).isZero()).isTrue();
            assertThat(Money.zero(USD).isPositive()).isFalse();
            assertThat(Money.zero(USD).isNegative()).isFalse();
        }
    }

    @Nested
    @DisplayName("value semantics and immutability")
    class ValueSemantics {

        @Test
        @DisplayName("has no mutable state and no public mutator")
        void isImmutable() {
            for (Field field : Money.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("field %s must be private", field.getName())
                        .isTrue();
            }

            assertThat(Money.class.getMethods())
                    .as("no method may look like a mutator")
                    .noneMatch(m -> m.getName().startsWith("set"));

            assertThat(Modifier.isFinal(Money.class.getModifiers()))
                    .as("Money must be final so its invariants cannot be subclassed away")
                    .isTrue();
        }

        @Test
        @DisplayName("operations return a new instance and leave the original untouched")
        void operationsDoNotMutate() {
            Money original = Money.of(new BigDecimal("10.00"), USD);

            Money result = original.plus(Money.of(new BigDecimal("5.00"), USD));

            assertThat(result).isNotSameAs(original);
            assertThat(original.minorUnits()).isEqualTo(1000L);
            assertThat(result.minorUnits()).isEqualTo(1500L);
        }

        @Test
        @DisplayName("equality covers amount, currency and scale")
        void equality() {
            assertThat(Money.ofMinorUnits(100L, USD)).isEqualTo(Money.ofMinorUnits(100L, USD));
            assertThat(Money.ofMinorUnits(100L, USD)).hasSameHashCodeAs(Money.ofMinorUnits(100L, USD));

            assertThat(Money.ofMinorUnits(100L, USD)).isNotEqualTo(Money.ofMinorUnits(100L, EUR));
            assertThat(Money.ofMinorUnits(100L, USD)).isNotEqualTo(Money.ofMinorUnits(101L, USD));
            // Same digits, different meaning: 1.00 USD is not 0.100 USD.
            assertThat(Money.ofMinorUnits(100L, USD)).isNotEqualTo(Money.ofPersisted(100L, USD, 3));
            assertThat(Money.ofMinorUnits(100L, USD)).isNotEqualTo(null);
            assertThat(Money.ofMinorUnits(100L, USD)).isNotEqualTo("100 USD");
        }

        @ParameterizedTest(name = "{0} {1} renders as {2}")
        @CsvSource({"1234, USD, 12.34 USD", "100, JPY, 100 JPY", "1234, BHD, 1.234 BHD", "0, USD, 0.00 USD"})
        @DisplayName("renders the exact value, never an approximation")
        void rendersExactly(long minorUnits, String code, String expected) {
            assertThat(Money.ofMinorUnits(minorUnits, CurrencyCode.of(code))).hasToString(expected);
        }

        @ParameterizedTest(name = "{0} {1} converts to {2}")
        @CsvSource({"1234, USD, 12.34", "100, JPY, 100", "1234, BHD, 1.234", "1, CLF, 0.0001"})
        @DisplayName("converts to BigDecimal exactly, at every currency scale")
        void convertsToBigDecimalExactly(long minorUnits, String code, String expected) {
            assertThat(Money.ofMinorUnits(minorUnits, CurrencyCode.of(code)).toBigDecimal())
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("round-trips through BigDecimal without loss")
        void roundTripsThroughBigDecimal() {
            for (String code : new String[] {"USD", "JPY", "BHD", "CLF"}) {
                CurrencyCode currency = CurrencyCode.of(code);
                Money original = Money.ofMinorUnits(123_456L, currency);

                assertThat(Money.of(original.toBigDecimal(), currency)).isEqualTo(original);
            }
        }

        @Test
        @DisplayName("a failure keeps its diagnostic state across serialization")
        void failuresRetainDiagnosticsWhenSerialized() throws Exception {
            // The accessors exist so a caller can react programmatically. Marking the fields
            // transient — the reflex when a field's type is not serializable — would make them
            // return null after a round-trip, silently, which is worse than not having them.
            CurrencyMismatchException original =
                    catchThrowableOfType(
                            CurrencyMismatchException.class,
                            () -> Money.ofMinorUnits(1L, USD).plus(Money.ofMinorUnits(1L, EUR)));

            CurrencyMismatchException restored = roundTrip(original);

            assertThat(restored.left()).isEqualTo(USD);
            assertThat(restored.right()).isEqualTo(EUR);
        }

        @SuppressWarnings("unchecked")
        private static <T> T roundTrip(T value) throws IOException, ClassNotFoundException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (T) in.readObject();
            }
        }

        @Test
        @DisplayName("rejects a null operand rather than treating it as zero")
        void rejectsNullOperand() {
            assertThatThrownBy(() -> Money.ofMinorUnits(1L, USD).plus(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Money.ofMinorUnits(1L, USD).minus(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
