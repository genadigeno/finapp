package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The close run's judges (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 8.1 T-check, 13.5; {@code INV-ACC-05},
 * {@code INV-ACC-09}): one claimed attempt to one {@link CheckVerdict}, each read through its port, {@code REPEATABLE
 * READ READ ONLY}, and writing nothing but the breaks {@link AccountingChecks} raises. Called <strong>outside any
 * transaction</strong> (its contract); {@code OPEN_ACCOUNTING_BREAKS} is judged in its own conclusion instead ({@link
 * CloseRuns#concludeOpenBreaks}).
 *
 * <p><strong>No false pass.</strong> {@code PASSED} only when the judge read everything it covers and found nothing. An
 * exception, a timeout, an incomplete proof, a reading missing, a verdict member absent: {@code FAILED} with its
 * reason, the exception's class logged (never its message, which may carry data).
 *
 * <p><strong>{@code DISCLOSED}</strong> only as {@link CloseCheckCode#disclosable()} admits: a raising check every one
 * of whose findings a person decided on and that stands unchanged - an {@code ACKNOWLEDGED} break's by its disclosure, a {@code RESOLVED}
 * one's by its correcting entry (the owner's ruling, ADR-0117, `P14-TSK-012`; {@link CheckOutcome#FINDING_CHANGED} otherwise, its next
 * generation raised) - and nothing else failing; {@code ACCRUALS_COMPLETE} with nothing behind and entries found past the cutoff; {@code
 * RECONCILIATION_DISCLOSURE} with its month run, read whole and not clean. A person's reason per {@code DISCLOSED} attempt is the proposal's
 * ({@code INV-ACC-09}, {@link PeriodCloses}).
 *
 * <p><strong>{@code RECONCILIATION_DISCLOSURE} and a month's run</strong> (the owner's rulings of 2026-11-02 and 2026-11-03): a month's run is a {@code
 * COMPLETED} {@code BATCH} run with a business date in the month, of a batch not repudiated, <strong>for each source the month requires</strong>; a run in another status
 * counts for nothing, a {@code REPROCESS} alone counts for nothing, and a repudiated batch's run counts for nothing, its evidence withdrawn. <strong>A month requires a source</strong>
 * (`P14-TSK-025`, the owner's rulings of 2026-11-06; {@link ReconciliationRunCoverage.Source#requiredIn}) that this build declares, admitted by the month's last day, not retired
 * before its first, and whose rule set stood active by its last day: the active sources of the owner's (e), narrowed by the dates of (c) and the rule set of (k). <strong>A month
 * with no activity gets its run from the counterparty's nil report</strong>: a file with no lines through the existing door - pulled, the channel its authentication, or uploaded
 * and attested by a second person - accepted with no posting, its run going {@code OPEN -> COMPLETED} at no items. Runs are born only in that acceptance's transaction, from a
 * stored file, so the platform cannot mint one to satisfy this check. A source retired before the month's first day is not required, its door being shut; one retired within
 * or after the month served it and is. Any source's unfinished or repudiated runs are disclosed, never ignored.
 *
 * <p><strong>{@code VALUATION_SET_APPROVED} waits</strong> (`P14-TSK-011`'s review, M-6): a set is captured only after the lock, so it would be {@code FAILED} on every first attempt
 * and {@code AccountingCloseCheckFailed} would fire at every close. So with no approved set the judge answers "not yet", the attempt deferred and re-judged until one stands, taking no
 * new lock - and no person's re-run and no attempt born by the approval, which would leave the first {@code FAILED} and race an attempt in flight. {@code CLOSE_STATUS} names the
 * proposals that await a decision.
 *
 * <p><strong>{@code ACCRUALS_COMPLETE} after the lock</strong> (`P14-TSK-006`'s review, I-1): a catch-up after the lock
 * floors the period's accrual dates to the first open date, and {@link AccrualCompleteness#blocking} then reads empty
 * while the period is short of that interest. So both legs are read on <em>one snapshot</em>: an accrual either has not
 * committed (behind: {@code FAILED}) or has (past the cutoff: {@code DISCLOSED}, with the figures) - no third state, and
 * never {@code PASSED} on a period short of interest. Not judged inside T-lock: that would pause every posting for an
 * O(servicing) read and hold the ledger's protection hostage to one stuck loan. <strong>Bnpl has no floor and the same
 * shortfall</strong> (`P14-TSK-011`'s review, C-1): {@code BNPL-F2a} posts today valued the accrual date, so a lagging sweep
 * is read by the same leg, and the month's last day posts after the month by the rule itself. So a period with an active
 * pay-in-N obligation reads {@code DISCLOSED}; the evidence keeps {@code lastDay} apart from {@code older}, the lag.
 */
@Slf4j
@RequiredArgsConstructor
public final class CloseChecks {

    /** The prefix of a read that failed - {@link CheckOutcome#UNAVAILABLE}'s, one vocabulary. */
    public static final String UNAVAILABLE = CheckOutcome.UNAVAILABLE;

    /** The FINAL trial balance's verdict or a leg is not {@code true}. */
    public static final String TRIAL_BALANCE_FALSE = "TRIAL_BALANCE_FALSE";

    /** The period's lock pinned no GL mapping version. */
    public static final String NO_PINNED_VERSION = "NO_PINNED_VERSION";

    /** The FINAL trial balance holds an unmapped line. */
    public static final String NOT_FULLY_MAPPED = "NOT_FULLY_MAPPED";

    /** An accruer has not reached the period's end. */
    public static final String ACCRUALS_BEHIND = "ACCRUALS_BEHIND";

    /** A projection differs from its derivation. */
    public static final String PROJECTION_DRIFT = "PROJECTION_DRIFT";

    /** The projection reading could not judge everything - an account in flight. */
    public static final String PROJECTION_INCOMPLETE = "PROJECTION_INCOMPLETE";

    /** A source the month requires has no {@code COMPLETED} {@code BATCH} run of an unrepudiated batch in the month, or the month requires no source at all. */
    public static final String MONTH_NOT_RUN = "MONTH_NOT_RUN";

    /** The approved set holds no rate. */
    public static final String EMPTY_APPROVED_SET = "EMPTY_APPROVED_SET";

    static final String ACCRUALS_JUDGED_BY = "accrual-completeness:v1";
    static final String PROJECTION_JUDGED_BY = "source-proofs:v1";

    /** At most this many drifting subjects are named; the count is always whole. */
    static final int MAX_SUBJECTS = 20;

    /** The three break-raising checks' callable - {@link AccountingChecks#run}. */
    @FunctionalInterface
    public interface BreakChecks {
        CheckOutcome run(YearMonth period, CloseCheckCode check, Actor actor, CorrelationId correlation);
    }

    @NonNull private final BreakChecks breakChecks;
    @NonNull private final FinalTrialBalance trialBalance;
    @NonNull private final AccrualCompleteness accruals;
    @NonNull private final SourceProofs proofs;
    @NonNull private final ReconciliationDisclosure disclosure;
    @NonNull private final ReconciliationRunCoverage coverage;
    @NonNull private final ValuationRateSets valuationSets;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final AccountingChecks.Snapshots snapshots;

    /**
     * The attempt's verdict.
     *
     * @return empty when the judge cannot answer <em>yet</em> (a report run another worker generates): the caller
     * defers
     * @throws IllegalArgumentException for {@code OPEN_ACCOUNTING_BREAKS}, judged in its conclusion
     */
    public Optional<CheckVerdict> judge(CloseRuns.ClaimedCheck claimed) {
        Objects.requireNonNull(claimed, "claimed");
        Actor actor = claimed.actor();
        CloseCheckCode check = claimed.check();
        if (check == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
            throw new IllegalArgumentException("OPEN_ACCOUNTING_BREAKS is judged in its conclusion");
        }
        try {
            return switch (check) {
                case LEDGER_TRIAL_BALANCE -> ledgerTrialBalance(claimed, actor);
                case GL_EQUALS_LEDGER -> glEqualsLedger(claimed, actor);
                case RULE_CONFORMANCE, SOURCE_COMPLETENESS -> Optional.of(raising(claimed, actor));
                case ACCRUALS_COMPLETE -> Optional.of(accrualsComplete(claimed));
                case PROJECTION_VERIFIED -> Optional.of(projectionVerified(claimed));
                case RECONCILIATION_DISCLOSURE -> Optional.of(reconciliationDisclosure(claimed));
                case VALUATION_SET_APPROVED -> valuationSetApproved(claimed);
                case OPEN_ACCOUNTING_BREAKS -> throw new IllegalStateException("handled above");
            };
        } catch (RuntimeException unreadable) {
            // Never a pass: whatever could not be read fails the check. The class only - a message may carry data.
            log.warn("A close check could not be judged - it is FAILED, never passed: {} {}", check,
                    unreadable.getClass().getName());
            return Optional.of(CheckVerdict.failed(check, CloseRuns.JUDGED_BY, List.of(UNAVAILABLE + check.name()),
                    CanonicalJson.object().put("period", claimed.month().toString())));
        }
    }

    // ------------------------------------------------------------------ the trial balance

    private Optional<CheckVerdict> ledgerTrialBalance(CloseRuns.ClaimedCheck claimed, Actor actor) {
        CloseCheckCode check = claimed.check();
        FinalTrialBalance.Reading reading = trialBalance.read(claimed.period(), actor, claimed.correlation());
        return switch (reading) {
            case FinalTrialBalance.InProgress generating -> Optional.empty();
            case FinalTrialBalance.Unavailable unavailable -> Optional.of(CheckVerdict.failed(check,
                    reportJudgedBy(TrialBalanceDefinition.VERSION), List.of(unavailable.failure()), periodOf(claimed)));
            case FinalTrialBalance.Verdict verdict -> Optional.of(verdict.holds()
                    ? CheckVerdict.passed(check, reportJudgedBy(verdict.definitionVersion()), evidence(verdict))
                    : CheckVerdict.failed(check, reportJudgedBy(verdict.definitionVersion()),
                            List.of(TRIAL_BALANCE_FALSE), evidence(verdict)));
        };
    }

    /**
     * {@code GL_EQUALS_LEDGER} (`P14-TSK-009`'s review, M-6): {@code passes} is not sufficient for the close, and
     * neither is this check's own run - {@link AccountingChecks#run} falls back to the version in force for a period
     * that pins none, so the pin is read here, and the FINAL trial balance's {@code fullyMapped} beside it. Never
     * {@code DISCLOSED}: both are the close's preconditions ({@code INV-ACC-09}).
     */
    private Optional<CheckVerdict> glEqualsLedger(CloseRuns.ClaimedCheck claimed, Actor actor) {
        FinalTrialBalance.Reading reading = trialBalance.read(claimed.period(), actor, claimed.correlation());
        if (reading instanceof FinalTrialBalance.InProgress) {
            return Optional.empty();
        }
        // The run first: its breaks are the evidence even when the pin fails the check.
        CheckOutcome outcome = breakChecks.run(claimed.month(), claimed.check(), actor, claimed.correlation());
        boolean pinned = snapshots.read(snapshot -> periods.find(snapshot, claimed.period())
                .map(row -> row.pinned().isPresent()).orElse(false));
        List<String> failures = new ArrayList<>(outcome.failures());
        if (!pinned) {
            failures.add(NO_PINNED_VERSION);
        }
        CanonicalJson.Obj evidence = evidence(outcome).put("pinned", pinned);
        switch (reading) {
            case FinalTrialBalance.Verdict verdict -> {
                evidence.put("fullyMapped", verdict.fullyMapped()).put("trialBalanceRun", verdict.run());
                if (!verdict.fullyMapped()) {
                    failures.add(NOT_FULLY_MAPPED);
                }
            }
            case FinalTrialBalance.Unavailable unavailable -> failures.add(unavailable.failure());
            case FinalTrialBalance.InProgress generating -> throw new IllegalStateException("deferred above");
        }
        return Optional.of(failures.isEmpty()
                ? CheckVerdict.passed(claimed.check(), outcome.judgedBy(), evidence)
                : CheckVerdict.failed(claimed.check(), outcome.judgedBy(), failures, evidence));
    }

    // ------------------------------------------------------------------ the two other raising checks

    private CheckVerdict raising(CloseRuns.ClaimedCheck claimed, Actor actor) {
        return verdictOf(breakChecks.run(claimed.month(), claimed.check(), actor, claimed.correlation()));
    }

    /**
     * An outcome as an attempt's verdict: {@code DISCLOSED} only when the sole failure is {@link CheckOutcome#DISCREPANCIES} and every finding is one a person
     * decided on and that stands unchanged. An {@code OPEN} break awaits a decision; {@link CheckOutcome#FINDING_CHANGED}, {@code UNAVAILABLE:*}, {@code NO_MAPPING}
     * and {@code FINDINGS_CAPPED} are each another failure, so never.
     */
    static CheckVerdict verdictOf(CheckOutcome outcome) {
        CanonicalJson.Obj evidence = evidence(outcome);
        if (outcome.passed()) {
            return CheckVerdict.passed(outcome.check(), outcome.judgedBy(), evidence);
        }
        boolean allDisclosed = outcome.check().disclosable() && outcome.failures().equals(List.of(CheckOutcome.DISCREPANCIES))
                && outcome.decided() == outcome.findings();
        return allDisclosed
                ? CheckVerdict.disclosed(outcome.check(), outcome.judgedBy(), evidence)
                : CheckVerdict.failed(outcome.check(), outcome.judgedBy(), outcome.failures(), evidence);
    }

    private static CanonicalJson.Obj evidence(CheckOutcome outcome) {
        CanonicalJson.Arr changed = CanonicalJson.array();
        outcome.changed().stream().limit(MAX_SUBJECTS).forEach(changed::add);
        return CanonicalJson.object().put("findings", outcome.findings()).put("raised", outcome.raised())
                .put("knownOpen", outcome.knownOpen()).put("knownAcknowledged", outcome.knownAcknowledged())
                .put("knownResolved", outcome.knownResolved()).put("changed", outcome.changed().size())
                .put("changedSubjects", changed);
    }

    // ------------------------------------------------------------------ accruals

    private record AccrualReading(List<AccrualCompleteness.Lagging> behind, List<AccrualCompleteness.Floored> floored) {}

    private CheckVerdict accrualsComplete(CloseRuns.ClaimedCheck claimed) {
        LocalDate end = claimed.month().atEndOfMonth();
        AccrualReading reading = snapshots.read(snapshot -> new AccrualReading(accruals.blocking(snapshot, end),
                accruals.flooredPast(snapshot, end)));
        CanonicalJson.Arr behind = CanonicalJson.array();
        reading.behind().forEach(lagging -> behind.add(CanonicalJson.object().put("accruer", lagging.accruer().name())
                .put("currency", lagging.currency()).put("oldestUnaccrued", lagging.oldestUnaccrued().toString())));
        CanonicalJson.Arr floored = CanonicalJson.array();
        reading.floored().forEach(entry -> floored.add(CanonicalJson.object().put("accruer", entry.accruer().name())
                .put("currency", entry.currency()).put("scale", entry.scale()).put("entries", entry.entries())
                .put("interestMinor", entry.interestMinor().toString())
                .put("lastDay", CanonicalJson.object().put("entries", entry.lastDayEntries())
                        .put("interestMinor", entry.lastDayInterestMinor().toString()))
                .put("older", CanonicalJson.object().put("entries", entry.olderEntries())
                        .put("interestMinor", entry.olderInterestMinor().toString()))));
        CanonicalJson.Obj evidence = CanonicalJson.object().put("periodEnd", end.toString()).put("behind", behind)
                .put("flooredPastTheCutoff", floored);
        if (!reading.behind().isEmpty()) {
            return CheckVerdict.failed(claimed.check(), ACCRUALS_JUDGED_BY, List.of(ACCRUALS_BEHIND), evidence);
        }
        return reading.floored().isEmpty()
                ? CheckVerdict.passed(claimed.check(), ACCRUALS_JUDGED_BY, evidence)
                : CheckVerdict.disclosed(claimed.check(), ACCRUALS_JUDGED_BY, evidence);
    }

    // ------------------------------------------------------------------ projections

    private CheckVerdict projectionVerified(CloseRuns.ClaimedCheck claimed) {
        List<SourceProofs.Reading> readings = snapshots.read(snapshot ->
                proofs.read(snapshot, SourceProofs.Source.PROJECTION));
        Optional<SourceProofs.Reading> reading = readings.stream()
                .filter(candidate -> candidate.proof() == SourceProofs.Proof.PROJECTION).findFirst();
        if (reading.isEmpty()) {
            return CheckVerdict.failed(claimed.check(), PROJECTION_JUDGED_BY,
                    List.of(UNAVAILABLE + SourceProofs.Proof.PROJECTION.name()), periodOf(claimed));
        }
        CanonicalJson.Arr subjects = CanonicalJson.array();
        reading.get().discrepancies().stream().limit(MAX_SUBJECTS).forEach(found -> subjects.add(found.subject()));
        CanonicalJson.Obj evidence = CanonicalJson.object().put("complete", reading.get().complete())
                .put("drifting", (long) reading.get().discrepancies().size()).put("subjects", subjects);
        List<String> failures = new ArrayList<>();
        if (!reading.get().complete()) {
            failures.add(PROJECTION_INCOMPLETE);
        }
        if (!reading.get().discrepancies().isEmpty()) {
            failures.add(PROJECTION_DRIFT);
        }
        return failures.isEmpty()
                ? CheckVerdict.passed(claimed.check(), PROJECTION_JUDGED_BY, evidence)
                : CheckVerdict.failed(claimed.check(), PROJECTION_JUDGED_BY, failures, evidence);
    }

    // ------------------------------------------------------------------ the disclosure

    private record DisclosureReading(ReconciliationDisclosure.Disclosure disclosure, ReconciliationRunCoverage.Coverage coverage) {}

    /** The disclosure and the month's runs, on one snapshot: {@code FAILED} when the month did not run, otherwise {@code PASSED} only with nothing to disclose. */
    private CheckVerdict reconciliationDisclosure(CloseRuns.ClaimedCheck claimed) {
        DisclosureReading read = snapshots.read(snapshot -> new DisclosureReading(disclosure.asOfCutoff(snapshot, claimed.month()),
                coverage.ofMonth(snapshot, claimed.month())));
        MonthRuns runs = MonthRuns.judge(read.coverage());
        CanonicalJson.Obj evidence = read.disclosure().evidence().put("monthRuns", runs.evidence());
        if (!runs.ran()) {
            return CheckVerdict.failed(claimed.check(), ReconciliationDisclosure.JUDGED_BY, List.of(MONTH_NOT_RUN), evidence);
        }
        return read.disclosure().nothingToDisclose() && runs.finished()
                ? CheckVerdict.passed(claimed.check(), ReconciliationDisclosure.JUDGED_BY, evidence)
                : CheckVerdict.disclosed(claimed.check(), ReconciliationDisclosure.JUDGED_BY, evidence);
    }

    /**
     * The owner's rule over one coverage reading. The evidence is per source, never per day: a month's lines could pass the attempt's bound and fail the check by its size.
     *
     * @param ran every source the month requires has a run that counts, and the month requires at least one ({@link ReconciliationRunCoverage.Coverage#required()})
     * @param finished no run of the month stands unfinished or repudiated, whichever source's, and none names a source the register does not hold
     * @param notRun the sources the month requires with no run that counts, by code; {@link PeriodCloses} re-judges {@code ran} on it, holding the period row
     */
    record MonthRuns(boolean ran, boolean finished, List<String> notRun, CanonicalJson.Obj evidence) {

        static MonthRuns judge(ReconciliationRunCoverage.Coverage coverage) {
            CanonicalJson.Arr unrun = CanonicalJson.array();
            List<String> notRun = new ArrayList<>();
            CanonicalJson.Arr sources = CanonicalJson.array();
            List<ReconciliationRunCoverage.Source> required = coverage.required();
            boolean anyRequired = !required.isEmpty();
            boolean allRan = true;
            for (ReconciliationRunCoverage.Source source : coverage.sources()) {
                List<ReconciliationRunCoverage.Line> own = coverage.lines().stream()
                        .filter(line -> line.sourceId().equals(source.id())).toList();
                long counting = own.stream().filter(MonthRuns::counts).mapToLong(ReconciliationRunCoverage.Line::runs).sum();
                long reprocess = own.stream().filter(line -> !ReconciliationRunCoverage.Coverage.BATCH.equals(line.kind()))
                        .mapToLong(ReconciliationRunCoverage.Line::runs).sum();
                long unfinished = own.stream().filter(line -> !ReconciliationRunCoverage.Coverage.COMPLETED.equals(line.status()))
                        .mapToLong(ReconciliationRunCoverage.Line::runs).sum();
                long repudiated = own.stream().filter(ReconciliationRunCoverage.Line::repudiated)
                        .mapToLong(ReconciliationRunCoverage.Line::runs).sum();
                boolean requires = required.contains(source);
                CanonicalJson.Obj member = CanonicalJson.object().put("code", source.code()).put("state", source.state().name());
                source.putDays(member);
                sources.add(member.put("required", requires).put("completedBatchRuns", counting).put("reprocessRuns", reprocess)
                        .put("unfinishedRuns", unfinished).put("repudiatedRuns", repudiated));
                if (requires && counting == 0) {
                    allRan = false;
                    unrun.add(source.code());
                    notRun.add(source.code());
                }
            }
            long unknown = coverage.lines().stream()
                    .filter(line -> coverage.sources().stream().noneMatch(source -> source.id().equals(line.sourceId())))
                    .mapToLong(ReconciliationRunCoverage.Line::runs).sum();
            boolean finished = unknown == 0 && coverage.lines().stream().allMatch(line ->
                    ReconciliationRunCoverage.Coverage.COMPLETED.equals(line.status()) && !line.repudiated());
            return new MonthRuns(anyRequired && allRan, finished, List.copyOf(notRun), CanonicalJson.object().put("month", coverage.month().toString())
                    .put("sources", sources).put("sourcesNotRun", unrun).put("unknownSourceRuns", unknown));
        }

        /** The owner's rule: {@code BATCH}, {@code COMPLETED}, and its batch not repudiated. */
        static boolean counts(ReconciliationRunCoverage.Line line) {
            return ReconciliationRunCoverage.Coverage.BATCH.equals(line.kind())
                    && ReconciliationRunCoverage.Coverage.COMPLETED.equals(line.status()) && !line.repudiated();
        }
    }

    // ------------------------------------------------------------------ the valuation set

    private record SetReading(Optional<ValuationRateSets.Valued> approved, List<ValuationRateSetStore.SetRow> all) {}

    /**
     * The period's approved set, read afresh per attempt and pinned nowhere - whichever way the owner rules on
     * superseding an approved set (ADR-0116), the check reads what {@code approvedFor} answers then. <strong>None yet: not yet</strong>, so the attempt is
     * deferred (`P14-TSK-011`'s review, M-6, this class's note); {@code CLOSE_STATUS} names the proposals awaiting a decision (`P14-TSK-014`'s review, M-5).
     */
    private Optional<CheckVerdict> valuationSetApproved(CloseRuns.ClaimedCheck claimed) {
        SetReading reading = snapshots.read(snapshot -> new SetReading(valuationSets.approvedFor(snapshot, claimed.period()),
                valuationSets.ofPeriod(snapshot, claimed.period())));
        CanonicalJson.Arr sets = CanonicalJson.array();
        reading.all().forEach(set -> sets.add(CanonicalJson.object().put("id", set.id().value().toString())
                .put("status", set.status().name())));
        CanonicalJson.Obj evidence = CanonicalJson.object().put("sets", sets);
        if (reading.approved().isEmpty()) {
            return Optional.empty();
        }
        ValuationRateSets.Valued approved = reading.approved().get();
        String judgedBy = "valuation-set:" + approved.id().value();
        evidence.put("approved", approved.id().value().toString()).put("asOf", approved.asOf().toString())
                .put("rates", (long) approved.rates().size());
        return Optional.of(approved.rates().isEmpty()
                ? CheckVerdict.failed(claimed.check(), judgedBy, List.of(EMPTY_APPROVED_SET), evidence)
                : CheckVerdict.passed(claimed.check(), judgedBy, evidence));
    }

    // ------------------------------------------------------------------ plumbing

    private static CanonicalJson.Obj evidence(FinalTrialBalance.Verdict verdict) {
        return CanonicalJson.object().put("run", verdict.run()).put("revision", verdict.revision())
                .put("definitionVersion", verdict.definitionVersion()).put("contentSha256", verdict.contentSha256())
                .put("passes", verdict.passes()).put("openingBalanced", verdict.openingBalanced())
                .put("periodBalanced", verdict.periodBalanced()).put("balanced", verdict.balanced())
                .put("fullyMapped", verdict.fullyMapped());
    }

    private static CanonicalJson.Obj periodOf(CloseRuns.ClaimedCheck claimed) {
        return CanonicalJson.object().put("period", claimed.month().toString());
    }

    private static String reportJudgedBy(int version) {
        return "report:" + TrialBalanceDefinition.CODE + ":v" + version;
    }
}
