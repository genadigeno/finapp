package com.finapp.app.risk;

import com.finapp.risk.RegulatoryFilingProvider;
import com.finapp.risk.RegulatoryReportStatus;
import com.finapp.risk.ReportObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The regulatory report's meters (`P13-TSK-021`; {@code PHASE_13_PLAN.md} section 15's {@code finapp.risk.report{status}};
 * {@code DESIGN-021} D11; the Phase 12 → 13 gate's N5): {@code finapp.risk.report{status}} - a report reaching each
 * status; {@code finapp.risk.report.filing{outcome}} - every answer the authority gave a submission or an inquiry
 * ({@code acknowledged}, {@code rejected}, {@code unknown}, {@code not_received}) and every call that threw
 * ({@code failed}) - {@code unknown} and {@code failed} are the runbook's "a filing unknown" alert's inputs (`-024`
 * writes the rule); and {@code finapp.risk.sweeper.claim.failed{kind=report}} - a claim of the filing's drive that
 * failed. Every series registered eagerly; the tags closed vocabularies - never an id, a reference, a jurisdiction's
 * subject or a code the authority sent ({@code INV-RSK-08}, {@code MetricNames.ALLOWED_TAG_KEYS}).
 */
public final class RegulatoryReportMetrics implements ReportObserver {

    public static final String REPORT = "finapp.risk.report";
    public static final String FILING = "finapp.risk.report.filing";

    /** Exported as {@code finapp_risk_sweeper_claim_failed_total}, beside the other risk sweepers'. */
    public static final String CLAIM_FAILED = RiskMonitoringMetrics.CLAIM_FAILED;

    /** The {@code kind} tag of the filing's drive's claim failures - the report leg of {@code RiskActionSchedule}. */
    public static final String KIND = "report";

    /** The {@code outcome} tag of a call that threw - nothing known of it. */
    public static final String FAILED = "failed";

    private final Map<RegulatoryReportStatus, Counter> statuses = new EnumMap<>(RegulatoryReportStatus.class);
    private final Map<RegulatoryFilingProvider.Outcome, Counter> answers =
            new EnumMap<>(RegulatoryFilingProvider.Outcome.class);
    private final Counter failed;
    private final Counter claimFailed;

    public RegulatoryReportMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (RegulatoryReportStatus status : RegulatoryReportStatus.values()) {
            statuses.put(status, Counter.builder(REPORT).tag("status", status.name())
                    .description("Regulatory reports reaching each status").register(registry));
        }
        for (RegulatoryFilingProvider.Outcome outcome : RegulatoryFilingProvider.Outcome.values()) {
            answers.put(outcome, Counter.builder(FILING).tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .description("The authority's answers to a regulatory report's submissions and inquiries")
                    .register(registry));
        }
        failed = Counter.builder(FILING).tag("outcome", FAILED)
                .description("Filing calls that threw - nothing known, the permit left for the next inquiry")
                .register(registry);
        claimFailed = Counter.builder(CLAIM_FAILED).tag("kind", KIND)
                .description("Regulatory report drive claims that failed and were left for a later tick (N5)")
                .register(registry);
    }

    @Override
    public void moved(RegulatoryReportStatus status) {
        statuses.get(Objects.requireNonNull(status, "status")).increment();
    }

    @Override
    public void answered(RegulatoryFilingProvider.Outcome outcome) {
        answers.get(Objects.requireNonNull(outcome, "outcome")).increment();
    }

    /** A filing call that threw - nothing recorded, the permit standing. */
    public void failed() {
        failed.increment();
    }

    /** A claim that failed - nothing claimed for certain; a later tick claims again. */
    public void claimFailed() {
        claimFailed.increment();
    }
}
