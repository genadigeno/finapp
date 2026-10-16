package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.lending.DelinquencyBucket;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.PayoutPreference;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every lending series PHASE_11_PLAN.md section 15 names is registered at startup with its closed tags - and no lending
 * series can carry a figure (`P11-TSK-030`; ADR-0072, PHASE_11_PLAN.md section 11, {@code INV-AUD-02}).
 *
 * <p>The context boots with nothing configured and no reachable database: exactly the "freshly started instance" the
 * observability criterion names - armed ahead of the phase flip, after which {@code PlannedMetersExistTest} reads the same
 * table. The section's rows are counted EXACTLY (35 - the Phase 11 completion gate added two, A5-03), so a row added without a meter, or a meter renamed, fails here.
 *
 * <p>The needle: every tag of every {@code finapp.lending.}, {@code finapp.credit.exposure.census} and
 * {@code finapp.credit.consumption} series has a key from lending's closed set and a value from the closed set that key
 * admits - a product, a state, a bucket, a payout path or a verdict, each an enum constant of the code, or a lowercase outcome
 * or kind word, the only free-form values, letters and underscores alone. No amount, rate, balance, limit, DPD, party, loan
 * or any identifier can be carried by any of them, under any key (the probe: tag a counter with an amount and this is red).
 */
@org.junit.jupiter.api.Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@DisplayName("every lending series is registered at startup with closed tags, and none can carry a figure (P11-TSK-030)")
class LendingMetersTest {

    private static final Pattern PLANNED_METER = Pattern.compile("^\\|\\s*`(finapp\\.[a-z][a-z0-9.]*)`");

    /** A lowercase word - an outcome or a kind: letters and underscores only, never a digit, a hyphen or a dot. */
    private static final Pattern WORD = Pattern.compile("[a-z][a-z_]{0,39}");

    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("section 15's 35 rows are each a registered series on a fresh instance, with the tag keys the plan gives it")
    void everySectionFifteenSeriesIsRegisteredWithItsTags() throws IOException {
        List<String> planned = plannedSeries();
        assertThat(planned).as("PHASE_11_PLAN.md section 15 has exactly 35 series rows").hasSize(35)
                .doesNotHaveDuplicates();
        Map<String, Set<String>> registered = new TreeMap<>();
        registry.getMeters().forEach(meter -> registered.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                .addAll(meter.getId().getTags().stream().map(Tag::getKey).toList()));
        assertThat(registered.keySet()).containsAll(planned);

        Map<String, Set<String>> keys = new TreeMap<>();
        keys.put("finapp.lending.application", Set.of("product", "outcome"));
        keys.put("finapp.lending.offer", Set.of("product", "outcome"));
        keys.put("finapp.lending.origination", Set.of("kind", "outcome"));
        keys.put("finapp.lending.acceptance", Set.of("product", "outcome"));
        keys.put("finapp.lending.disbursement", Set.of("payout", "outcome"));
        keys.put("finapp.lending.billing", Set.of("kind", "outcome"));
        keys.put("finapp.lending.repayment", Set.of("kind", "outcome"));
        keys.put("finapp.lending.fee", Set.of("kind", "outcome"));
        keys.put("finapp.lending.waiver", Set.of("kind", "outcome"));
        keys.put("finapp.lending.delinquency", Set.of("product", "bucket"));
        keys.put("finapp.lending.default", Set.of("product"));
        keys.put("finapp.lending.loans", Set.of("product", "status"));
        keys.put("finapp.lending.terms.active", Set.of("product"));
        for (String name : List.of("finapp.lending.payout", "finapp.lending.draw", "finapp.lending.accrual",
                "finapp.lending.collection", "finapp.lending.reversal", "finapp.lending.payoff", "finapp.lending.amendment",
                "finapp.credit.consumption")) {
            keys.put(name, Set.of("outcome"));
        }
        for (String name : List.of("finapp.lending.subledger.proof", "finapp.lending.replay", "finapp.lending.capital.proof",
                "finapp.credit.exposure.census")) {
            keys.put(name, Set.of("verdict"));
        }
        for (String name : planned) {
            assertThat(registered.get(name)).as("%s's tag keys", name).isEqualTo(keys.getOrDefault(name, Set.of()));
        }
        // Each gauge-by-category is registered for every value of its category, from startup.
        for (LoanProduct product : LoanProduct.values()) {
            assertThat(registry.find("finapp.lending.terms.active").tag("product", product.name()).gauges()).isNotEmpty();
            String lower = product.name().toLowerCase(Locale.ROOT);
            assertThat(registry.find("finapp.lending.default").tag("product", lower).gauges()).isNotEmpty();
            for (DelinquencyBucket bucket : DelinquencyBucket.values()) {
                assertThat(registry.find("finapp.lending.delinquency").tag("product", lower)
                        .tag("bucket", bucket.name().toLowerCase(Locale.ROOT)).gauges()).as(bucket.name()).isNotEmpty();
            }
        }
        for (PayoutPreference path : PayoutPreference.values()) {
            assertThat(registry.find("finapp.lending.disbursement").tag("payout", path.name().toLowerCase(Locale.ROOT))
                    .counters()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("every lending tag is a closed key with a closed value - an enum constant, or a word of letters alone - so"
            + " no amount, rate, balance, limit, DPD, party or loan id can be carried")
    void noLendingSeriesCanCarryAFigure() {
        List<Meter> lending = registry.getMeters().stream().filter(meter -> {
            String name = meter.getId().getName();
            return name.startsWith("finapp.lending.") || name.equals("finapp.credit.exposure.census")
                    || name.equals("finapp.credit.consumption");
        }).toList();
        assertThat(lending).as("the lending meters are published and read").hasSizeGreaterThan(60);
        Map<String, Pattern> closed = closedValues();
        for (Meter meter : lending) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(closed).as("%s carries tag %s, not one of lending's closed keys", meter.getId().getName(),
                        tag.getKey()).containsKey(tag.getKey());
                assertThat(tag.getValue()).as("%s's %s", meter.getId().getName(), tag.getKey())
                        .matches(closed.get(tag.getKey()));
            }
        }
    }

    /** Each key lending's meters carry, and the closed set of values it admits. */
    private static Map<String, Pattern> closedValues() {
        return Map.of(
                "product", oneOf(Arrays.stream(LoanProduct.values()).map(Enum::name)
                        .flatMap(name -> Stream.of(name, name.toLowerCase(Locale.ROOT))).collect(Collectors.toSet())),
                "status", oneOf(Arrays.stream(LoanStatus.values()).map(value -> value.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet())),
                "bucket", oneOf(Arrays.stream(DelinquencyBucket.values()).map(value -> value.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet())),
                "payout", oneOf(Arrays.stream(PayoutPreference.values()).map(value -> value.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet())),
                "verdict", oneOf(Set.of("HOLDS", "FAILS", "IDENTICAL", "DIVERGED")),
                "outcome", WORD,
                "kind", WORD);
    }

    private static Pattern oneOf(Set<String> values) {
        return Pattern.compile(values.stream().map(Pattern::quote).collect(Collectors.joining("|")));
    }

    private static List<String> plannedSeries() throws IOException {
        List<String> planned = new java.util.ArrayList<>();
        boolean inObservability = false;
        for (String line : Files.readAllLines(repositoryFile("docs/project/PHASE_11_PLAN.md"), StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inObservability = line.startsWith("## 15.");
                continue;
            }
            Matcher row = PLANNED_METER.matcher(line);
            if (inObservability && row.find()) {
                planned.add(row.group(1));
            }
        }
        return planned;
    }

    private static Path repositoryFile(String relative) {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve(relative))) {
            here = here.getParent();
        }
        assertThat(here).as("could not locate %s", relative).isNotNull();
        return here.resolve(relative);
    }
}
