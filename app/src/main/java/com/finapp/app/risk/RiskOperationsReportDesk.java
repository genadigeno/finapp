package com.finapp.app.risk;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.CommandRef;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.VelocityProof;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Risk's operations reports desk (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3; {@code DESIGN-023} D8; the
 * {@code RiskQueueDesk} shape): the date judged first ({@code 422 api.ValidationFailed}, nothing read, nothing recorded);
 * the report and its census on ONE read-only {@code REPEATABLE READ} snapshot ({@link RiskOperationsReports}); then
 * {@code risk.ReportRead} committed in a transaction of its own <strong>before</strong> the answer - a report whose read
 * failed was served nothing and is recorded by nothing. Views name codes, counts and exact minor units with their
 * currency; a velocity divergence names its dimension, window and figures - never its subject ({@code INV-RSK-08}).
 */
public final class RiskOperationsReportDesk {

    private final RiskOperationsReports reports;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskOperationsReportDesk(RiskOperationsReports reports, RiskTransactions transactions, RiskFlowScope flow) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    // ------------------------------------------------------------------ views

    public record RiskDecisionsReportView(String date, boolean known, String asOf, List<RiskDecisionRowView> rows,
            List<RiskReasonCountView> reasons, List<RiskVersionCountView> versions,
            RiskAssessmentCensusView census) {}

    /** One group of the day's assessments: how many, and their judged sum in minor units of the currency at the scale. */
    public record RiskDecisionRowView(String operation, String decision, String failSafeCause, Boolean challengeSatisfied,
            String velocityOutcome, String currency, int scale, long assessments, long judgedMinor) {}

    public record RiskReasonCountView(String operation, String reasonCode, long assessments) {}

    public record RiskVersionCountView(String operation, Integer policyVersion, long assessments) {}

    public record RiskAssessmentCensusView(String verdict, List<RiskAssessmentCensusRowView> operations) {}

    /** Assessments = judged commands for one operation; the missing and orphaned commands by reference, bounded. */
    public record RiskAssessmentCensusRowView(String operation, String verdict, long assessments, long commands,
            long matched, long reconciled, long unrecordable, List<String> missing, long missingCount,
            List<String> orphaned, long orphanedCount) {}

    public record RiskAlertsReportView(String date, boolean known, String asOf, List<RiskAlertCountView> rows,
            List<RiskAlertRuleCountView> rules, RiskBornOnceCensusView census) {}

    public record RiskAlertCountView(String kind, String severity, String status, long alerts) {}

    public record RiskAlertRuleCountView(String kind, String ruleCode, long alerts) {}

    public record RiskBornOnceCensusView(String verdict, long raised, long duplicates, List<RiskBornOnceRowView> rows) {}

    public record RiskBornOnceRowView(String kind, String window, long raised, long keys, long held, long duplicates) {}

    public record RiskCasesReportView(String date, boolean known, String asOf, List<RiskCaseCountView> opened,
            List<RiskDispositionCountView> disposed, RiskOneOpenCensusView oneOpen, RiskFilingCensusView filings) {}

    public record RiskCaseCountView(String kind, String status, long cases) {}

    public record RiskDispositionCountView(String kind, String disposition, long cases) {}

    public record RiskOneOpenCensusView(String verdict, String asOf, List<RiskOneOpenRowView> rows) {}

    public record RiskOneOpenRowView(String kind, long open, long subjects, long subjectsOverOne) {}

    public record RiskFilingCensusView(String verdict, String asOf, long filed, long due, long overdue) {}

    public record RiskVelocityReportView(String date, boolean known, String asOf, String verdict,
            RiskVelocityLinkView record, RiskVelocityLinkView journal, RiskMonitoringCensusView monitoring) {}

    /** One link of the velocity proof: what it judged, and each divergence - never the window's subject. */
    public record RiskVelocityLinkView(String verdict, String asOf, String evidenceThrough, int judged, int pending,
            int unprovable, int divergedCount, List<RiskVelocityDivergenceView> divergences) {}

    public record RiskVelocityDivergenceView(String dimension, String windowKind, String windowStart, String currency,
            int scale, long recordedCount, long recordedSumMinor, long fromCount, long toCount, long fromSumMinor,
            long toSumMinor) {}

    public record RiskMonitoringCensusView(String verdict, String watermarkThrough, long entries, long monitored,
            long pending, long ahead, List<String> missing, long missingCount, List<String> phantom,
            long phantomCount) {}

    // ------------------------------------------------------------------ the reports

    public RiskDecisionsReportView decisions(String rawDate) {
        RiskOperationsReports.DecisionsReport report = served(rawDate, RiskSpans.PROOF,
                (snapshot, day) -> reports.decisions(snapshot, day), RiskOperationsReports.DecisionsReport::served);
        return new RiskDecisionsReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.rows().stream().map(row -> new RiskDecisionRowView(row.operation().name(), row.decision().name(),
                        row.failSafe().map(Enum::name).orElse(null), row.challengeSatisfied().orElse(null),
                        row.velocity().name(), row.currency().code(), row.scale(), row.assessments(),
                        row.judgedMinor())).toList(),
                report.reasons().stream().map(row -> new RiskReasonCountView(row.operation().name(), row.reason().name(),
                        row.assessments())).toList(),
                report.versions().stream().map(row -> new RiskVersionCountView(row.operation().name(),
                        row.version().isPresent() ? row.version().getAsInt() : null, row.assessments())).toList(),
                report.census().map(RiskOperationsReportDesk::census).orElse(null));
    }

    public RiskAlertsReportView alerts(String rawDate) {
        RiskOperationsReports.AlertsReport report = served(rawDate, RiskSpans.PROOF,
                (snapshot, day) -> reports.alerts(snapshot, day), RiskOperationsReports.AlertsReport::served);
        return new RiskAlertsReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.rows().stream().map(row -> new RiskAlertCountView(row.kind().name(), row.severity().name(),
                        row.status().name(), row.alerts())).toList(),
                report.rules().stream().map(row -> new RiskAlertRuleCountView(row.kind().name(), row.ruleCode(),
                        row.alerts())).toList(),
                report.census().map(RiskOperationsReportDesk::census).orElse(null));
    }

    public RiskCasesReportView cases(String rawDate) {
        RiskOperationsReports.CasesReport report = served(rawDate, RiskSpans.PROOF,
                (snapshot, day) -> reports.cases(snapshot, day), RiskOperationsReports.CasesReport::served);
        return new RiskCasesReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.opened().stream().map(row -> new RiskCaseCountView(row.kind().name(), row.status().name(),
                        row.cases())).toList(),
                report.disposed().stream().map(row -> new RiskDispositionCountView(row.kind().name(),
                        row.disposition().name(), row.cases())).toList(),
                report.open().map(RiskOperationsReportDesk::census).orElse(null),
                report.filings().map(RiskOperationsReportDesk::census).orElse(null));
    }

    public RiskVelocityReportView velocity(String rawDate) {
        RiskOperationsReports.VelocityReport report = served(rawDate, RiskSpans.VELOCITY_PROOF,
                (snapshot, day) -> reports.velocity(snapshot, day), RiskOperationsReports.VelocityReport::served);
        return new RiskVelocityReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.proof().map(whole -> whole.verdict().name()).orElse(null),
                report.proof().map(whole -> link(whole.record())).orElse(null),
                report.proof().flatMap(VelocityProof.Whole::journal).map(RiskOperationsReportDesk::link).orElse(null),
                report.monitoring().map(RiskOperationsReportDesk::census).orElse(null));
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Read<R> {
        R in(java.sql.Connection snapshot, RiskDay day);
    }

    /** The date judged, the report read on one snapshot, recorded in a transaction of its own, then answered. */
    private <R> R served(String rawDate, String span, Read<R> read, Function<R, RiskOperationsReports.Served> served) {
        RiskDay day = RiskReportDates.day(rawDate, "A risk report");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk report runs inside a correlation scope"))
                .correlationId();
        R report = flow.door(span, () -> transactions.inSnapshot(snapshot -> read.in(snapshot, day)));
        transactions.inTransaction(unitOfWork -> {
            reports.record(unitOfWork, served.apply(report), actor, correlation);
            return null;
        });
        return report;
    }

    private static RiskAssessmentCensusView census(AssessmentCensus.Result census) {
        return new RiskAssessmentCensusView(census.verdict().name(), census.operations().stream()
                .map(operation -> new RiskAssessmentCensusRowView(operation.operation().name(),
                        operation.verdict().name(), operation.assessments(), operation.commands(), operation.matched(),
                        operation.reconciled(), operation.unrecordable(),
                        operation.missing().stream().map(CommandRef::value).toList(), operation.missingCount(),
                        operation.orphaned().stream().map(CommandRef::value).toList(), operation.orphanedCount()))
                .toList());
    }

    private static RiskBornOnceCensusView census(AlertBornOnceCensus.Result census) {
        return new RiskBornOnceCensusView(census.verdict().name(), census.raised(), census.duplicates(),
                census.rows().stream().map(row -> new RiskBornOnceRowView(row.kind().name(), row.window().name(),
                        row.raised(), row.keys(), row.held(), row.duplicates())).toList());
    }

    private static RiskOneOpenCensusView census(CaseOneOpenCensus.Result census) {
        return new RiskOneOpenCensusView(census.verdict().name(), census.asOf().toString(), census.rows().stream()
                .map(row -> new RiskOneOpenRowView(row.kind().name(), row.open(), row.subjects(),
                        row.subjectsOverOne())).toList());
    }

    private static RiskFilingCensusView census(FilingCensus.Result census) {
        return new RiskFilingCensusView(census.verdict().name(), census.asOf().toString(), census.filed(), census.due(),
                census.overdue());
    }

    private static RiskMonitoringCensusView census(MonitoringCensus.Result census) {
        return new RiskMonitoringCensusView(census.verdict().name(), census.watermarkThrough().toString(),
                census.entries(), census.monitored(), census.pending(), census.ahead(),
                census.missing().stream().map(UUID::toString).toList(), census.missingCount(),
                census.phantom().stream().map(UUID::toString).toList(), census.phantomCount());
    }

    private static RiskVelocityLinkView link(VelocityProof.Result result) {
        return new RiskVelocityLinkView(result.verdict().name(), result.asOf().toString(),
                result.evidenceThrough().toString(), result.judged(), result.pending(), result.unprovable(),
                result.divergences().size(), com.finapp.risk.RiskProofs.named(result.divergences()).stream()
                        .map(divergence -> new RiskVelocityDivergenceView(divergence.key().dimension().name(),
                                divergence.key().windowKind().name(), divergence.key().windowStart().toString(),
                                divergence.currency().code(), divergence.scale(), divergence.recordedCount(),
                                divergence.recordedSumMinor(), divergence.fromCount(), divergence.toCount(),
                                divergence.fromSumMinor(), divergence.toSumMinor()))
                        .toList());
    }
}
