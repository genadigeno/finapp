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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Risk's operations reports and the proofs' one reading (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 13.3, 15;
 * {@code DESIGN-023} D8, D10; bnpl's {@code BnplReports} shape) - <strong>decisions</strong>, <strong>alerts</strong>,
 * <strong>cases</strong> and <strong>velocity</strong> for one UTC day, each read on ONE read-only
 * {@code REPEATABLE READ} snapshot with the census it carries, so a report's rows and its census agree whatever commits
 * beside it. Counts and codes; money only as exact minor units grouped by currency - a group's sum, which for a group of
 * one assessment is that assessment's amount: an operator's read, never a customer's. A day before
 * {@link DailyReplay#EARLIEST} or after the database's today is {@code known=false}: nothing read, still recorded.
 * {@link #record} appends {@code risk.ReportRead}, which the caller commits in a transaction of its own before the report
 * is answered. Nothing else is written - a report names, a person acts.
 *
 * <h2>The reader, placed - and what is withheld from them (the review's M10, I2)</h2>
 *
 * <p>Every report is read by a person the platform can place in a party - fail closed, the day replay's and the queues'
 * rule ({@link RiskAssessments.SelfDealingRefused}, recorded {@code FAILED} by the caller). Then:
 *
 * <ul>
 *   <li>a velocity divergence is <strong>located</strong> by its window's subject - the dimension, the subject reference,
 *       the window kind and start - unless the subject is the reader's own party's or cannot be placed (a device, a
 *       beneficiary): withheld and counted, the queues' rule for a subject. Each subject is placed once a request
 *       (memoised) and at most {@link #PLACED} subjects are placed - a divergence past that bound is withheld, counted -
 *       so a report's statements are the proof's fixed set plus a bounded number of placements (the review's second
 *       round); the divergences are examined in order until {@link RiskProofs#NAMED} are shown, the rest counted in the
 *       link's total;</li>
 *   <li>an orphaned assessment's command reference is withheld when the assessment judged the reader's own party; the
 *       other references a census names - a judged command missing its seal, a journal entry or a monitored row - name
 *       defects by id, their content readable only through the doors that judge self-dealing.</li>
 * </ul>
 * Names are bounded ({@link RiskProofs#NAMED} a list); the counts are always whole.
 */
public final class RiskOperationsReports {

    /** The audit target type of a report - its id the report's name. */
    public static final String TARGET_TYPE = "risk_report";

    private static final String ACT = "report-read";

    /** The most subjects one velocity report places - an account's or a merchant's party a lookup each. */
    public static final int PLACED = 50;

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
            Optional<AssessmentCensus.Result> census, long withheld) {
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

    /** One divergence the reader may locate: the window, and its subject's reference. */
    public record Located(VelocityProof.Divergence divergence, String subjectRef) {
        public Located {
            Objects.requireNonNull(divergence, "divergence");
            Objects.requireNonNull(subjectRef, "subjectRef");
        }

        @Override
        public String toString() {
            return "Located[" + divergence + "]";
        }
    }

    /**
     * The velocity report: the proof made whole <em>as of the snapshot</em> ({@code proof}'s own {@code asOf} - the
     * windows live now, whatever the date asked: the review's M6), its divergences located per link, those withheld
     * counted; and the date's monitoring census.
     */
    public record VelocityReport(RiskDay day, boolean known, Instant asOf, Optional<VelocityProof.Whole> proof,
            List<Located> record, List<Located> journal, long withheld, Optional<MonitoringCensus.Result> monitoring) {
        public VelocityReport {
            record = List.copyOf(record);
            journal = List.copyOf(journal);
        }

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
    private final RescreeningCensus rescreenings;
    private final VelocityProof velocity;
    private final RiskActingParty actingParties;
    private final RiskSubjectParties subjectParties;
    private final MonitoredParties parties;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskOperationsReports(RiskProofReads reads, AssessmentCensus assessments, MonitoringCensus monitoring,
            AlertBornOnceCensus alerts, CaseOneOpenCensus cases, FilingCensus filings, RescreeningCensus rescreenings,
            VelocityProof velocity, RiskActingParty actingParties, RiskSubjectParties subjectParties, MonitoredParties parties,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.filings = Objects.requireNonNull(filings, "filings");
        this.rescreenings = Objects.requireNonNull(rescreenings, "rescreenings");
        this.velocity = Objects.requireNonNull(velocity, "velocity");
        this.actingParties = Objects.requireNonNull(actingParties, "actingParties");
        this.subjectParties = Objects.requireNonNull(subjectParties, "subjectParties");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The day's decisions for {@code reader}: per operation, decision, fail-safe, challenge, velocity and currency;
     * reasons; versions; the assessment census, an orphan of the reader's own party withheld.
     *
     * @throws RiskAssessments.SelfDealingRefused the reader is placed in no party (fail closed)
     */
    public DecisionsReport decisions(Connection snapshot, RiskDay day, Actor reader) {
        Instant asOf = asOf(snapshot);
        UUID own = Queues.reader(snapshot, actingParties, reader, ACT);
        if (!known(day, asOf)) {
            return new DecisionsReport(day, false, asOf, List.of(), List.of(), List.of(), Optional.empty(), 0);
        }
        AssessmentCensus.Result census = assessments.run(snapshot, day);
        List<AssessmentCensus.Operation> shown = new ArrayList<>();
        long withheld = 0;
        for (AssessmentCensus.Operation operation : census.operations()) {
            List<RiskProofReads.Assessed> orphans = new ArrayList<>();
            for (RiskProofReads.Assessed orphan : operation.orphaned()) {
                if (orphan.party().equals(own)) {
                    withheld++;
                } else {
                    orphans.add(orphan);
                }
            }
            shown.add(new AssessmentCensus.Operation(operation.operation(), operation.assessments(),
                    operation.commands(), operation.matched(), operation.reconciled(), operation.unrecordable(),
                    operation.missing(), operation.missingCount(), orphans, operation.orphanedCount()));
        }
        return new DecisionsReport(day, true, asOf, reads.decisions(snapshot, day), reads.reasons(snapshot, day),
                reads.versions(snapshot, day), Optional.of(new AssessmentCensus.Result(day, shown)), withheld);
    }

    /** The day's alerts by kind, severity and status, by rule, and the born-once census - for a placed reader. */
    public AlertsReport alerts(Connection snapshot, RiskDay day, Actor reader) {
        Instant asOf = asOf(snapshot);
        Queues.reader(snapshot, actingParties, reader, ACT);
        if (!known(day, asOf)) {
            return new AlertsReport(day, false, asOf, List.of(), List.of(), Optional.empty());
        }
        return new AlertsReport(day, true, asOf, reads.alerts(snapshot, day), reads.alertRules(snapshot, day),
                Optional.of(alerts.run(snapshot, day)));
    }

    /** The day's cases opened and disposed, and - at the snapshot - the one-open and filing censuses; a placed reader. */
    public CasesReport cases(Connection snapshot, RiskDay day, Actor reader) {
        Instant asOf = asOf(snapshot);
        Queues.reader(snapshot, actingParties, reader, ACT);
        if (!known(day, asOf)) {
            return new CasesReport(day, false, asOf, List.of(), List.of(), Optional.empty(), Optional.empty());
        }
        return new CasesReport(day, true, asOf, reads.casesOpened(snapshot, day), reads.casesDisposed(snapshot, day),
                Optional.of(cases.run(snapshot)), Optional.of(filings.run(snapshot)));
    }

    /**
     * The velocity proof made whole at the snapshot, its divergences located for {@code reader} - the subject withheld
     * when it is the reader's own party's or cannot be placed - and the day's monitoring census.
     */
    public VelocityReport velocity(Connection snapshot, RiskDay day, Actor reader) {
        Instant asOf = asOf(snapshot);
        UUID own = Queues.reader(snapshot, actingParties, reader, ACT);
        if (!known(day, asOf)) {
            return new VelocityReport(day, false, asOf, Optional.empty(), List.of(), List.of(), 0, Optional.empty());
        }
        VelocityProof.Whole whole = velocity.prove(snapshot);
        long[] withheld = {0};
        Placements placements = new Placements(snapshot);
        List<Located> record = locate(placements, whole.record().divergences(), own, withheld);
        List<Located> journal = whole.journal().map(link -> locate(placements, link.divergences(), own, withheld))
                .orElse(List.of());
        return new VelocityReport(day, true, asOf, Optional.of(whole), record, journal, withheld[0],
                Optional.of(monitoring.run(snapshot, day)));
    }

    /**
     * The proofs' reading for the meters, on {@code snapshot}: the velocity proof made whole, and each census over today
     * and yesterday (UTC, the database's clock) - the worse verdict of the two days; the one-open, filing and
     * rescreening censuses at the snapshot (the last over the list versions in force or retired since yesterday's
     * start, `P13-TSK-024`).
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
        // P13-TSK-024 (-022's routed row): the versions in force or retired since yesterday's start, on this snapshot.
        censuses.put(CensusKind.RESCREENING, rescreenings.run(snapshot).verdict());
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

    /** Records a refused report {@code FAILED} ({@code risk.SelfDealingRefused}) - a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, Report report, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                RiskAuditAction.SELF_DEALING_REFUSED, TARGET_TYPE, report.path(), Optional.empty(), AuditOutcome.FAILED,
                correlation, Optional.of("act=" + refused.act() + ", cause=" + refused.refusal().name() + " refused")));
    }

    /**
     * {@code divergences} located for the reader of party {@code own}, examined in order until {@link RiskProofs#NAMED}
     * are shown: one of the reader's own party, one that cannot be placed and one past the placement bound withheld,
     * counted.
     */
    private List<Located> locate(Placements placements, List<VelocityProof.Divergence> divergences, UUID own,
            long[] withheld) {
        List<Located> shown = new ArrayList<>();
        for (VelocityProof.Divergence divergence : divergences) {
            if (shown.size() >= RiskProofs.NAMED) {
                break;
            }
            Optional<UUID> party = placements.partyOf(divergence.key());
            if (party.isEmpty() || party.get().equals(own)) {
                withheld[0]++;
            } else {
                shown.add(new Located(divergence, divergence.key().subjectRef()));
            }
        }
        return shown;
    }

    /** One request's placements: each subject placed once, at most {@link #PLACED} of them - past it, unplaced. */
    private final class Placements {
        private final Connection snapshot;
        private final Map<String, Optional<UUID>> placed = new HashMap<>();

        Placements(Connection snapshot) {
            this.snapshot = snapshot;
        }

        Optional<UUID> partyOf(VelocityWindowKey key) {
            String subject = key.dimension().name() + ':' + key.subjectRef();
            Optional<UUID> known = placed.get(subject);
            if (known != null) {
                return known;
            }
            if (placed.size() >= PLACED) {
                return Optional.empty();
            }
            Optional<UUID> party = RiskOperationsReports.this.partyOf(snapshot, key);
            placed.put(subject, party);
            return party;
        }
    }

    /** The party accountable for a window's subject - empty when this build cannot place it (fail closed). */
    private Optional<UUID> partyOf(Connection snapshot, VelocityWindowKey key) {
        String ref = key.subjectRef();
        return switch (key.dimension()) {
            case PARTY -> uuid(ref);
            case SOURCE_ACCOUNT, DESTINATION -> uuid(ref).flatMap(account -> parties.partyOfAccount(snapshot, account));
            case MERCHANT -> subjectParties.accountablePartyOf(snapshot, new SignalSubject(SubjectKind.MERCHANT, ref));
            case DEVICE, SESSION -> Optional.empty();
        };
    }

    private static Optional<UUID> uuid(String ref) {
        try {
            return Optional.of(UUID.fromString(ref));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private Instant asOf(Connection snapshot) {
        RiskProofs.requireSnapshot(snapshot);
        return reads.databaseNow(snapshot);
    }

    private static boolean known(RiskDay day, Instant asOf) {
        return !day.date().isBefore(DailyReplay.EARLIEST) && !day.start().isAfter(asOf);
    }
}
