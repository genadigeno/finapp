package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.app.accounting.AccountingBreakAgeMetrics;
import com.finapp.app.accounting.AccountingBreakMetrics;
import com.finapp.app.accounting.AccountingMappingMetrics;
import com.finapp.app.accounting.AccountingPeriodMetrics;
import com.finapp.app.accounting.CloseCheckStallMetrics;
import com.finapp.app.accounting.CloseRunMetrics;
import com.finapp.app.accounting.CloseRunStallMetrics;
import com.finapp.app.accounting.ClosedMonthRunMetrics;
import com.finapp.app.accounting.PriorPeriodAdjustmentMetrics;
import com.finapp.app.accounting.RegulatoryAttentionMetrics;
import com.finapp.app.accounting.RegulatorySubmissionMetrics;
import com.finapp.app.accounting.ReportRunMetrics;
import com.finapp.app.bnpl.BnplWriteOffMetrics;
import com.finapp.app.lending.LoanWriteOffMetrics;
import com.finapp.platform.testing.RepositoryPaths;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;

/**
 * Every accounting alert can fire, on its break and only then (`P14-TSK-020`; {@code PHASE_14_PLAN.md} section 16; {@code RiskAlertRulesTest}'s shape).
 *
 * <p><strong>What this proves, and what it does not.</strong> The scrape is real: a freshly started instance's real registry, the real metric classes, the real names, units and tags, nothing translated here. The evaluation is
 * {@link RuleEvaluator}, a small evaluator written in this repository, and {@link RuleTimeline} for a rule's {@code for}: neither is Prometheus. So a rule that passes here has an expression that holds on its break and
 * nowhere else as this evaluator reads PromQL, over series that exist. Prometheus's own reading is {@code infra/prometheus/tests/accounting.test.yml} (since 2026-11-12: every rule fires there and is silent on its
 * healthy series, run by {@code promtool} in CI's {@code alerts} job and held by {@code AlertCatalogueTest}); until then this file's was the only evaluation, which is why the grammar is kept closed and small.
 *
 * <p><strong>And what the scrape is.</strong> This calls the registry's {@code scrape()}, after asking the gauge refresher to read every accounting gauge and waiting for it: every gauge reads on that thread, never on the scrape (`P15-TSK-024`), so with the database gone or the pool exhausted a production scrape completes too and these samples reach the rules. <em>(Until `P15-TSK-024` the other modules' classes read on the scrape thread, and such a scrape outlived Prometheus's timeout and stored nothing.)</em>
 *
 * <p>So this is red when a series is renamed without its rule (each selector, labels and all, is found in the scrape), when a counter a rule reads is not there at zero on a fresh instance, when a gauge fails to anything but NaN, when a threshold drifts from
 * {@link AccountingObjectives}, when one break wakes a second alert, and when a rule is added with no case here. The live resolution with a database is {@code AlertRulesResolveTest#theAccountingRulesResolve} (owed, C5). The runbook's half is {@link AccountingRunbookTest}.
 */
@Tag("slice")
// Pointed at a database that is not there (PlannedMetersExistTest's idiom): every series is the beans' own, from startup, and every read of the database fails - the unreadable path, met for real.
@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
        "finapp.accounting.sweeper.enabled=false"})
@DisplayName("every accounting alert fires on its break and only then (P14-TSK-020)")
class AccountingAlertRulesTest {

    static final String RULES = "infra/prometheus/rules/accounting.yml";

    private static final String RUN_STALLED = "finapp_accounting_close_run_stalled";
    private static final String CHECK_STALLED = "finapp_accounting_close_check_stalled";
    private static final String OPEN_AGE = "finapp_accounting_period_open_age_days";
    private static final String SWEEPER = "finapp_accounting_sweeper_enabled";
    private static final String UNMAPPED = "finapp_accounting_gl_unmapped";
    private static final String BREAK_OPEN = "finapp_accounting_break_open";
    private static final String BREAK_AGED = "finapp_accounting_break_aged";
    private static final String STANDING = "finapp_accounting_adjustment_standing";
    private static final String UNRUN = "finapp_accounting_close_month_unrun";
    private static final String ATTENTION = "finapp_accounting_regulatory_attention";
    private static final String CHECK = "finapp_accounting_close_check_total";
    private static final String LEG = "finapp_accounting_close_leg_failed_total";
    private static final String REFUSED_CUTOFF = "finapp_accounting_cutoff_refused_total";
    private static final String FLOORED = "finapp_accounting_cutoff_floored_total";
    private static final String RUN = "finapp_accounting_report_run_total";
    private static final String REFUSED = "finapp_accounting_report_refused_total";
    private static final String DIVERGED = "finapp_accounting_report_diverged_total";
    private static final String SUBMISSION = "finapp_accounting_regulatory_submission_total";
    private static final String LOAN_WRITE_OFF = "finapp_lending_write_off_total";
    private static final String BNPL_WRITE_OFF = "finapp_bnpl_write_off_total";

    /** The series a gauge publishes, told apart from a counter's or a timer's by its suffix: a new gauge joins with no line here. */
    private static final Pattern COUNTED = Pattern.compile(".*_(total|count|sum|max|bucket)$");

    /** A scrape reaches every read gauge of the context: once per class is enough, and it is a fresh instance's. */
    private static String scraped;

    @Autowired private MeterRegistry registry;

    // ------------------------------------------------------------------ can it fire: the series exist

    @Test
    @DisplayName("the published names are the code's constants, units included - the age's _days among them, a name the plan's table never said")
    void theSeriesIsTheCodes() {
        assertThat(CloseRunStallMetrics.STALLED.replace('.', '_')).isEqualTo(RUN_STALLED);
        assertThat(CloseCheckStallMetrics.STALLED.replace('.', '_')).isEqualTo(CHECK_STALLED);
        assertThat(AccountingPeriodMetrics.OPEN_AGE.replace('.', '_') + "_days").isEqualTo(OPEN_AGE);
        assertThat(AccountingMappingMetrics.UNMAPPED.replace('.', '_')).isEqualTo(UNMAPPED);
        assertThat(AccountingBreakMetrics.OPEN.replace('.', '_')).isEqualTo(BREAK_OPEN);
        assertThat(AccountingBreakAgeMetrics.AGED.replace('.', '_')).isEqualTo(BREAK_AGED);
        assertThat(PriorPeriodAdjustmentMetrics.STANDING.replace('.', '_')).isEqualTo(STANDING);
        assertThat(ClosedMonthRunMetrics.UNRUN.replace('.', '_')).isEqualTo(UNRUN);
        assertThat(RegulatoryAttentionMetrics.ATTENTION.replace('.', '_')).isEqualTo(ATTENTION);
        assertThat(CloseRunMetrics.CHECK.replace('.', '_') + "_total").isEqualTo(CHECK);
        assertThat(CloseRunMetrics.LEG_FAILED.replace('.', '_') + "_total").isEqualTo(LEG);
        assertThat(ReportRunMetrics.RUN.replace('.', '_') + "_total").isEqualTo(RUN);
        assertThat(ReportRunMetrics.REFUSED.replace('.', '_') + "_total").isEqualTo(REFUSED);
        assertThat(ReportRunMetrics.DIVERGED.replace('.', '_') + "_total").isEqualTo(DIVERGED);
        assertThat(RegulatorySubmissionMetrics.SUBMISSION.replace('.', '_') + "_total").isEqualTo(SUBMISSION);
        assertThat(LoanWriteOffMetrics.WRITE_OFF.replace('.', '_') + "_total").isEqualTo(LOAN_WRITE_OFF);
        assertThat(BnplWriteOffMetrics.WRITE_OFF.replace('.', '_') + "_total").isEqualTo(BNPL_WRITE_OFF);
        assertThat(LedgerWriteMeters.CUTOFF_REFUSED.replace('.', '_') + "_total").isEqualTo(REFUSED_CUTOFF);
        assertThat(LedgerWriteMeters.CUTOFF_FLOORED.replace('.', '_') + "_total").isEqualTo(FLOORED);
    }

    @Test
    @DisplayName("every selector of every rule is in a real scrape of a fresh instance with the labels it filters on and groups by: a rule on a series or a label value that does not exist would be green forever")
    void everySelectorResolvesInARealScrape() {
        List<RuleEvaluator.Sample> scrape = fresh();
        Map<String, String> rules = rules();
        assertThat(rules).as("the rules file parses, or this checks nothing").hasSizeGreaterThan(20);
        rules.forEach((alert, expr) -> {
            Set<String> selectors = RuleEvaluator.selectorsOf(expr);
            assertThat(selectors).as("%s reads a series", alert).isNotEmpty();
            for (String selector : selectors) {
                assertThat(RuleEvaluator.matching(scrape, selector)).as("%s reads %s, which a fresh instance publishes", alert, selector).isNotEmpty();
            }
            Matcher grouped = Pattern.compile("by \\(([a-z_]+)\\)").matcher(expr);
            while (grouped.find()) {
                String label = grouped.group(1);
                for (String selector : selectors) {
                    assertThat(RuleEvaluator.matching(scrape, selector)).as("%s groups by %s", alert, label)
                            .allSatisfy(sample -> assertThat(sample.labels()).containsKey(label));
                }
            }
        });
    }

    @Test
    @DisplayName("every counter a rule reads is there at zero from startup: increase() cannot see the first event of a series born at that event")
    void everyCounterARuleReadsIsAtZeroFromStartup() {
        List<RuleEvaluator.Sample> scrape = fresh();
        int read = 0;
        for (Map.Entry<String, String> rule : rules().entrySet()) {
            if (!rule.getValue().contains("increase(")) {
                continue;
            }
            for (String selector : RuleEvaluator.selectorsOf(rule.getValue())) {
                read++;
                assertThat(RuleEvaluator.matching(scrape, selector)).as("%s reads %s", rule.getKey(), selector).isNotEmpty()
                        .allSatisfy(sample -> assertThat(sample.value()).as("%s at startup", selector).isZero());
            }
        }
        assertThat(read).as("the rules that read a counter are found").isGreaterThan(15);
        // And what that buys, in the evaluator's own terms: a series born at its event shows no increase, the very first alert lost.
        String contrary = rules().get("RegulatoryContraryAnswer");
        Predicate<RuleEvaluator.Sample> it = named(SUBMISSION).and(label("outcome", "contrary"));
        List<RuleEvaluator.Sample> healthy = healthy();
        assertThat(RuleEvaluator.fires(contrary, healthy.stream().filter(it.negate()).toList(), bump(healthy, it, 1)))
                .as("born at its first event: unseen").isFalse();
        assertThat(RuleEvaluator.fires(contrary, healthy, bump(healthy, it, 1))).as("registered at zero: seen").isTrue();
    }

    // ------------------------------------------------------------------ the unreadable path, for real

    @Test
    @DisplayName("the database gone, in a scrape that completes: every read gauge publishes NaN and none 0, and over those samples the unreadable rules alone fire - no threshold rule reads NaN. Every read met the absent database for real, on the gauge refresher, before the scrape: a NaN here is a failed read, never one that did not run")
    void aCompletedScrapeWithTheDatabaseGoneHoldsNoNumber() {
        List<RuleEvaluator.Sample> raw = fresh();
        List<RuleEvaluator.Sample> gauges = raw.stream().filter(sample -> isReadGauge(sample.name())).toList();
        assertThat(gauges.stream().map(RuleEvaluator.Sample::name).collect(Collectors.toSet()))
                .as("the census: every gauge the rules read").contains(RUN_STALLED, CHECK_STALLED, OPEN_AGE, UNMAPPED, BREAK_OPEN, BREAK_AGED, STANDING, UNRUN, ATTENTION);
        assertThat(gauges).as("no database: no gauge claims a number").allSatisfy(sample -> assertThat(sample.value()).as(sample.name()).isNaN());
        List<RuleEvaluator.Sample> running = set(raw, named(SWEEPER), value -> 1.0);
        assertThat(firing(running, running)).containsExactly("AccountingCloseCheckStallUnreadable", "AccountingCloseRunStallUnreadable",
                "AccountingGaugesUnreadable", "RegulatoryAttentionUnreadable");
    }

    @Test
    @DisplayName("each read gauge alone unreadable wakes one unreadable rule alone; each gauge's series gone wakes the absent rule alone: a dead gauge never reads as healthy")
    void aDeadGaugeIsNeverHealthy() {
        List<RuleEvaluator.Sample> healthy = healthy();
        Set<String> gauges = healthy.stream().map(RuleEvaluator.Sample::name).filter(AccountingAlertRulesTest::isReadGauge).collect(Collectors.toCollection(TreeSet::new));
        assertThat(gauges).hasSizeGreaterThanOrEqualTo(9);
        for (String gauge : gauges) {
            List<RuleEvaluator.Sample> unreadable = set(healthy, named(gauge), value -> Double.NaN);
            assertThat(firing(unreadable, unreadable)).as("%s NaN", gauge).hasSize(1).allSatisfy(alert -> assertThat(alert).endsWith("Unreadable"));
            List<RuleEvaluator.Sample> gone = healthy.stream().filter(named(gauge).negate()).toList();
            assertThat(firing(gone, gone)).as("%s gone", gauge).containsExactly("AccountingGaugesAbsent");
        }
        List<RuleEvaluator.Sample> gone = healthy.stream().filter(named(SWEEPER).negate()).toList();
        assertThat(firing(gone, gone)).as("the schedule's flag gone").containsExactly("AccountingGaugesAbsent");
    }

    // ------------------------------------------------------------------ on its break, and only then

    @Test
    @DisplayName("on a healthy reading nothing fires")
    void nothingFiresWhenHealthy() {
        List<RuleEvaluator.Sample> healthy = healthy();
        assertThat(firing(healthy, healthy)).isEmpty();
    }

    @Test
    @DisplayName("every alert in the file fires alone on its own break, and is silent one short of it; a rule with no case here fails")
    void everyAlertFiresOnItsBreakAlone() {
        List<RuleEvaluator.Sample> healthy = healthy();
        Set<String> covered = new TreeSet<>();

        level(covered, "AccountingCloseRunStalled", healthy, named(RUN_STALLED), 0);
        level(covered, "AccountingCloseCheckStalled", healthy, named(CHECK_STALLED), 0);
        level(covered, "AccountingCloseOverdue", healthy, named(OPEN_AGE), AccountingObjectives.CLOSE_OVERDUE_DAYS);
        level(covered, "AccountingAdjustmentStanding", healthy, named(STANDING), 0);
        level(covered, "AccountingMappingGap", healthy, named(UNMAPPED), 0);
        level(covered, "AccountingClosedMonthLostItsRun", healthy, named(UNRUN), 0);
        for (BreakType type : BreakType.values()) {
            level(covered, "AccountingBreakAged", healthy, named(BREAK_AGED).and(label("type", type.name())), 0);
        }
        level(covered, "RegulatoryResendsExhausted", healthy, named(ATTENTION).and(label("kind", "resends_exhausted")), 0);
        level(covered, "RegulatoryStaleUnsent", healthy, named(ATTENTION).and(label("kind", "stale_unsent")), 0);

        // The schedule off everywhere; a single instance off is no break (below).
        List<RuleEvaluator.Sample> off = set(healthy, named(SWEEPER), value -> 0.0);
        assertThat(firing(off, off)).containsExactly("AccountingScheduleOff");
        covered.add("AccountingScheduleOff");

        for (CloseCheckCode check : CloseCheckCode.values()) {
            counter(covered, "AccountingCloseCheckFailed", healthy, named(CHECK).and(label("check", check.name())).and(label("outcome", "failed")));
        }
        for (String leg : CloseRunMetrics.LEGS) {
            Predicate<RuleEvaluator.Sample> it = named(LEG).and(label("leg", leg));
            assertThat(firing(healthy, bump(healthy, it, 1))).as("%s failing once is a blip", leg).isEmpty();
            assertThat(firing(healthy, bump(healthy, it, 2))).as("%s failing twice", leg).containsExactly("AccountingCloseLegFailing");
        }
        covered.add("AccountingCloseLegFailing");

        for (String command : List.of("posting", "reversal")) {
            counter(covered, "AccountingPostingIntoClosedPeriod", healthy, named(REFUSED_CUTOFF).and(label("command", command)));
        }
        counter(covered, "AccountingAdjustmentIntoClosedPeriod", healthy, named(REFUSED_CUTOFF).and(label("command", "adjustment")));

        for (String definition : values(healthy, DIVERGED, "definition")) {
            counter(covered, "AccountingReportDiverged", healthy, named(DIVERGED).and(label("definition", definition)));
            counter(covered, "AccountingReportRunFailed", healthy, named(RUN).and(label("definition", definition)).and(label("outcome", "failed")));
        }
        counter(covered, "AccountingClosedMonthStatementRefused", healthy, named(REFUSED).and(label("outcome", ReportRunMetrics.REFUSED_FINAL)));

        for (Map.Entry<String, String> event : Map.of("RegulatorySubmissionRejected", "rejected", "RegulatoryContraryAnswer", "contrary",
                "RegulatoryRunBytesDiffer", "run_bytes_differ", "RegulatorySendWithheld", "send_withheld_month_moved",
                "RegulatoryResendWithheld", "not_resent_month_moved", "RegulatoryAnswerAnomaly", "answer_anomaly").entrySet()) {
            counter(covered, event.getKey(), healthy, named(SUBMISSION).and(label("outcome", event.getValue())));
        }
        for (Map.Entry<String, String> growing : Map.of("RegulatoryReturnOverdue", "overdue", "RegulatoryReturnOvertaken", "overtaken").entrySet()) {
            Predicate<RuleEvaluator.Sample> it = named(ATTENTION).and(label("kind", growing.getValue()));
            List<RuleEvaluator.Sample> standing = set(healthy, it, value -> 5.0);
            assertThat(firing(standing, standing)).as("%s standing is no alert", growing.getValue()).isEmpty();
            assertThat(firing(standing, set(healthy, it, value -> 6.0))).as("%s grown", growing.getValue()).containsExactly(growing.getKey());
            assertThat(firing(standing, set(healthy, it, value -> 4.0))).as("%s falling", growing.getValue()).isEmpty();
            covered.add(growing.getKey());
        }
        for (String product : values(healthy, LOAN_WRITE_OFF, "product")) {
            counter(covered, "WriteOffApproved", healthy, named(LOAN_WRITE_OFF).and(label("product", product)).and(label("outcome", "approved")));
            counter(covered, "WriteOffSelfDealingRefused", healthy, named(LOAN_WRITE_OFF).and(label("product", product)).and(label("outcome", "self_dealing_refused")));
        }
        for (String product : values(healthy, BNPL_WRITE_OFF, "product")) {
            counter(covered, "WriteOffApproved", healthy, named(BNPL_WRITE_OFF).and(label("product", product)).and(label("outcome", "approved")));
            counter(covered, "WriteOffSelfDealingRefused", healthy, named(BNPL_WRITE_OFF).and(label("product", product)).and(label("outcome", "self_dealing_refused")));
        }
        // Their cases are this class's other tests, each broken for real above.
        covered.addAll(List.of("AccountingCloseRunStallUnreadable", "AccountingCloseCheckStallUnreadable", "AccountingGaugesUnreadable",
                "RegulatoryAttentionUnreadable", "AccountingGaugesAbsent"));
        assertThat(covered).as("every rule in the file is broken by a case here, and nothing else is").isEqualTo(rules().keySet());
    }

    @Test
    @DisplayName("what must stay silent: a set awaited (deferred), an overtaken worker, a disclosure, a floored posting, a report that concluded, every regulatory act not named, a refusal not of a closed month, and breaks merely not RESOLVED")
    void whatIsNoBreakIsSilent() {
        List<RuleEvaluator.Sample> healthy = healthy();
        for (CloseCheckCode check : CloseCheckCode.values()) {
            for (String outcome : CloseRunMetrics.OUTCOMES) {
                if (!outcome.equals("failed")) {
                    assertThat(firing(healthy, bump(healthy, named(CHECK).and(label("check", check.name())).and(label("outcome", outcome)), 40)))
                            .as("%s %s", check, outcome).isEmpty();
                }
            }
        }
        assertThat(firing(healthy, bump(healthy, named(FLOORED), 5))).as("a floored posting is no refusal").isEmpty();
        for (String outcome : ReportRunMetrics.OUTCOMES) {
            if (!outcome.equals("failed")) {
                assertThat(firing(healthy, bump(healthy, named(RUN).and(label("outcome", outcome)), 1))).as(outcome).isEmpty();
            }
        }
        for (String refusal : List.of(ReportRunMetrics.REFUSED_PROVISIONAL, ReportRunMetrics.REFUSED_TRANSLATION)) {
            assertThat(firing(healthy, bump(healthy, named(REFUSED).and(label("outcome", refusal)), 1))).as("%s does not page", refusal).isEmpty();
        }
        Set<String> alerted = Set.of("rejected", "contrary", "run_bytes_differ", "send_withheld_month_moved", "not_resent_month_moved", "answer_anomaly");
        for (String outcome : RegulatorySubmissionMetrics.OUTCOMES) {
            if (!alerted.contains(outcome)) {
                assertThat(firing(healthy, bump(healthy, named(SUBMISSION).and(label("outcome", outcome)), 1))).as(outcome).isEmpty();
            }
        }
        List<RuleEvaluator.Sample> acknowledged = set(healthy, named(BREAK_OPEN), value -> 12.0);
        assertThat(firing(acknowledged, acknowledged)).as("break.open above zero for good: an ACKNOWLEDGED generation, superseded or not").isEmpty();
        for (String outcome : List.of("proposed", "rejected", "stale", "behind_accrual")) {
            assertThat(firing(healthy, bump(healthy, named(LOAN_WRITE_OFF).and(label("outcome", outcome)), 1))).as(outcome).isEmpty();
        }
    }

    // ------------------------------------------------------------------ N instances

    @Test
    @DisplayName("ten instances: a gauge's one number pages once, not ten times; one instance's read failing leaves the others' number to the threshold rule; one instance off is no break; a counter on any one fires once")
    void tenInstancesPageOnce() {
        List<RuleEvaluator.Sample> healthy = instances(healthy(), 10);
        List<RuleEvaluator.Sample> stalled = set(healthy, named(RUN_STALLED), value -> 1.0);
        assertThat(RuleEvaluator.series(rules().get("AccountingCloseRunStalled"), stalled, stalled)).as("one alert, not ten").hasSize(1);
        assertThat(firing(stalled, stalled)).containsExactly("AccountingCloseRunStalled");

        List<RuleEvaluator.Sample> oneAway = set(stalled, named(RUN_STALLED).and(label("instance", "i0:8080")), value -> Double.NaN);
        assertThat(firing(oneAway, oneAway)).as("the others still say so, and the unreadable one is said too")
                .containsExactly("AccountingCloseRunStallUnreadable", "AccountingCloseRunStalled");

        List<RuleEvaluator.Sample> oneOff = set(healthy, named(SWEEPER).and(label("instance", "i3:8080")), value -> 0.0);
        assertThat(firing(oneOff, oneOff)).as("the other nine run every leg").isEmpty();

        Predicate<RuleEvaluator.Sample> contrary = named(SUBMISSION).and(label("outcome", "contrary"));
        List<RuleEvaluator.Sample> onOne = bump(healthy, contrary.and(label("instance", "i7:8080")), 1);
        assertThat(RuleEvaluator.series(rules().get("RegulatoryContraryAnswer"), healthy, onOne)).as("sum() across instances").hasSize(1);
        List<RuleEvaluator.Sample> onAll = bump(healthy, contrary, 1);
        assertThat(RuleEvaluator.series(rules().get("RegulatoryContraryAnswer"), healthy, onAll)).hasSize(1);
    }

    @Test
    @DisplayName("a restart is a counter's reset and no event: down from five to zero nothing fires; an instance's fresh start with a real event after it fires")
    void aRestartIsNoEvent() {
        List<RuleEvaluator.Sample> healthy = healthy();
        Predicate<RuleEvaluator.Sample> it = named(SUBMISSION).and(label("outcome", "rejected"));
        List<RuleEvaluator.Sample> running = set(healthy, it, value -> 5.0);
        assertThat(firing(running, healthy)).as("restarted: the counter back at zero").isEmpty();
        assertThat(firing(running, bump(healthy, it, 1))).as("restarted, and one rejected since").containsExactly("RegulatorySubmissionRejected");
    }

    // ------------------------------------------------------------------ through time

    @Test
    @DisplayName("through time: a month inside its week never fires, however long it stands there; past it, it fires after its hour")
    void aMonthInsideItsWeekIsCalm() {
        List<RuleEvaluator.Sample> healthy = relevant(healthy());
        long days = AccountingObjectives.CLOSE_OVERDUE_DAYS;
        assertThat(timeline("AccountingCloseOverdue", second -> set(healthy, named(OPEN_AGE), value -> (double) days)).firing(6 * 3600))
                .as("day %s, all day", days).isEmpty();
        long wait = forOf("AccountingCloseOverdue").toSeconds();
        Optional<Long> first = timeline("AccountingCloseOverdue", second -> set(healthy, named(OPEN_AGE), value -> second < 3600 ? (double) days : days + 1.0))
                .firstFiring(6 * 3600);
        assertThat(first).hasValueSatisfying(at -> assertThat(at).isBetween(3600 + wait, 3600 + wait + 2 * RuleTimeline.EVALUATION.toSeconds()));
    }

    @Test
    @DisplayName("through time: a blip shorter than a stall's minute stays silent, one that holds fires; a NaN for ten minutes stays silent, for twenty it is ticketed")
    void aStallAndAnUnreadableHonourTheirWaits() {
        List<RuleEvaluator.Sample> healthy = relevant(healthy());
        assertThat(timeline("AccountingCloseCheckStalled", second -> set(healthy, named(CHECK_STALLED), value -> second == 600 ? 3.0 : 0.0)).firing(3600))
                .as("one scrape's reading").isEmpty();
        assertThat(timeline("AccountingCloseCheckStalled", second -> set(healthy, named(CHECK_STALLED), value -> second >= 600 ? 3.0 : 0.0)).firstFiring(3600))
                .hasValueSatisfying(at -> assertThat(at).isBetween(660L, 720L));
        assertThat(timeline("AccountingGaugesUnreadable", second -> set(healthy, named(BREAK_AGED), value -> second >= 600 && second < 1200 ? Double.NaN : 0.0)).firing(3600))
                .as("ten minutes").isEmpty();
        assertThat(timeline("AccountingGaugesUnreadable", second -> set(healthy, named(BREAK_AGED), value -> second >= 600 ? Double.NaN : 0.0)).firstFiring(3600))
                .hasValueSatisfying(at -> assertThat(at).isGreaterThanOrEqualTo(600 + AccountingObjectives.UNREADABLE_WAIT.toSeconds()));
    }

    @Test
    @DisplayName("through time: overtaken and overdue standing for good never fire; one more fires at once and resolves an hour on - the owner's ruling, read as growth")
    void aLevelThatHoldsForGoodIsCalm() {
        List<RuleEvaluator.Sample> healthy = relevant(healthy());
        for (Map.Entry<String, String> rule : Map.of("RegulatoryReturnOvertaken", "overtaken", "RegulatoryReturnOverdue", "overdue").entrySet()) {
            Predicate<RuleEvaluator.Sample> it = named(ATTENTION).and(label("kind", rule.getValue()));
            assertThat(timeline(rule.getKey(), second -> set(healthy, it, value -> 3.0)).firing(6 * 3600)).as("three, standing").isEmpty();
            RuleTimeline grows = timeline(rule.getKey(), second -> set(healthy, it, value -> second < 3600 ? 3.0 : 4.0));
            assertThat(grows.firstFiring(6 * 3600)).hasValueSatisfying(at -> assertThat(at).isBetween(3600L, 3780L));
            assertThat(grows.firing(6 * 3600).last()).as("resolved an hour after").isLessThan(2 * 3600 + 120L);
        }
    }

    @Test
    @DisplayName("through time: one contrary answer fires within minutes and resolves; one leg failure stays silent, the hourly leg's second is seen")
    void eventsThroughTime() {
        List<RuleEvaluator.Sample> healthy = relevant(healthy());
        Predicate<RuleEvaluator.Sample> contrary = named(SUBMISSION).and(label("outcome", "contrary"));
        RuleTimeline one = timeline("RegulatoryContraryAnswer", second -> set(healthy, contrary, value -> second >= 600 ? 1.0 : 0.0));
        assertThat(one.firstFiring(3 * 3600)).hasValueSatisfying(at -> assertThat(at).isBetween(600L, 780L));
        assertThat(one.firing(3 * 3600).last()).isLessThan(4200L + 120);
        Predicate<RuleEvaluator.Sample> open = named(LEG).and(label("leg", "open"));
        assertThat(timeline("AccountingCloseLegFailing", second -> set(healthy, open, value -> second >= 600 ? 1.0 : 0.0)).firing(4 * 3600)).as("one blip").isEmpty();
        assertThat(timeline("AccountingCloseLegFailing", second -> set(healthy, open, value -> second >= 3900 ? 2.0 : second >= 300 ? 1.0 : 0.0)).firstFiring(4 * 3600))
                .as("failing at two hourly ticks").hasValueSatisfying(at -> assertThat(at).isBetween(3900L, 4080L));
    }

    // ------------------------------------------------------------------ bounds and shape

    @Test
    @DisplayName("each threshold and wait is the objective the code declares")
    void theThresholdsAreTheObjectives() {
        Map<String, Map<String, Object>> rules = ruleMaps();
        assertThat(rules.get("AccountingCloseOverdue").get("expr").toString().strip()).endsWith("> " + AccountingObjectives.CLOSE_OVERDUE_DAYS);
        String unreadable = AccountingObjectives.UNREADABLE_WAIT.toMinutes() + "m";
        for (String alert : List.of("AccountingCloseRunStallUnreadable", "AccountingCloseCheckStallUnreadable", "AccountingGaugesUnreadable", "RegulatoryAttentionUnreadable")) {
            assertThat(String.valueOf(rules.get(alert).get("for"))).as(alert).isEqualTo(unreadable);
        }
        assertThat(Duration.parse(AccountingObjectives.BREAK_AGED_AFTER)).isEqualTo(Duration.ofDays(AccountingObjectives.CLOSE_OVERDUE_DAYS));
        assertThat(Duration.parse(AccountingObjectives.ADJUSTMENT_STANDING_AFTER)).isEqualTo(Duration.ofDays(AccountingObjectives.CLOSE_OVERDUE_DAYS));
        assertThat(Duration.parse(AccountingObjectives.GAUGE_STATEMENT_TIMEOUT)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("each rule is well formed: a for, a page or ticket severity, a summary, a description citing the runbook, and no label that could name a period, a subject or a figure")
    void everyRuleIsWellFormed() {
        Set<String> labels = Set.of("outcome", "kind", "check", "leg", "command", "definition", "type", "product");
        assertThat(ruleList()).allSatisfy(rule -> {
            String alert = String.valueOf(rule.get("alert"));
            assertThat(String.valueOf(rule.get("for"))).as(alert).matches("\\d+[smhd]");
            assertThat(map(rule.get("labels"))).as(alert).containsOnlyKeys("severity");
            assertThat(map(rule.get("labels")).get("severity")).as(alert).isIn("page", "ticket");
            assertThat(String.valueOf(map(rule.get("annotations")).get("summary"))).as(alert).isNotBlank().isNotEqualTo("null");
            assertThat(String.valueOf(map(rule.get("annotations")).get("description"))).as(alert).contains("OPERATIONS_RUNBOOK.md section ");
            String expr = String.valueOf(rule.get("expr"));
            for (Pattern pattern : List.of(Pattern.compile("([a-z_]+)!?=\""), Pattern.compile("by \\(([a-z_]+)\\)"))) {
                Matcher used = pattern.matcher(expr);
                while (used.find()) {
                    assertThat(used.group(1)).as("%s reads the label %s", alert, used.group(1)).isIn(labels);
                }
            }
            Matcher templated = Pattern.compile("\\$labels\\.([a-z_]+)").matcher(map(rule.get("annotations")).toString());
            while (templated.find()) {
                assertThat(templated.group(1)).as("%s templates %s", alert, templated.group(1)).isIn(labels);
            }
        });
    }

    // ------------------------------------------------------------------ cases

    private void level(Set<String> covered, String alert, List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample, long threshold) {
        List<RuleEvaluator.Sample> at = set(healthy, sample, value -> (double) threshold);
        assertThat(firing(at, at)).as("%s at its threshold %d", alert, threshold).isEmpty();
        List<RuleEvaluator.Sample> past = set(healthy, sample, value -> threshold + 1.0);
        assertThat(firing(past, past)).as("%s one past %d", alert, threshold).containsExactly(alert);
        covered.add(alert);
    }

    private void counter(Set<String> covered, String alert, List<RuleEvaluator.Sample> healthy, Predicate<RuleEvaluator.Sample> sample) {
        assertThat(firing(healthy, healthy)).doesNotContain(alert);
        assertThat(firing(healthy, bump(healthy, sample, 1))).as(alert).containsExactly(alert);
        covered.add(alert);
    }

    /** The source-join census's gauges are read by {@code correctness.yml}, not by this file: their unreadable and absent legs are that file's. */
    private static boolean isReadGauge(String name) {
        return name.startsWith("finapp_accounting_") && !name.startsWith("finapp_accounting_source_census_")
                && !COUNTED.matcher(name).matches() && !name.equals(SWEEPER);
    }

    private static Set<String> values(List<RuleEvaluator.Sample> scrape, String name, String label) {
        Set<String> values = scrape.stream().filter(named(name)).map(sample -> sample.labels().get(label)).collect(Collectors.toCollection(TreeSet::new));
        assertThat(values).as("%s is published with its %s", name, label).isNotEmpty();
        return values;
    }

    private static Predicate<RuleEvaluator.Sample> named(String name) {
        return sample -> sample.name().equals(name);
    }

    private static Predicate<RuleEvaluator.Sample> label(String label, String value) {
        return sample -> value.equals(sample.labels().get(label));
    }

    private static List<RuleEvaluator.Sample> set(List<RuleEvaluator.Sample> scrape, Predicate<RuleEvaluator.Sample> which, Function<Double, Double> value) {
        assertThat(scrape.stream().anyMatch(which)).as("the series the rule watches is published").isTrue();
        return scrape.stream().map(sample -> which.test(sample) ? sample.withValue(value.apply(sample.value())) : sample).toList();
    }

    private static List<RuleEvaluator.Sample> bump(List<RuleEvaluator.Sample> scrape, Predicate<RuleEvaluator.Sample> which, long by) {
        return set(scrape, which, value -> value + by);
    }

    private static Set<String> firing(List<RuleEvaluator.Sample> before, List<RuleEvaluator.Sample> after) {
        Set<String> firing = new TreeSet<>();
        rules().forEach((alert, expr) -> {
            if (RuleEvaluator.fires(expr, before, after)) {
                firing.add(alert);
            }
        });
        return firing;
    }

    private static RuleTimeline timeline(String alert, java.util.function.LongFunction<List<RuleEvaluator.Sample>> scrapeAt) {
        return RuleTimeline.of(rules().get(alert), forOf(alert), scrapeAt);
    }

    private static Duration forOf(String alert) {
        return RuleTimeline.duration(String.valueOf(ruleMaps().get(alert).get("for")));
    }

    /** {@code scrape}'s samples under {@code count} instances, {@code i0:8080} onward: every instance publishes every series. */
    private static List<RuleEvaluator.Sample> instances(List<RuleEvaluator.Sample> scrape, int count) {
        List<RuleEvaluator.Sample> all = new ArrayList<>();
        for (RuleEvaluator.Sample sample : scrape) {
            for (int instance = 0; instance < count; instance++) {
                Map<String, String> labels = new LinkedHashMap<>(sample.labels());
                labels.put("instance", "i" + instance + ":8080");
                all.add(new RuleEvaluator.Sample(sample.name(), labels, sample.value()));
            }
        }
        return all;
    }

    /** Only what a rule reads: a timeline evaluates thousands of times. */
    private static List<RuleEvaluator.Sample> relevant(List<RuleEvaluator.Sample> scrape) {
        Set<String> read = rules().values().stream().flatMap(expr -> RuleEvaluator.seriesOf(expr).stream()).collect(Collectors.toSet());
        return scrape.stream().filter(sample -> read.contains(sample.name())).toList();
    }

    /** The fresh instance's scrape with every read gauge at a healthy zero and the schedule on. Counters and timers keep their values. */
    private List<RuleEvaluator.Sample> healthy() {
        List<RuleEvaluator.Sample> healthy = new ArrayList<>();
        for (RuleEvaluator.Sample sample : fresh()) {
            healthy.add(sample.name().equals(SWEEPER) ? sample.withValue(1) : isReadGauge(sample.name()) ? sample.withValue(0) : sample);
        }
        return healthy;
    }

    private List<RuleEvaluator.Sample> fresh() {
        return RuleEvaluator.samples(scrape(), Map.of("job", "finapp", "instance", "host.docker.internal:8080"));
    }

    private String scrape() {
        if (scraped == null) {
            PrometheusMeterRegistry prometheus = registry instanceof PrometheusMeterRegistry direct ? direct
                    : (PrometheusMeterRegistry) ((io.micrometer.core.instrument.composite.CompositeMeterRegistry) registry)
                            .getRegistries().stream().filter(PrometheusMeterRegistry.class::isInstance).findFirst()
                            .orElseThrow(() -> new AssertionError("no Prometheus registry"));
            // P15-TSK-024: every gauge reads on the gauge refresher, and a look answers its last read - NaN before the first.
            // So the accounting reads are asked for now and awaited: each meets the absent database for real (the pool's
            // 5 s, then no value), and a gauge that failed to anything but NaN shows here, not hidden behind "not read yet".
            RefreshedGauges.settle(registry, "finapp.accounting.");
            scraped = prometheus.scrape();
        }
        return scraped;
    }

    /** Alert name to expression, in file order. */
    static Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), String.valueOf(rule.get("expr")).strip()));
        return rules;
    }

    private static Map<String, Map<String, Object>> ruleMaps() {
        Map<String, Map<String, Object>> rules = new TreeMap<>();
        ruleList().forEach(rule -> rules.put(String.valueOf(rule.get("alert")), rule));
        return rules;
    }

    private static List<Map<String, Object>> cachedRules;

    static List<Map<String, Object>> ruleList() {
        if (cachedRules == null) {
            YamlMapFactoryBean factory = new YamlMapFactoryBean();
            factory.setResources(new FileSystemResource(RepositoryPaths.locate(RULES)));
            Map<String, Object> document = factory.getObject();
            assertThat(document).as("%s must parse", RULES).isNotNull();
            Map<String, Object> group = list(document.get("groups")).stream().map(AccountingAlertRulesTest::map)
                    .filter(candidate -> "accounting".equals(candidate.get("name"))).findFirst()
                    .orElseThrow(() -> new AssertionError("no rules group named accounting"));
            cachedRules = list(group.get("rules")).stream().map(AccountingAlertRulesTest::map).collect(Collectors.toList());
        }
        return cachedRules;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object node) {
        assertThat(node).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    private static List<?> list(Object node) {
        assertThat(node).isInstanceOf(List.class);
        return (List<?>) node;
    }
}
