package com.finapp.app.restore;

import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.SourceFindingKind;
import com.finapp.accounting.SourceProofs;
import com.finapp.ledger.TrialBalance;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The restore verification's judgements (`P15-TSK-030`; {@code OPERATIONS_RUNBOOK.md} section 11.35): what the platform's own
 * checks answered on a database, one verdict per check. <strong>Pure</strong>: the checks are run by {@link RestoreVerification}
 * through the application's own beans, and nothing here re-computes a figure; it only decides whether an answer is a pass.
 *
 * <p><strong>Never a false pass.</strong> A check over nothing is not a pass (an empty journal, no accounting period judged, a
 * census that walked no entry); a proof missing from the answers, a reading that could not judge everything, a source that could
 * not be read and a period the GL cannot be read for are each a failure. A detail names currencies, months, proofs, counts and ids,
 * <strong>never an amount</strong>.
 */
public final class RestoreVerdicts {

    static final String TRIAL_BALANCE = "trial balance per currency";
    static final String GL_EQUALS_LEDGER = "GL = ledger per period";
    static final String PROJECTION = "projection check";
    static final String PRODUCT_PROOFS = "every product proof";
    static final String SOURCE_CENSUS = "source-join census";

    /**
     * The checks in the order they run. {@link RestoreVerification} refuses to report a run whose verdicts are not exactly these, and
     * prints their number first, so the script counts a report against it rather than against a number of its own.
     */
    public static final List<String> CHECKS = List.of(TRIAL_BALANCE, GL_EQUALS_LEDGER, PROJECTION, PRODUCT_PROOFS, SOURCE_CENSUS);

    /** At most this many subjects are named in a detail; the count is always whole. */
    static final int MAX_NAMED = 10;

    private RestoreVerdicts() {}

    /** One check's verdict, as the report prints it. */
    record Verdict(String check, boolean passed, String detail) {

        Verdict {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(detail, "detail");
        }

        static Verdict pass(String check, String detail) {
            return new Verdict(check, true, detail);
        }

        static Verdict fail(String check, String detail) {
            return new Verdict(check, false, detail);
        }

        /** {@code PASS|<check>|<detail>}, on one line, the separator kept out of the detail. */
        String line() {
            return (passed ? "PASS" : "FAIL") + "|" + check + "|" + detail.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
        }
    }

    /** A check that threw: never a pass. The class only - a message may carry data. */
    static Verdict unreadable(String check, RuntimeException failure) {
        return Verdict.fail(check, "could not be read: " + failure.getClass().getName());
    }

    // ------------------------------------------------------------------ the trial balance

    /** The ledger's own sweep: at least one currency, and none out of balance. */
    static Verdict trialBalance(TrialBalance.Report report) {
        if (report.currenciesVerified() == 0) {
            return Verdict.fail(TRIAL_BALANCE, "no currency has a journal line: a balance over nothing proves nothing");
        }
        if (!report.outOfBalance().isEmpty()) {
            return Verdict.fail(TRIAL_BALANCE, report.outOfBalance().size() + " of " + report.currenciesVerified()
                    + " currencies out of balance: " + report.outOfBalance());
        }
        return Verdict.pass(TRIAL_BALANCE, "currencies balanced: " + report.currenciesVerified() + " of " + report.currenciesVerified());
    }

    // ------------------------------------------------------------------ GL = ledger

    /**
     * One accounting period as GL = ledger judged it: the version it was read under, or why it could not be read, and the subjects
     * of the findings {@code GlEqualsLedger} returned.
     */
    record PeriodJudgement(YearMonth month, PeriodStatus status, Optional<Integer> version, Optional<String> unreadable,
            List<String> findings) {

        PeriodJudgement {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(unreadable, "unreadable");
            findings = List.copyOf(findings);
        }

        boolean locked() {
            return status != PeriodStatus.OPEN;
        }
    }

    /**
     * Every period judged, none unreadable, none with a finding. A period past {@code OPEN} is read under the version its lock pinned
     * ({@code GlPins.forPeriod}); one that pinned none cannot be read, which fails it. A journal with entries and no period judged
     * fails: the GL was never compared with anything.
     */
    static Verdict glEqualsLedger(List<PeriodJudgement> periods, long journalEntries) {
        if (periods.isEmpty()) {
            return journalEntries == 0
                    ? Verdict.fail(GL_EQUALS_LEDGER, "no accounting period and an empty journal: nothing was compared")
                    : Verdict.fail(GL_EQUALS_LEDGER, "the journal holds " + journalEntries + " entries and no accounting period was judged");
        }
        List<String> failing = new ArrayList<>();
        for (PeriodJudgement period : periods) {
            if (period.unreadable().isPresent()) {
                failing.add(period.month() + " " + period.status() + " unreadable: " + period.unreadable().get());
            } else if (!period.findings().isEmpty()) {
                failing.add(period.month() + " " + period.status() + " v" + period.version().orElseThrow() + ": "
                        + period.findings().size() + " findings " + named(period.findings()));
            }
        }
        long locked = periods.stream().filter(PeriodJudgement::locked).count();
        if (!failing.isEmpty()) {
            return Verdict.fail(GL_EQUALS_LEDGER, failing.size() + " of " + periods.size() + " periods (" + locked + " locked) fail: "
                    + named(failing));
        }
        return Verdict.pass(GL_EQUALS_LEDGER, "periods agreeing: " + periods.size() + " (" + locked + " locked, each read by its pinned version): "
                + named(periods.stream().map(period -> period.month() + " " + period.status() + " v" + period.version().orElseThrow())
                        .toList()));
    }

    // ------------------------------------------------------------------ the proofs

    /** What one source of {@code SourceProofs} answered, or why it could not be read. */
    record SourceReading(SourceProofs.Source source, List<SourceProofs.Reading> readings, Optional<String> unreadable) {

        SourceReading {
            Objects.requireNonNull(source, "source");
            readings = List.copyOf(readings);
            Objects.requireNonNull(unreadable, "unreadable");
        }
    }

    /** The projection check: {@code ProjectionVerification}, as the close's {@code PROJECTION_VERIFIED} reads it. */
    static Set<SourceProofs.Proof> projectionProofs() {
        return EnumSet.of(SourceProofs.Proof.PROJECTION);
    }

    /** Every other proof of the catalogue, read from the enum and not from a list kept here. */
    static Set<SourceProofs.Proof> productProofs() {
        return EnumSet.complementOf(EnumSet.of(SourceProofs.Proof.PROJECTION));
    }

    /**
     * Each proof of {@code covered} answered exactly once, complete, with no discrepant subject. A proof whose source could not be read,
     * a proof no reading names and a proof named twice each fail.
     */
    static Verdict proofs(String check, Set<SourceProofs.Proof> covered, List<SourceReading> sources) {
        if (covered.isEmpty()) {
            return Verdict.fail(check, "no proof is covered: a check over nothing proves nothing");
        }
        List<String> failing = new ArrayList<>();
        List<String> held = new ArrayList<>();
        for (SourceProofs.Proof proof : covered) {
            Optional<SourceReading> source = sources.stream().filter(reading -> reading.source() == proof.source()).findFirst();
            if (source.isEmpty()) {
                failing.add(proof + " never asked");
                continue;
            }
            if (source.get().unreadable().isPresent()) {
                failing.add(proof + " unreadable: " + source.get().unreadable().get());
                continue;
            }
            List<SourceProofs.Reading> answers = source.get().readings().stream().filter(reading -> reading.proof() == proof).toList();
            if (answers.size() != 1) {
                failing.add(proof + " answered " + answers.size() + " times");
                continue;
            }
            SourceProofs.Reading answer = answers.get(0);
            if (!answer.complete()) {
                failing.add(proof + " incomplete");
            } else if (!answer.discrepancies().isEmpty()) {
                failing.add(proof + ": " + answer.discrepancies().size() + " discrepant "
                        + named(answer.discrepancies().stream().map(SourceProofs.Discrepancy::subject).toList()));
            } else {
                held.add(proof.name());
            }
        }
        if (!failing.isEmpty()) {
            return Verdict.fail(check, failing.size() + " of " + covered.size() + " proofs fail: " + named(failing));
        }
        return Verdict.pass(check, "proofs complete with no discrepant subject: " + covered.size() + " " + held);
    }

    // ------------------------------------------------------------------ the census

    /**
     * The source-join census walked from its floor over the whole journal, by its own judging code: every entry walked, and no finding
     * that was not already recorded before the backup. A walk shorter than the journal is the transaction counter behind the restored
     * entries ({@code OPERATIONS_RUNBOOK.md} section 11.35): the entries above it are invisible to the walk, which is never a pass.
     * A finding recorded before the backup is disclosed rather than refused: the census never closes one, and a restore cannot change it.
     *
     * @param found every entry the walk counted, with its kind
     * @param recorded the entries {@code accounting.source_census_finding} already held
     */
    static Verdict census(long walked, long journalEntries, Map<UUID, SourceFindingKind> found, Set<UUID> recorded) {
        if (journalEntries == 0) {
            return Verdict.fail(SOURCE_CENSUS, "an empty journal: the census judged nothing");
        }
        if (walked != journalEntries) {
            return Verdict.fail(SOURCE_CENSUS, "walked " + walked + " of the journal's " + journalEntries
                    + " entries: the transaction counter is behind the restored journal, or the walk stopped short");
        }
        Map<UUID, SourceFindingKind> unrecorded = found.entrySet().stream().filter(entry -> !recorded.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        long unsourced = unrecorded.values().stream().filter(kind -> kind == SourceFindingKind.UNSOURCED).count();
        long mismatched = unrecorded.values().stream().filter(kind -> kind == SourceFindingKind.MISMATCHED).count();
        if (!unrecorded.isEmpty()) {
            return Verdict.fail(SOURCE_CENSUS, "walked " + walked + " of " + journalEntries + " entries: " + unsourced + " unsourced and "
                    + mismatched + " mismatched that no finding recorded: " + named(unrecorded.keySet().stream().map(UUID::toString)
                            .sorted().toList()));
        }
        long known = found.size();
        return Verdict.pass(SOURCE_CENSUS, "walked " + walked + " of " + journalEntries + " entries: no new finding"
                + (known == 0 ? "" : "; " + known + " found that were recorded before the backup, disclosed"));
    }

    // ------------------------------------------------------------------ plumbing

    /** The first {@link #MAX_NAMED} of {@code names}, and how many more. */
    static String named(List<String> names) {
        if (names.size() <= MAX_NAMED) {
            return names.toString();
        }
        return names.subList(0, MAX_NAMED) + " and " + (names.size() - MAX_NAMED) + " more";
    }
}
