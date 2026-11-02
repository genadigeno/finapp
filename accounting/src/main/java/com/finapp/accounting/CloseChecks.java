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
 * The close run's judges (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 8.1 T-check, 13.5; {@code INV-ACC-05}, {@code INV-ACC-09}):
 * one claimed attempt to one {@link CheckVerdict}, each read through its port, {@code REPEATABLE READ READ ONLY}, and writing nothing but
 * the breaks {@link AccountingChecks} raises. Called <strong>outside any transaction</strong> (its contract); {@code
 * OPEN_ACCOUNTING_BREAKS} is judged in its own conclusion instead ({@link CloseRuns#concludeOpenBreaks}).
 *
 * <p><strong>No false pass.</strong> {@code PASSED} only when the judge read everything it covers and found nothing. An exception, a
 * timeout, an incomplete proof, a reading missing, a verdict member absent: {@code FAILED} with its reason, the exception's class
 * logged (never its message, which may carry data). A {@code RECURRED} outcome - a {@code RESOLVED} break's discrepancy still present - is
 * {@code FAILED} and never {@code DISCLOSED}, whatever the owner rules on re-raising (ADR-0117's open question): a re-raise would
 * make it a new {@code OPEN} break, {@code FAILED} just the same.
 *
 * <p><strong>{@code DISCLOSED}</strong> only as {@link CloseCheckCode#disclosable()} admits: a raising check every one of whose findings an
 * {@code ACKNOWLEDGED} break holds, and nothing else failing; {@code ACCRUALS_COMPLETE} with nothing behind and floored entries found;
 * {@code RECONCILIATION_DISCLOSURE} read whole and not clean.
 *
 * <p><strong>{@code ACCRUALS_COMPLETE} after the lock</strong> (`P14-TSK-006`'s review, I-1): a catch-up after the lock floors the period's
 * accrual dates to the first open date, and {@link AccrualCompleteness#blocking} then reads empty while the period is short of that
 * interest. So both legs are read on <em>one snapshot</em>: an accrual either has not committed (behind: {@code FAILED}) or has
 * (floored: {@code DISCLOSED}, with the figures) - no third state, and never {@code PASSED} on a period short of interest. Not judged inside
 * T-lock: that would pause every posting for an O(servicing) read and hold the ledger's protection hostage to one stuck loan.
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

    /** The period has no approved valuation rate set. */
    public static final String NO_APPROVED_SET = "NO_APPROVED_SET";

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
    @NonNull private final ValuationRateSets valuationSets;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final AccountingChecks.Snapshots snapshots;

    /**
     * The attempt's verdict.
     *
     * @return empty when the judge cannot answer <em>yet</em> (a report run another worker generates): the caller defers
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
                case VALUATION_SET_APPROVED -> Optional.of(valuationSetApproved(claimed));
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
     * {@code GL_EQUALS_LEDGER} (`P14-TSK-009`'s review, M-6): {@code passes} is not sufficient for the close, and neither is this
     * check's own run - {@link AccountingChecks#run} falls back to the version in force for a period that pins none, so the pin is
     * read here, and the FINAL trial balance's {@code fullyMapped} beside it. Never {@code DISCLOSED}: both are the close's
     * preconditions ({@code INV-ACC-09}).
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
     * An outcome as an attempt's verdict: {@code DISCLOSED} only when the sole failure is {@link CheckOutcome#DISCREPANCIES} and an
     * {@code ACKNOWLEDGED} break - four eyes, a disclosure - holds every finding. {@link CheckOutcome#RECURRED},
     * {@code UNAVAILABLE:*}, {@code NO_MAPPING} and {@code FINDINGS_CAPPED} are each another failure, so never.
     */
    static CheckVerdict verdictOf(CheckOutcome outcome) {
        CanonicalJson.Obj evidence = evidence(outcome);
        if (outcome.passed()) {
            return CheckVerdict.passed(outcome.check(), outcome.judgedBy(), evidence);
        }
        boolean allDisclosed = outcome.check().disclosable() && outcome.failures().equals(List.of(CheckOutcome.DISCREPANCIES))
                && outcome.knownAcknowledged() == outcome.findings();
        return allDisclosed
                ? CheckVerdict.disclosed(outcome.check(), outcome.judgedBy(), evidence)
                : CheckVerdict.failed(outcome.check(), outcome.judgedBy(), outcome.failures(), evidence);
    }

    private static CanonicalJson.Obj evidence(CheckOutcome outcome) {
        return CanonicalJson.object().put("findings", outcome.findings()).put("raised", outcome.raised())
                .put("knownOpen", outcome.knownOpen()).put("knownAcknowledged", outcome.knownAcknowledged())
                .put("recurred", outcome.knownResolved());
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
                .put("interestMinor", entry.interestMinor().toString())));
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

    private CheckVerdict reconciliationDisclosure(CloseRuns.ClaimedCheck claimed) {
        ReconciliationDisclosure.Disclosure read = snapshots.read(snapshot ->
                disclosure.asOfCutoff(snapshot, claimed.month()));
        return read.nothingToDisclose()
                ? CheckVerdict.passed(claimed.check(), ReconciliationDisclosure.JUDGED_BY, read.evidence())
                : CheckVerdict.disclosed(claimed.check(), ReconciliationDisclosure.JUDGED_BY, read.evidence());
    }

    // ------------------------------------------------------------------ the valuation set

    private record SetReading(Optional<ValuationRateSets.Valued> approved, List<ValuationRateSetStore.SetRow> all) {}

    /**
     * The period's approved set, read afresh per attempt and pinned nowhere - whichever way the owner rules on superseding an
     * approved set (ADR-0116), the check reads what {@code approvedFor} answers then. The period's sets are named in the evidence so a
     * {@code FAILED} check says which proposal awaits a decision (`P14-TSK-014`'s review, M-5).
     */
    private CheckVerdict valuationSetApproved(CloseRuns.ClaimedCheck claimed) {
        SetReading reading = snapshots.read(snapshot -> new SetReading(valuationSets.approvedFor(snapshot, claimed.period()),
                valuationSets.ofPeriod(snapshot, claimed.period())));
        CanonicalJson.Arr sets = CanonicalJson.array();
        reading.all().forEach(set -> sets.add(CanonicalJson.object().put("id", set.id().value().toString())
                .put("status", set.status().name())));
        CanonicalJson.Obj evidence = CanonicalJson.object().put("sets", sets);
        if (reading.approved().isEmpty()) {
            return CheckVerdict.failed(claimed.check(), "valuation-set:none", List.of(NO_APPROVED_SET), evidence);
        }
        ValuationRateSets.Valued approved = reading.approved().get();
        String judgedBy = "valuation-set:" + approved.id().value();
        evidence.put("approved", approved.id().value().toString()).put("asOf", approved.asOf().toString())
                .put("rates", (long) approved.rates().size());
        return approved.rates().isEmpty()
                ? CheckVerdict.failed(claimed.check(), judgedBy, List.of(EMPTY_APPROVED_SET), evidence)
                : CheckVerdict.passed(claimed.check(), judgedBy, evidence);
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
