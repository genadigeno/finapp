package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.CommandRef;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.RiskProofs;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.VelocityProof;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Risk's operations reports desk (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3; {@code DESIGN-023} D8; the
 * {@code RiskQueueDesk} shape): the date judged first ({@code 422 api.ValidationFailed}, nothing read, nothing recorded);
 * the report and its census on ONE read-only {@code REPEATABLE READ} snapshot ({@link RiskOperationsReports}), every
 * statement bounded ({@link RiskProofs#bound}, {@code finapp.risk.proofs.statement-timeout}); then {@code risk.ReportRead}
 * committed in a transaction of its own <strong>before</strong> the answer - a report whose read failed was served
 * nothing and is recorded by nothing. The reader is placed in a party first, the day replay's rule: a reader the platform
 * cannot place is {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED} against the report in a transaction of its
 * own. Views name codes, counts and exact minor units with their currency; a velocity divergence names its dimension,
 * window, figures and - for the reader's eyes only, an operator's - its subject's reference, withheld (counted) when the
 * subject is the reader's own party's or cannot be placed; an orphaned assessment of the reader's own party is withheld
 * the same way. Never logged ({@code INV-RSK-08}).
 */
public final class RiskOperationsReportDesk {

    private final RiskOperationsReports reports;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;
    private final Duration statementTimeout;

    /** The desk, every snapshot bounded by {@link RiskProofs#STATEMENT_TIMEOUT}. */
    public RiskOperationsReportDesk(RiskOperationsReports reports, RiskTransactions transactions, RiskFlowScope flow) {
        this(reports, transactions, flow, RiskProofs.STATEMENT_TIMEOUT);
    }

    /** The desk, every snapshot armed with {@code statementTimeout}. */
    public RiskOperationsReportDesk(RiskOperationsReports reports, RiskTransactions transactions, RiskFlowScope flow,
            Duration statementTimeout) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
    }

    // ------------------------------------------------------------------ views

    /** The day's decisions; {@code withheld} the orphaned assessments of the reader's own party, counted, not named. */
    public record RiskDecisionsReportView(String date, boolean known, String asOf, List<RiskDecisionRowView> rows,
            List<RiskReasonCountView> reasons, List<RiskVersionCountView> versions,
            RiskAssessmentCensusView census, long withheld) {}

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

    /**
     * The velocity report. {@code proofAsOf} is the proof's own instant - the windows live at the snapshot, whatever the
     * {@code date} asked (the review's M6): the proof is of now, the monitoring census of the date. {@code withheld}
     * counts the divergences whose subject is the reader's own party's or cannot be placed.
     */
    public record RiskVelocityReportView(String date, boolean known, String asOf, String proofAsOf, String verdict,
            RiskVelocityLinkView record, RiskVelocityLinkView journal, long withheld,
            RiskMonitoringCensusView monitoring) {}

    /**
     * One link of the velocity proof: what it judged; {@code explained} the windows whose expected figures had a transfer
     * permitted uncounted subtracted (still judged); {@code beforeFloor} the postings older than the first transfer the
     * record ever holds; and each divergence the reader may locate.
     */
    public record RiskVelocityLinkView(String verdict, String asOf, String evidenceThrough, int judged, int pending,
            int unprovable, int explained, int beforeFloor, int divergedCount,
            List<RiskVelocityDivergenceView> divergences) {}

    /** One divergent window, located by its subject's reference - an operator's read, never logged. */
    public record RiskVelocityDivergenceView(String dimension, String subjectRef, String windowKind, String windowStart,
            String currency, int scale, long recordedCount, long recordedSumMinor, long fromCount, long toCount,
            long fromSumMinor, long toSumMinor) {}

    public record RiskMonitoringCensusView(String verdict, String watermarkThrough, long entries, long monitored,
            long pending, long ahead, List<String> missing, long missingCount, List<String> phantom,
            long phantomCount) {}

    // ------------------------------------------------------------------ the reports

    public RiskDecisionsReportView decisions(String rawDate) {
        RiskOperationsReports.DecisionsReport report = served(rawDate, RiskOperationsReports.Report.DECISIONS,
                RiskSpans.PROOF, reports::decisions, RiskOperationsReports.DecisionsReport::served);
        return new RiskDecisionsReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.rows().stream().map(row -> new RiskDecisionRowView(row.operation().name(), row.decision().name(),
                        row.failSafe().map(Enum::name).orElse(null), row.challengeSatisfied().orElse(null),
                        row.velocity().name(), row.currency().code(), row.scale(), row.assessments(),
                        row.judgedMinor())).toList(),
                report.reasons().stream().map(row -> new RiskReasonCountView(row.operation().name(), row.reason().name(),
                        row.assessments())).toList(),
                report.versions().stream().map(row -> new RiskVersionCountView(row.operation().name(),
                        row.version().isPresent() ? row.version().getAsInt() : null, row.assessments())).toList(),
                report.census().map(RiskOperationsReportDesk::census).orElse(null), report.withheld());
    }

    public RiskAlertsReportView alerts(String rawDate) {
        RiskOperationsReports.AlertsReport report = served(rawDate, RiskOperationsReports.Report.ALERTS,
                RiskSpans.PROOF, reports::alerts, RiskOperationsReports.AlertsReport::served);
        return new RiskAlertsReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.rows().stream().map(row -> new RiskAlertCountView(row.kind().name(), row.severity().name(),
                        row.status().name(), row.alerts())).toList(),
                report.rules().stream().map(row -> new RiskAlertRuleCountView(row.kind().name(), row.ruleCode(),
                        row.alerts())).toList(),
                report.census().map(RiskOperationsReportDesk::census).orElse(null));
    }

    public RiskCasesReportView cases(String rawDate) {
        RiskOperationsReports.CasesReport report = served(rawDate, RiskOperationsReports.Report.CASES,
                RiskSpans.PROOF, reports::cases, RiskOperationsReports.CasesReport::served);
        return new RiskCasesReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.opened().stream().map(row -> new RiskCaseCountView(row.kind().name(), row.status().name(),
                        row.cases())).toList(),
                report.disposed().stream().map(row -> new RiskDispositionCountView(row.kind().name(),
                        row.disposition().name(), row.cases())).toList(),
                report.open().map(RiskOperationsReportDesk::census).orElse(null),
                report.filings().map(RiskOperationsReportDesk::census).orElse(null));
    }

    public RiskVelocityReportView velocity(String rawDate) {
        RiskOperationsReports.VelocityReport report = served(rawDate, RiskOperationsReports.Report.VELOCITY,
                RiskSpans.VELOCITY_PROOF, reports::velocity, RiskOperationsReports.VelocityReport::served);
        return new RiskVelocityReportView(report.day().toString(), report.known(), report.asOf().toString(),
                report.proof().map(whole -> whole.record().asOf().toString()).orElse(null),
                report.proof().map(whole -> whole.verdict().name()).orElse(null),
                report.proof().map(whole -> link(whole.record(), report.record())).orElse(null),
                report.proof().flatMap(VelocityProof.Whole::journal).map(link -> link(link, report.journal()))
                        .orElse(null),
                report.withheld(),
                report.monitoring().map(RiskOperationsReportDesk::census).orElse(null));
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Read<R> {
        R in(java.sql.Connection snapshot, RiskDay day, Actor reader);
    }

    /**
     * The date judged, the report read for the placed reader on one bounded snapshot, recorded in a transaction of its
     * own, then answered; an unplaceable reader refused and recorded {@code FAILED} in a transaction of its own.
     */
    private <R> R served(String rawDate, RiskOperationsReports.Report which, String span, Read<R> read,
            Function<R, RiskOperationsReports.Served> served) {
        RiskDay day = RiskReportDates.day(rawDate, "A risk report");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk report runs inside a correlation scope"))
                .correlationId();
        R report;
        try {
            report = flow.door(span, () -> transactions.inSnapshot(snapshot -> {
                RiskProofs.bound(snapshot, statementTimeout);
                return read.in(snapshot, day, actor);
            }));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(unitOfWork -> {
                reports.recordRefusal(unitOfWork, which, refused, actor, correlation);
                return null;
            });
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk report was refused",
                    "a holder the platform cannot place in a party reads no report; nothing was served.");
        }
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
                        operation.orphaned().stream().map(RiskProofReads.Assessed::commandRef).map(CommandRef::value)
                                .toList(), operation.orphanedCount()))
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

    /** {@code result}'s figures, its divergences as the reports located them - the shown ones, bounded. */
    private static RiskVelocityLinkView link(VelocityProof.Result result, List<RiskOperationsReports.Located> located) {
        return new RiskVelocityLinkView(result.verdict().name(), result.asOf().toString(),
                result.evidenceThrough().toString(), result.judged(), result.pending(), result.unprovable(),
                result.explained(), result.beforeFloor(), result.divergences().size(), located.stream()
                        .map(shown -> {
                            VelocityProof.Divergence divergence = shown.divergence();
                            return new RiskVelocityDivergenceView(divergence.key().dimension().name(),
                                    shown.subjectRef(), divergence.key().windowKind().name(),
                                    divergence.key().windowStart().toString(), divergence.currency().code(),
                                    divergence.scale(), divergence.recordedCount(), divergence.recordedSumMinor(),
                                    divergence.fromCount(), divergence.toCount(), divergence.fromSumMinor(),
                                    divergence.toSumMinor());
                        })
                        .toList());
    }
}
