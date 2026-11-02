package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Translation into the reporting currency (`P14-TSK-015`; ADR-0116 decision 3; {@code INV-ACC-11}, {@code INV-MON-03},
 * {@code INV-MON-04}): the backlog entry's five currencies at three scales with explicit differences, against figures worked
 * out by hand outside this codebase; the originals kept; an oracle over generated amounts past {@code long}, in exact rational
 * arithmetic sharing nothing with the code; the ties; the rate's direction; {@code EUR}'s identity; a currency with no rate;
 * never across currencies; identical bytes whatever the JVM's locale and zone; and the judged way to the rates.
 */
@DisplayName("translation into the reporting currency (P14-TSK-015)")
class TranslationTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(TranslationTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("a translation reads only through its port");
            });
    private static final UUID SET = UUID.fromString("00000000-0000-4000-8000-000000000015");
    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000016");
    private static final YearMonth MONTH = YearMonth.of(2096, 2);
    private static final Instant AS_OF = Instant.parse("2096-03-01T00:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2096-02-29T16:00:00Z");
    private static final Map<String, String> RATES = Map.of("GBP", "0.7012", "USD", "1.1561", "JPY", "131.25", "BHD",
            "0.4358123456");

    private final AccountingPeriodId period = AccountingPeriodId.next(IDS);

    /** The set the suites translate at: {@code EUR/GBP}, {@code EUR/USD}, {@code EUR/JPY}, {@code EUR/BHD}. */
    static Translation.Rates rates() {
        TreeMap<String, ValuationRate> pinned = new TreeMap<>();
        RATES.forEach((quote, rate) -> pinned.put(quote, rate(quote, rate)));
        return new Translation.Rates(SET, MONTH, AS_OF, 96, pinned);
    }

    /**
     * The oracle's figure: {@code amount} in {@code currency} at {@link #RATES}, in {@code BigInteger}s - the exact quotient
     * of two integers, in cents, rounded half-even by hand - sharing nothing with {@link Translation} but the question.
     */
    static BigDecimal exactly(BigDecimal amount, String currency) {
        BigInteger numerator = amount.unscaledValue().multiply(BigInteger.valueOf(100));
        BigInteger denominator = BigInteger.TEN.pow(amount.scale());
        if (!currency.equals("EUR")) {
            BigDecimal rate = new BigDecimal(RATES.get(currency));
            numerator = numerator.multiply(BigInteger.TEN.pow(rate.scale()));
            denominator = denominator.multiply(rate.unscaledValue());
        }
        BigInteger[] divided = numerator.abs().divideAndRemainder(denominator);
        int half = divided[1].shiftLeft(1).compareTo(denominator);
        BigInteger cents = half > 0 || (half == 0 && divided[0].testBit(0)) ? divided[0].add(BigInteger.ONE) : divided[0];
        return new BigDecimal(numerator.signum() < 0 ? cents.negate() : cents, 2);
    }

    @Test
    @DisplayName("the entry's acceptance: five currencies at three scales (EUR at a fourth as well), each part HALF_EVEN at two"
            + " places, each currency's explicit difference between its parts and its total - worked out by hand, none of it"
            + " pushed into the largest part")
    void everyCurrencyAtItsScaleWithAnExplicitDifference() {
        Translation.Translated translated = rates().translate(List.of(
                part("EUR", "100.00"), part("EUR", "0.0050"), part("EUR", "0.0050"), part("EUR", "0.0050"),
                part("USD", "10.01"), part("USD", "20.03"), part("USD", "0.04"), part("USD", "0.04"), part("USD", "0.04"),
                part("GBP", "1.01"), part("GBP", "0.02"), part("GBP", "0.02"), part("GBP", "0.02"), part("GBP", "0.02"),
                part("GBP", "0.02"),
                part("JPY", "1234"), part("JPY", "-999"), part("JPY", "7"), part("JPY", "7"), part("JPY", "7"),
                part("BHD", "1.001"), part("BHD", "-12.345"), part("BHD", "0.002"), part("BHD", "0.002"),
                part("BHD", "0.002")));

        assertThat(translated.lines()).extracting(line -> line.translated().orElseThrow().toPlainString()).containsExactly(
                "100.00", "0.00", "0.00", "0.00",
                "8.66", "17.33", "0.03", "0.03", "0.03",
                "1.44", "0.03", "0.03", "0.03", "0.03", "0.03",
                "9.40", "-7.61", "0.05", "0.05", "0.05",
                "2.30", "-28.33", "0.00", "0.00", "0.00");

        assertThat(translated.groups()).extracting(Translation.Group::currency)
                .containsExactly("BHD", "EUR", "GBP", "JPY", "USD");
        group(translated, "EUR", Translation.Basis.IDENTITY, "100.0150", "100.00", "0.02", "100.02");
        group(translated, "USD", Translation.Basis.PINNED_RATE, "30.16", "26.08", "0.01", "26.09");
        group(translated, "GBP", Translation.Basis.PINNED_RATE, "1.11", "1.59", "-0.01", "1.58");
        group(translated, "JPY", Translation.Basis.PINNED_RATE, "256", "1.94", "0.01", "1.95");
        group(translated, "BHD", Translation.Basis.PINNED_RATE, "-11.338", "-26.03", "0.01", "-26.02");

        Translation.Figures total = translated.total().orElseThrow();
        assertThat(total.parts().toPlainString()).isEqualTo("103.58");
        assertThat(total.difference().toPlainString()).isEqualTo("0.04");
        assertThat(total.total().toPlainString()).isEqualTo("103.62");
        assertThat(translated.complete()).isTrue();
        assertThat(translated.untranslated()).isEmpty();
    }

    @Test
    @DisplayName("the originals are kept: every line holds its part exactly - value, scale, currency - in the order given, each"
            + " group its exact original total at the data's own scale, the input untouched")
    void originalsAreKept() {
        List<Translation.Part> parts = new ArrayList<>(List.of(part("BHD", "1.001"), part("EUR", "0.0050"), part("JPY", "7"),
                part("USD", "0.04"), part("BHD", "-12.345")));
        List<Translation.Part> given = List.copyOf(parts);
        Translation.Translated translated = rates().translate(parts);
        assertThat(parts).isEqualTo(given);
        assertThat(translated.lines()).extracting(Translation.Line::original).isEqualTo(given);
        // BigDecimal.equals: the value and its scale.
        assertThat(translated.lines().get(0).original().amount()).isEqualTo(new BigDecimal("1.001"));
        assertThat(translated.lines().get(1).original().amount()).isEqualTo(new BigDecimal("0.0050"));
        assertThat(translated.lines().get(2).original().amount()).isEqualTo(new BigDecimal("7"));
        assertThat(translated.groups().get(0).original()).isEqualTo(new BigDecimal("-11.344"));

        String section = CanonicalJson.text(Translation.section(rates(), new TreeMap<>(Map.of("closing", translated)),
                Map.of("BHD", 3, "EUR", 4, "JPY", 0, "USD", 2)));
        assertThat(section)
                .contains("\"original\":{\"currency\":\"BHD\",\"minor\":\"-11344\",\"scale\":\"3\"}")
                .contains("\"original\":{\"currency\":\"EUR\",\"minor\":\"50\",\"scale\":\"4\"}")
                .contains("\"original\":{\"currency\":\"JPY\",\"minor\":\"7\",\"scale\":\"0\"}")
                .contains("\"original\":{\"currency\":\"USD\",\"minor\":\"4\",\"scale\":\"2\"}");
    }

    @Test
    @DisplayName("a property over generated amounts, past long at three scales: every part and total is the exact oracle's;"
            + " parts + difference = total; the difference bounded by half a cent per figure; the order of the parts never"
            + " shows; equal original totals translate equal however they are split")
    void generatedAmountsAgreeWithAnExactOracle() {
        Translation.Rates rates = rates();
        List<String> currencies = List.of("EUR", "USD", "GBP", "JPY", "BHD");
        Map<String, Integer> scales = Map.of("EUR", 2, "USD", 2, "GBP", 2, "JPY", 0, "BHD", 3);
        for (int seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            List<Translation.Part> parts = new ArrayList<>();
            int count = 1 + random.nextInt(60);
            for (int i = 0; i < count; i++) {
                String currency = currencies.get(random.nextInt(currencies.size()));
                // Every size: a few minor units, around a tie's reach, and far past long.
                int bits = 1 + random.nextInt(i % 7 == 0 ? 90 : 24);
                BigInteger minor = new BigInteger(bits, random);
                parts.add(new Translation.Part(currency,
                        new BigDecimal(random.nextBoolean() ? minor : minor.negate(), scales.get(currency))));
            }
            Translation.Translated translated = rates.translate(parts);

            for (Translation.Line line : translated.lines()) {
                assertThat(line.translated()).as("seed %d: %s", seed, line.original())
                        .contains(exactly(line.original().amount(), line.original().currency()));
                assertThat(line.translated().orElseThrow().scale()).isEqualTo(Translation.REPORTING_SCALE);
            }
            BigDecimal sumOfTotals = new BigDecimal("0.00");
            for (Translation.Group group : translated.groups()) {
                List<Translation.Part> own = parts.stream().filter(part -> part.currency().equals(group.currency())).toList();
                BigDecimal original = own.stream().map(Translation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal sum = own.stream().map(part -> exactly(part.amount(), part.currency()))
                        .reduce(new BigDecimal("0.00"), BigDecimal::add);
                Translation.Figures figures = group.figures().orElseThrow();
                assertThat(group.original()).as("seed %d", seed).isEqualByComparingTo(original);
                assertThat(figures.total()).as("seed %d", seed).isEqualTo(exactly(original, group.currency()));
                assertThat(figures.parts()).as("seed %d", seed).isEqualTo(sum);
                assertThat(figures.parts().add(figures.difference())).as("seed %d", seed).isEqualTo(figures.total());
                // Each of n parts and the total is within half a cent of its exact value.
                assertThat(figures.difference().abs().multiply(new BigDecimal(200)))
                        .as("seed %d: the difference's bound", seed)
                        .isLessThanOrEqualTo(new BigDecimal(own.size() + 1));
                sumOfTotals = sumOfTotals.add(figures.total());
            }
            assertThat(translated.total().orElseThrow().total()).as("seed %d", seed).isEqualTo(sumOfTotals);

            List<Translation.Part> shuffled = new ArrayList<>(parts);
            Collections.shuffle(shuffled, new Random(seed * 31L));
            assertThat(rates.translate(shuffled).groups()).as("seed %d: the order never shows", seed)
                    .isEqualTo(translated.groups());

            // The same total split otherwise: the translated total is the original total's alone.
            for (Translation.Group group : translated.groups()) {
                BigDecimal original = group.original();
                BigDecimal unit = BigDecimal.ONE.movePointLeft(original.scale());
                Translation.Translated split = rates.translate(List.of(
                        new Translation.Part(group.currency(), original.subtract(unit)),
                        new Translation.Part(group.currency(), unit)));
                assertThat(split.groups().get(0).figures().orElseThrow().total()).as("seed %d", seed)
                        .isEqualTo(group.figures().orElseThrow().total());
            }
        }
    }

    @Test
    @DisplayName("HALF_EVEN, named (INV-MON-03): a tie goes to the even cent, either sign, in EUR and at a rate; the mode is"
            + " written in the report")
    void aTieGoesToTheEvenCent() {
        assertThat(figure("EUR", "0.125")).isEqualTo("0.12");
        assertThat(figure("EUR", "0.135")).isEqualTo("0.14");
        assertThat(figure("EUR", "-0.125")).isEqualTo("-0.12");
        assertThat(figure("EUR", "-0.135")).isEqualTo("-0.14");
        assertThat(figure("EUR", "0.005")).isEqualTo("0.00");
        assertThat(figure("EUR", "0.015")).isEqualTo("0.02");
        Translation.Rates two = single("USD", "2");
        assertThat(two.translate(List.of(part("USD", "0.25"))).lines().get(0).translated()).contains(new BigDecimal("0.12"));
        assertThat(two.translate(List.of(part("USD", "0.75"))).lines().get(0).translated()).contains(new BigDecimal("0.38"));
        assertThat(two.translate(List.of(part("USD", "-0.25"))).lines().get(0).translated())
                .contains(new BigDecimal("-0.12"));
        assertThat(Translation.ROUNDING).isEqualTo(java.math.RoundingMode.HALF_EVEN);
        assertThat(CanonicalJson.text(Translation.section(rates(), new TreeMap<>(), Map.of())))
                .contains("\"rounding\":\"HALF_EVEN\"").contains("\"scale\":\"2\"");
    }

    @Test
    @DisplayName("the rate's direction: units of the currency per one euro, so a part is divided by it, never multiplied -"
            + " 13125 JPY at EUR/JPY 131.25 is 100.00 EUR")
    void aPartIsDividedByTheRate() {
        assertThat(figure("JPY", "13125")).isEqualTo("100.00");
        assertThat(figure("GBP", "70.12")).isEqualTo("100.00");
        assertThat(figure("USD", "115.61")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("EUR is translated at identity, explicitly: no rate from the set, basis IDENTITY and rate 1 written in the"
            + " report, a finer scale rounded by name - even with a set that holds nothing for it")
    void eurIsIdentityExplicitly() {
        Translation.Rates rates = single("USD", "2");
        assertThat(rates.basis("EUR")).isEqualTo(Translation.Basis.IDENTITY);
        Translation.Translated translated = rates.translate(List.of(part("EUR", "12.345"), part("EUR", "10.00")));
        assertThat(translated.lines()).extracting(line -> line.translated().orElseThrow().toPlainString())
                .containsExactly("12.34", "10.00");
        group(translated, "EUR", Translation.Basis.IDENTITY, "22.345", "22.34", "0.00", "22.34");
        String section = CanonicalJson.text(Translation.section(rates, new TreeMap<>(Map.of("closing", translated)),
                Map.of("EUR", 3)));
        assertThat(section)
                .contains("{\"basis\":\"IDENTITY\",\"currency\":\"EUR\",\"rate\":\"1\"}")
                .contains("\"currency\":\"EUR\",\"rate\":\"1\"}");
    }

    @Test
    @DisplayName("a currency with no rate in the set is neither dropped nor translated at one: its line keeps its original and"
            + " translates to nothing, its group is named, the report-wide total withheld - a code no JDK knows included, no"
            + " table is consulted")
    void aCurrencyWithNoRateIsDisclosedNeverDroppedNeverAtOne() {
        Translation.Translated translated = rates().translate(List.of(part("CHF", "100.00"), part("EUR", "50.00"),
                part("ZZZ", "7.77")));
        assertThat(translated.lines().get(0).original().amount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(translated.lines().get(0).translated()).isEmpty();
        assertThat(translated.lines().get(2).translated()).isEmpty();
        assertThat(translated.lines().get(1).translated()).contains(new BigDecimal("50.00"));
        Translation.Group chf = translated.groups().get(0);
        assertThat(chf.currency()).isEqualTo("CHF");
        assertThat(chf.basis()).isEqualTo(Translation.Basis.NO_RATE);
        assertThat(chf.original()).isEqualTo(new BigDecimal("100.00"));
        assertThat(chf.figures()).isEmpty();
        assertThat(translated.complete()).isFalse();
        assertThat(translated.untranslated()).containsExactly("CHF", "ZZZ");
        assertThat(translated.total()).as("a partial total is not a total").isEmpty();

        String section = CanonicalJson.text(Translation.section(rates(), new TreeMap<>(Map.of("closing", translated)),
                Map.of("CHF", 2, "EUR", 2, "ZZZ", 2)));
        assertThat(section)
                .contains("\"complete\":false").contains("\"untranslated\":[\"CHF\",\"ZZZ\"]")
                .contains("\"total\":{\"closing\":null}")
                .contains("{\"basis\":\"NO_RATE\",\"columns\":{\"closing\":{\"original\":{\"currency\":\"CHF\","
                        + "\"minor\":\"10000\",\"scale\":\"2\"},\"translatedParts\":null,\"translatedTotal\":null,"
                        + "\"translationDifference\":null}},\"currency\":\"CHF\",\"rate\":null}");
    }

    @Test
    @DisplayName("a zero needs no rate: a currency with no rate whose every figure is zero translates to zero, stays out of"
            + " untranslated, keeps complete true and withholds nothing - by a basis of its own, with no rate and never one of"
            + " one")
    void aCurrencyWithNoRateAllAtZeroIsNotAHole() {
        Translation.Translated translated = rates().translate(List.of(part("CHF", "0.00"), part("CHF", "0"),
                part("EUR", "50.00")));
        assertThat(translated.lines()).extracting(line -> line.translated().orElseThrow().toPlainString())
                .containsExactly("0.00", "0.00", "50.00");
        group(translated, "CHF", Translation.Basis.ZERO_NO_RATE, "0.00", "0.00", "0.00", "0.00");
        assertThat(translated.complete()).isTrue();
        assertThat(translated.untranslated()).isEmpty();
        assertThat(translated.total().orElseThrow().total()).isEqualTo(new BigDecimal("50.00"));

        String section = CanonicalJson.text(Translation.section(rates(), new TreeMap<>(Map.of("closing", translated)),
                Map.of("CHF", 2, "EUR", 2)));
        assertThat(section)
                .contains("\"complete\":true").contains("\"untranslated\":[]")
                .contains("{\"basis\":\"ZERO_NO_RATE\",\"columns\":{\"closing\":{\"original\":{\"currency\":\"CHF\","
                        + "\"minor\":\"0\",\"scale\":\"2\"},\"translatedParts\":{\"currency\":\"EUR\",\"minor\":\"0\","
                        + "\"scale\":\"2\"},\"translatedTotal\":{\"currency\":\"EUR\",\"minor\":\"0\",\"scale\":\"2\"},"
                        + "\"translationDifference\":{\"currency\":\"EUR\",\"minor\":\"0\",\"scale\":\"2\"}}},"
                        + "\"currency\":\"CHF\",\"rate\":null}")
                .as("no rate listed for it").doesNotContain("EUR/CHF");
    }

    @Test
    @DisplayName("any figure that is not zero, in any column, leaves a currency with no rate a hole in every column - zero"
            + " figures included, every total withheld")
    void anyFigureNotZeroInAnyColumnKeepsTheHole() {
        TreeMap<String, List<Translation.Part>> columns = new TreeMap<>();
        columns.put("closing", List.of(part("CHF", "0.00"), part("EUR", "1.00")));
        columns.put("debits", List.of(part("CHF", "5.00"), part("EUR", "1.00")));
        columns.put("opening", List.of(part("CHF", "0.00"), part("EUR", "1.00")));
        SortedMap<String, Translation.Translated> translated = rates().translate(columns, Set.of());
        assertThat(translated).containsOnlyKeys("closing", "debits", "opening");
        translated.forEach((name, column) -> {
            assertThat(column.lines().get(0).translated()).as(name).isEmpty();
            assertThat(column.lines().get(1).translated()).as(name).contains(new BigDecimal("1.00"));
            assertThat(column.groups().get(0).basis()).as(name).isEqualTo(Translation.Basis.NO_RATE);
            assertThat(column.complete()).as(name).isFalse();
            assertThat(column.untranslated()).as(name).containsExactly("CHF");
            assertThat(column.total()).as(name).isEmpty();
        });
    }

    @Test
    @DisplayName("a currency the report holds and no part carries is a hole, in every column and whatever the set values:"
            + " named untranslated, complete false, the totals withheld, its original no invented zero")
    void aCurrencyTheReportHoldsAndNoPartCarriesIsAHole() {
        TreeMap<String, List<Translation.Part>> columns = new TreeMap<>();
        columns.put("closing", List.of(part("EUR", "1.00")));
        columns.put("debits", List.of(part("EUR", "2.00")));
        SortedMap<String, Translation.Translated> translated = rates().translate(columns, Set.of("EUR", "USD"));
        translated.forEach((name, column) -> {
            assertThat(column.lines()).as(name).hasSize(1);
            assertThat(column.groups()).extracting(Translation.Group::currency).containsExactly("EUR", "USD");
            Translation.Group usd = column.groups().get(1);
            assertThat(usd.basis()).as(name).isEqualTo(Translation.Basis.NO_PARTS);
            assertThat(usd.figures()).as(name).isEmpty();
            assertThat(column.complete()).as(name).isFalse();
            assertThat(column.untranslated()).as(name).containsExactly("USD");
            assertThat(column.total()).as(name).isEmpty();
        });
        assertThat(CanonicalJson.text(Translation.section(rates(), translated, Map.of("EUR", 2))))
                .contains("\"complete\":false").contains("\"untranslated\":[\"USD\"]")
                .contains("\"total\":{\"closing\":null,\"debits\":null}")
                .contains("{\"basis\":\"NO_PARTS\",\"columns\":{\"closing\":{\"original\":null,\"translatedParts\":null,"
                        + "\"translatedTotal\":null,\"translationDifference\":null},\"debits\":{\"original\":null,"
                        + "\"translatedParts\":null,\"translatedTotal\":null,\"translationDifference\":null}},"
                        + "\"currency\":\"USD\",\"rate\":null}");
        assertThat(rates().translate(columns, Set.of("EUR")).get("closing").complete())
                .as("a required currency a part carries is no hole").isTrue();
    }

    @Test
    @DisplayName("never across currencies (INV-MON-04): a group holds one currency's parts, 1.1561 USD and 1.00 EUR are two"
            + " groups though they translate alike; only EUR figures are summed; a part names a currency's shape")
    void arithmeticNeverCrossesCurrencies() {
        Translation.Translated translated = rates().translate(List.of(part("USD", "1.1561"), part("EUR", "1.00")));
        assertThat(translated.groups()).hasSize(2);
        assertThat(translated.groups().get(0).original()).isEqualTo(new BigDecimal("1.00"));
        assertThat(translated.groups().get(1).original()).isEqualTo(new BigDecimal("1.1561"));
        assertThat(translated.total().orElseThrow().total()).isEqualTo(new BigDecimal("2.00"));
        String section = CanonicalJson.text(Translation.section(rates(), new TreeMap<>(Map.of("closing", translated)),
                Map.of("EUR", 2, "USD", 4)));
        assertThat(section.substring(section.indexOf("\"total\":"))).doesNotContain("original");
        assertThatThrownBy(() -> part("usd", "1.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Translation.Figures(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .as("parts + difference = total is not a convention").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pure, and identical bytes on every JVM: in a default locale with its own digits and another zone, the"
            + " section's hash is the one pinned here; the rate's text is NUMERIC(20,10)'s however the value arrived")
    void identicalBytesEverywhere() {
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            for (Locale other : List.of(Locale.ROOT, Locale.forLanguageTag("th-TH-u-nu-thai"),
                    Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.forLanguageTag("de-DE"))) {
                Locale.setDefault(other);
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
                assertThat(CanonicalJson.sha256(CanonicalJson.text(pinnedSection()))).as(other.toLanguageTag())
                        .isEqualTo(PINNED_SECTION);
            }
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
        assertThat(CanonicalJson.text(pinnedSection())).contains("\"rate\":\"131.2500000000\"")
                .contains("\"rate\":\"0.4358123456\"");
    }

    @Test
    @DisplayName("the judged way to the rates: the set the run names, APPROVED, of the run's own period - anything else is not"
            + " issued; a plain run has none to read")
    void pinnedByJudgesTheSet() {
        ValuationRateReads approved = (snapshot, id) -> Optional.of(set(id, period, ValuationRateSetStatus.APPROVED));
        Translation.Rates rates = Translation.Rates.pinnedBy(approved, SNAPSHOT, inputs(Optional.of(SET)));
        assertThat(rates.setId()).isEqualTo(SET);
        assertThat(rates.pinned()).containsOnlyKeys("BHD", "GBP", "JPY", "USD");
        for (ValuationRateSetStatus status : List.of(ValuationRateSetStatus.PROPOSED, ValuationRateSetStatus.REJECTED)) {
            assertThatThrownBy(() -> Translation.Rates.pinnedBy((snapshot, id) -> Optional.of(set(id, period, status)),
                    SNAPSHOT, inputs(Optional.of(SET)))).as(status.name()).isInstanceOf(Translation.NotPinned.class);
        }
        assertThatThrownBy(() -> Translation.Rates.pinnedBy((snapshot, id) -> Optional.of(set(id,
                AccountingPeriodId.next(IDS), ValuationRateSetStatus.APPROVED)), SNAPSHOT, inputs(Optional.of(SET))))
                .as("another period's").isInstanceOf(Translation.NotPinned.class);
        assertThatThrownBy(() -> Translation.Rates.pinnedBy((snapshot, id) -> Optional.empty(), SNAPSHOT,
                inputs(Optional.of(SET)))).as("not in the snapshot").isInstanceOf(Translation.NotPinned.class);
        assertThatThrownBy(() -> Translation.Rates.pinnedBy(approved, SNAPSHOT, inputs(Optional.empty())))
                .as("a plain run").isInstanceOf(Translation.NotPinned.class);
    }

    // ------------------------------------------------------------------

    /** The hash of {@link #pinnedSection}, computed once: any JVM running this suite must write these bytes. */
    private static final String PINNED_SECTION = "e12c628ac03a64cd462fa305cb51467fae46442a793d68a5a2af2944673a4663";

    private static CanonicalJson.Obj pinnedSection() {
        Translation.Translated closing = rates().translate(List.of(part("BHD", "1.001"), part("EUR", "0.0050"),
                part("GBP", "-250.55"), part("JPY", "1234"), part("USD", "20.03"), part("USD", "0.04")));
        Translation.Translated debits = rates().translate(List.of(part("BHD", "0.002"), part("EUR", "99999999999.9999"),
                part("JPY", "123456789012345678901234"), part("USD", "0.03")));
        return Translation.section(rates(), new TreeMap<>(Map.of("closing", closing, "debits", debits)),
                Map.of("BHD", 3, "EUR", 4, "GBP", 2, "JPY", 0, "USD", 2));
    }

    private static Translation.Part part(String currency, String amount) {
        return new Translation.Part(currency, new BigDecimal(amount));
    }

    private static String figure(String currency, String amount) {
        return rates().translate(List.of(part(currency, amount))).lines().get(0).translated().orElseThrow().toPlainString();
    }

    private static void group(Translation.Translated translated, String currency, Translation.Basis basis, String original,
            String parts, String difference, String total) {
        Translation.Group group = translated.groups().stream().filter(candidate -> candidate.currency().equals(currency))
                .findFirst().orElseThrow();
        assertThat(group.basis()).as(currency).isEqualTo(basis);
        assertThat(group.original().toPlainString()).as(currency).isEqualTo(original);
        Translation.Figures figures = group.figures().orElseThrow();
        assertThat(figures.parts().toPlainString()).as(currency).isEqualTo(parts);
        assertThat(figures.difference().toPlainString()).as(currency + "'s difference line").isEqualTo(difference);
        assertThat(figures.total().toPlainString()).as(currency).isEqualTo(total);
    }

    private static Translation.Rates single(String quote, String value) {
        TreeMap<String, ValuationRate> pinned = new TreeMap<>();
        pinned.put(quote, rate(quote, value));
        return new Translation.Rates(SET, MONTH, AS_OF, 96, pinned);
    }

    private static ValuationRate rate(String quote, String value) {
        return new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of(quote), new BigDecimal(value), "reference.test",
                SNAPSHOT_ID, OBSERVED, OBSERVED.plusSeconds(1));
    }

    private static ValuationRateReads.PinnedSet set(UUID id, AccountingPeriodId period, ValuationRateSetStatus status) {
        return new ValuationRateReads.PinnedSet(id, period, MONTH, status, AS_OF, 96,
                RATES.entrySet().stream().map(entry -> rate(entry.getKey(), entry.getValue())).toList());
    }

    private ReportInputs inputs(Optional<UUID> set) {
        return new ReportInputs(period, MONTH, 1, RunBasis.FINAL, Optional.of(Instant.EPOCH), Optional.empty(),
                Optional.empty(), Instant.EPOCH, RunParameters.of(Map.of(), set));
    }
}
