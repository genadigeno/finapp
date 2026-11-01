package com.finapp.app.accounting;

import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.ReportDefinitions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The report runs' meters (`P14-TSK-008`; {@code PHASE_14_PLAN.md} section 16): {@code finapp.accounting.report.run}
 * {definition, outcome} - one count per concluded run, {@code final}, {@code provisional} or {@code failed} - and
 * {@code finapp.accounting.report.diverged}{definition} - one per FINAL run whose regeneration was not
 * {@code IDENTICAL} (the alert {@code AccountingReportDiverged}, `P14-TSK-020`: a FINAL report that does not reproduce is
 * an incident, never a repair). Counted after the commit, once per edge this instance made - a converging second
 * completer counts nothing.
 *
 * <p>The {@code definition} tag is a definition's code - {@code MetricNames.ALLOWED_TAG_KEYS}' deliberate addition by this
 * task, bounded by the definitions compiled into the build, never a request's value; {@code outcome} a closed set. No tag
 * carries a period, an id, a figure or a hash. Registered eagerly for every definition the build holds (from
 * `P14-TSK-009`, {@code TRIAL_BALANCE}), so a dashboard sees a zero rather than an absence.
 */
public final class ReportRunMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_report_run_total}. */
    public static final String RUN = "finapp.accounting.report.run";

    /** Spelled with dots - exported as {@code finapp_accounting_report_diverged_total}. */
    public static final String DIVERGED = "finapp.accounting.report.diverged";

    /** The {@code outcome} tag's values. */
    public static final List<String> OUTCOMES = List.of("final", "provisional", "failed");

    private final MeterRegistry registry;

    public ReportRunMetrics(MeterRegistry registry, ReportDefinitions definitions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (String definition : definitions.codes()) {
            for (String outcome : OUTCOMES) {
                run(definition, outcome);
            }
            diverged(definition);
        }
    }

    /** A run this instance concluded - its completion or its failure. */
    public void concluded(ReportRunStore.RunRow run) {
        RunStatus status = run.status();
        if (status == RunStatus.FINAL || status == RunStatus.PROVISIONAL || status == RunStatus.FAILED) {
            run(run.definition(), status.name().toLowerCase(Locale.ROOT)).increment();
        }
    }

    /** A FINAL run's regeneration that was not identical. */
    public void diverged(ReportRunStore.RunRow run) {
        diverged(run.definition()).increment();
    }

    private Counter run(String definition, String outcome) {
        return Counter.builder(RUN)
                .description("Report runs concluded, by definition and outcome (final, provisional, failed)")
                .tag("definition", definition)
                .tag("outcome", outcome)
                .register(registry);
    }

    private Counter diverged(String definition) {
        return Counter.builder(DIVERGED)
                .description("FINAL report runs whose regeneration was not IDENTICAL - never repaired; alert"
                        + " AccountingReportDiverged")
                .tag("definition", definition)
                .register(registry);
    }
}
