package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Risk's operations reports and the proofs' one reading (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3, 15;
 * {@code DESIGN-023} D8, D10; bnpl's {@code BnplReports} shape) - <strong>decisions</strong>, <strong>alerts</strong>,
 * <strong>cases</strong> and <strong>velocity</strong> for one UTC day, each read on ONE read-only
 * {@code REPEATABLE READ} snapshot with the census it carries, so a report's rows and its census agree whatever commits
 * beside it. Counts and codes; money only as exact minor units grouped by currency (a bucketed sum, never one movement);
 * never a subject, a person, a note or a score - an identifier only where a proof names a difference (at most
 * {@link RiskProofs#NAMED} a list). A day before {@link DailyReplay#EARLIEST} or after the database's today is
 * {@code known=false}: nothing read, still recorded. {@link #record} appends {@code risk.ReportRead}, which the caller
 * commits in a transaction of its own before the report is answered. Nothing else is written - a report names, a person
 * acts.
 */
public final class RiskOperationsReports {

    /** The audit target type of a report - its id the report's name. */
    public static final String TARGET_TYPE = "risk_report";

    /** The four reports. */
    public enum Report {
        DECISIONS("decisions"), ALERTS("alerts"), CASES("cases"), VELOCITY("velocity");

        private final String path;

        Report(String path) {
            this.path = path;
        }

        /** The report's name - its route's last segment and its audit target id. */
        public String path() {
            return path;
        }
    }

    /** What the audit record of a serving says: the report, its date, whether known, its rows and its verdict. */
    public record Served(Report report, RiskDay day, boolean known, long rows, ProofVerdict verdict) {
        public Served {
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(verdict, "verdict");
        }
    }

    public record DecisionsReport(RiskDay day, boolean known, Instant asOf, List<RiskProofReads.DecisionRow> rows,
            List<RiskProofReads.ReasonRow> reasons, List<RiskProofReads.VersionRow> versions,
            Optional<AssessmentCensus.Result> census) {
        public DecisionsReport {
            rows = List.copyOf(rows);
            reasons = List.copyOf(reasons);
            versions = List.copyOf(versions);
        }

        public Served served() {
            return new Served(Report.DECISIONS, day, known, rows.size(),
                    census.map(AssessmentCensus.Result::verdict).orElse(ProofVerdict.VACUOUS));
        }
    }

    public record AlertsReport(RiskDay day, boolean known, Instant asOf, List<RiskProofReads.AlertRow> rows,
            List<RiskProofReads.RuleRow> rules, Optional<AlertBornOnceCensus.Result> census) {
        public AlertsReport {
            rows = List.copyOf(rows);
            rules = List.copyOf(rules);
        }

        public Served served() {
            return new Served(Report.ALERTS, day, known, rows.size(),
                    census.map(AlertBornOnceCensus.Result::verdict).orElse(ProofVerdict.VACUOUS));
        }
    }

    public record CasesReport(RiskDay day, boolean known, Instant asOf, List<RiskProofReads.CaseRow> opened,
            List<RiskProofReads.DisposedRow> disposed, Optional<CaseOneOpenCensus.Result> open,
            Optional<FilingCensus.Result> filings) {
        public CasesReport {
            opened = List.copyOf(opened);
            disposed = List.copyOf(disposed);
        }

        public Served served() {
            ProofVerdict verdict = open.map(CaseOneOpenCensus.Result::verdict).orElse(ProofVerdict.VACUOUS)
                    .and(filings.map(FilingCensus.Result::verdict).orElse(ProofVerdict.VACUOUS));
            return new Served(Report.CASES, day, known, opened.size() + disposed.size(), verdict);
        }
    }

    public record VelocityReport(RiskDay day, boolean known, Instant asOf, Optional<VelocityProof.Whole> proof,
            Optional<MonitoringCensus.Result> monitoring) {
        public Served served() {
            ProofVerdict verdict = proof.map(whole -> ProofVerdict.of(whole.verdict())).orElse(ProofVerdict.VACUOUS)
                    .and(monitoring.map(MonitoringCensus.Result::verdict).orElse(ProofVerdict.VACUOUS));
            return new Served(Report.VELOCITY, day, known, proof.map(VelocityProof.Whole::diverged).orElse(0), verdict);
        }
    }

    /** The proofs' one reading for the meters: today's and yesterday's censuses and the velocity proof, one snapshot. */
    public record Reading(Instant asOf, VelocityProof.Verdict velocity, Map<CensusKind, ProofVerdict> censuses) {
        public Reading {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(velocity, "velocity");
            censuses = Map.copyOf(censuses);
        }
    }

    private final RiskProofReads reads;
    private final AssessmentCensus assessments;
    private final MonitoringCensus monitoring;
    private final AlertBornOnceCensus alerts;
    private final CaseOneOpenCensus cases;
    private final FilingCensus filings;
    private final VelocityProof velocity;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskOperationsReports(RiskProofReads reads, AssessmentCensus assessments, MonitoringCensus monitoring,
            AlertBornOnceCensus alerts, CaseOneOpenCensus cases, FilingCensus filings, VelocityProof velocity,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.filings = Objects.requireNonNull(filings, "filings");
        this.velocity = Objects.requireNonNull(velocity, "velocity");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The day's decisions: per operation, decision, fail-safe, challenge, velocity and currency; reasons; versions. */
    public DecisionsReport decisions(Connection snapshot, RiskDay day) {
        Instant asOf = asOf(snapshot);
        if (!known(day, asOf)) {
            return new DecisionsReport(day, false, asOf, List.of(), List.of(), List.of(), Optional.empty());
        }
        return new DecisionsReport(day, true, asOf, reads.decisions(snapshot, day), reads.reasons(snapshot, day),
                reads.versions(snapshot, day), Optional.of(assessments.run(snapshot, day)));
    }

    /** The day's alerts by kind, severity and status, by rule, and the born-once census. */
    public AlertsReport alerts(Connection snapshot, RiskDay day) {
        Instant asOf = asOf(snapshot);
        if (!known(day, asOf)) {
            return new AlertsReport(day, false, asOf, List.of(), List.of(), Optional.empty());
        }
        return new AlertsReport(day, true, asOf, reads.alerts(snapshot, day), reads.alertRules(snapshot, day),
                Optional.of(alerts.run(snapshot, day)));
    }

    /** The day's cases opened and disposed, and - at the snapshot - the one-open and the filing censuses. */
    public CasesReport cases(Connection snapshot, RiskDay day) {
        Instant asOf = asOf(snapshot);
        if (!known(day, asOf)) {
            return new CasesReport(day, false, asOf, List.of(), List.of(), Optional.empty(), Optional.empty());
        }
        return new CasesReport(day, true, asOf, reads.casesOpened(snapshot, day), reads.casesDisposed(snapshot, day),
                Optional.of(cases.run(snapshot)), Optional.of(filings.run(snapshot)));
    }

    /** The velocity proof made whole at the snapshot, and the day's monitoring census. */
    public VelocityReport velocity(Connection snapshot, RiskDay day) {
        Instant asOf = asOf(snapshot);
        if (!known(day, asOf)) {
            return new VelocityReport(day, false, asOf, Optional.empty(), Optional.empty());
        }
        return new VelocityReport(day, true, asOf, Optional.of(velocity.prove(snapshot)),
                Optional.of(monitoring.run(snapshot, day)));
    }

    /**
     * The proofs' reading for the meters, on {@code snapshot}: the velocity proof made whole, and each census over today
     * and yesterday (UTC, the database's clock) - the worse verdict of the two days.
     */
    public Reading reading(Connection snapshot) {
        Instant asOf = asOf(snapshot);
        RiskDay today = RiskDay.containing(asOf);
        RiskDay yesterday = today.previous();
        Map<CensusKind, ProofVerdict> censuses = new EnumMap<>(CensusKind.class);
        censuses.put(CensusKind.ASSESSMENT,
                assessments.run(snapshot, yesterday).verdict().and(assessments.run(snapshot, today).verdict()));
        censuses.put(CensusKind.MONITORING,
                monitoring.run(snapshot, yesterday).verdict().and(monitoring.run(snapshot, today).verdict()));
        censuses.put(CensusKind.ALERT_BORN_ONCE,
                alerts.run(snapshot, yesterday).verdict().and(alerts.run(snapshot, today).verdict()));
        censuses.put(CensusKind.CASE_ONE_OPEN, cases.run(snapshot).verdict());
        censuses.put(CensusKind.FILING, filings.run(snapshot).verdict());
        return new Reading(asOf, velocity.prove(snapshot).verdict(), censuses);
    }

    /** Records {@code served} read by {@code actor} - {@code risk.ReportRead}; counts and codes only. */
    public void record(Connection unitOfWork, Served served, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(served, "served");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.REPORT_READ, TARGET_TYPE, served.report().path(), Optional.empty(),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("report=" + served.report().path() + ", date="
                        + served.day() + ", known=" + served.known() + ", rows=" + served.rows() + ", verdict="
                        + served.verdict().name())));
    }

    private Instant asOf(Connection snapshot) {
        RiskProofs.requireSnapshot(snapshot);
        return reads.databaseNow(snapshot);
    }

    private static boolean known(RiskDay day, Instant asOf) {
        return !day.date().isBefore(DailyReplay.EARLIEST) && !day.start().isAfter(asOf);
    }
}
