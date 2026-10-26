package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Every read the proofs, the censuses, the operations reports, the daily replay and the evidence door take of risk's own
 * tables (`P13-TSK-023`; {@code DESIGN-023} D1-D9; bnpl's {@code BnplReportReads} shape) - a port, so the proofs are
 * judged over fakes, and one implementation ({@link JdbcRiskProofReads}) holding every statement: {@code SELECT} only,
 * risk's schema only ({@code INV-RSK-02}), on the caller's read-only snapshot, no lock. A day is an identifier range
 * ({@link RiskDay}). Rows carry codes, counts and exact minor units with their currency - never a subject, a note or a
 * name, except the two evidence reads, which serve one row the case file holds.
 */
public interface RiskProofReads {

    /** The database's clock. */
    Instant databaseNow(Connection snapshot);

    // ------------------------------------------------------------------ assessments

    /**
     * One assessment of the day as the census reads it: the command it judged, whether it permitted it, and the party it
     * judged - so a report withholds a reference of its reader's own party (`P13-TSK-023`, the review's M10).
     */
    record Assessed(CommandRef commandRef, boolean permits, UUID party) {
        public Assessed {
            Objects.requireNonNull(commandRef, "commandRef");
            Objects.requireNonNull(party, "party");
        }

        @Override
        public String toString() {
            return "Assessed[" + commandRef + "]";
        }
    }

    /** The assessments of {@code operation} minted on {@code day}. */
    List<Assessed> assessed(Connection snapshot, RiskOperation operation, RiskDay day);

    /** Of {@code refs}, those {@code operation} holds an assessment for - whatever its day ({@code UNIQUE} probes). */
    Set<CommandRef> assessedAmong(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs);

    /** At most {@code limit} whole assessments minted on {@code day}, ascending by id, after {@code after}. */
    List<RiskAssessment> assessmentsOfDay(Connection snapshot, RiskDay day, Optional<RiskAssessmentId> after, int limit);

    /**
     * The day's assessments grouped: the operation, the decision, the fail-safe's cause, the challenge's answer, what
     * the windows did, the judged currency and scale - how many, and their judged sum in minor units.
     */
    record DecisionRow(RiskOperation operation, RiskDecision decision, Optional<UnavailableCause> failSafe,
            Optional<Boolean> challengeSatisfied, VelocityOutcome velocity, CurrencyCode currency, int scale,
            long assessments, long judgedMinor) {
        public DecisionRow {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(failSafe, "failSafe");
            Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(currency, "currency");
        }
    }

    List<DecisionRow> decisions(Connection snapshot, RiskDay day);

    /** How many of the day's assessments of an operation carry a reason code. */
    record ReasonRow(RiskOperation operation, ReasonCode reason, long assessments) {}

    List<ReasonRow> reasons(Connection snapshot, RiskDay day);

    /** How many of the day's assessments of an operation pinned a version number - none for an unpinned fail-safe. */
    record VersionRow(RiskOperation operation, OptionalInt version, long assessments) {}

    List<VersionRow> versions(Connection snapshot, RiskDay day);

    // ------------------------------------------------------------------ alerts

    /** The day's alerts by kind, severity and status. */
    record AlertRow(AlertKind kind, AlertSeverity severity, AlertStatus status, long alerts) {}

    List<AlertRow> alerts(Connection snapshot, RiskDay day);

    /** The day's alerts by kind and rule code (a rule kind's name, or an operational kind's own). */
    record RuleRow(AlertKind kind, String ruleCode, long alerts) {}

    List<RuleRow> alertRules(Connection snapshot, RiskDay day);

    /**
     * The born-once census's row for one kind and window kind (`-020`'s census of AML alerts per window among them): the
     * alerts raised that day, the distinct (subject, rule, window) keys among them, and every row any day holds of those
     * keys - more rows than keys is an alert born twice.
     */
    record BornOnceRow(AlertKind kind, WindowKind window, long raised, long keys, long rows) {}

    List<BornOnceRow> bornOnce(Connection snapshot, RiskDay day);

    // ------------------------------------------------------------------ cases and filings

    /** The cases opened on the day by kind and their status now. */
    record CaseRow(CaseKind kind, CaseStatus status, long cases) {}

    List<CaseRow> casesOpened(Connection snapshot, RiskDay day);

    /** The cases disposed on the day by kind and disposition. */
    record DisposedRow(CaseKind kind, Disposition disposition, long cases) {}

    List<DisposedRow> casesDisposed(Connection snapshot, RiskDay day);

    /** The open cases of a kind at the snapshot: how many, over how many subjects, and how many subjects hold two or more. */
    record OpenRow(CaseKind kind, long open, long subjects, long subjectsOverOne) {}

    List<OpenRow> openCases(Connection snapshot);

    /**
     * `-021`'s routed row over {@code regulatory_report_due}: the {@code FILED} reports, those whose drive's permit has
     * come, and those whose permit came more than {@code overdueAfter} ago - a filing the drive no longer drives.
     */
    record FilingRow(long filed, long due, long overdue) {}

    FilingRow filings(Connection snapshot, Duration overdueAfter);

    /**
     * `-022`'s routed row, read by `P13-TSK-024` over {@code risk V018}'s two census indexes: one screening list version
     * in force, or retired at or after {@code retiredSince} - its list and ordinal, what its enqueue counted born
     * ({@code screening_list_version.enqueued}), its permits ({@code rescreening_permit_of_version}), those concluded
     * {@code DONE(CLEARED)} and {@code DONE(HIT)}, and the {@code SCREENING_CLEARED} / {@code SCREENING_HIT} signals
     * naming the version ({@code risk_signal_by_list_version}). Codes and counts; the version's id is an operator's fact,
     * never a party.
     */
    record RescreeningRow(ScreeningListVersionId version, ScreeningList list, int ordinal, long enqueued, long permits,
            long cleared, long hit, long clearedSignals, long hitSignals) {
        public RescreeningRow {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(list, "list");
        }
    }

    List<RescreeningRow> rescreenings(Connection snapshot, Instant retiredSince);

    /**
     * When the oldest report still {@code FILED} was filed - a filing the authority has not concluded (`P13-TSK-024`,
     * {@code finapp.risk.report.unconcluded.age}); empty when none is.
     */
    Optional<Instant> oldestUnconcludedFiling(Connection snapshot);

    // ------------------------------------------------------------------ monitoring

    /** The journal entry ids of the monitored transactions in {@code [fromInclusive, toExclusive)}, ascending. */
    List<UUID> monitoredBetween(Connection snapshot, UUID fromInclusive, UUID toExclusive);

    // ------------------------------------------------------------------ evidence

    /** The monitored transaction born from {@code journalEntryId}, if one is. */
    Optional<MonitoredTransaction> monitored(Connection snapshot, UUID journalEntryId);

    /** The signal {@code id}, if one exists. */
    Optional<RiskSignal> signal(Connection snapshot, RiskSignalId id);
}
