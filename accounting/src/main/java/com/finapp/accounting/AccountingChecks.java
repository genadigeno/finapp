package com.finapp.accounting;

import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * The three break-raising close checks as one callable (`P14-TSK-010`; ADR-0117 decision 2; {@code PHASE_14_PLAN.md}
 * sections 8.1 T-check, 13.3, 13.5; {@code INV-ACC-12}, {@code INV-REC-02}) - {@code GL_EQUALS_LEDGER},
 * {@code RULE_CONFORMANCE}, {@code SOURCE_COMPLETENESS} - which the close run's worker invokes per claimed check
 * (`P14-TSK-011`; nothing here schedules, and no door runs it: the operator's re-run is `-011`'s).
 *
 * <p><strong>Judge, then raise.</strong> Each judge reads on a verified, statement-bounded {@code REPEATABLE READ READ
 * ONLY} snapshot ({@link Snapshots}; {@code INV-ACC-14}) and writes nothing: GL = ledger and rule conformance on one
 * snapshot each - the month's entries in keyset pages of {@link #PAGE} - source completeness on <strong>one snapshot per
 * source</strong>, so a proof that fails or times out (its transaction aborted) blinds no other. Then every new finding
 * is raised in a transaction of its own ({@link Transactions}; {@link AccountingBreaks#raise}: the break, its case-file
 * row, the audit record and the outbox row in one commit), so a crash leaves every committed break whole and the
 * re-run raises the rest. Nothing here posts.
 *
 * <p><strong>Ten instances, one break per discrepancy.</strong> Any number of instances may run the same check for the
 * same period at once: the judges only read, and the raises converge on {@code accounting_break_born_once}. Subjects
 * already raised are counted by their break's status - open, acknowledged, or resolved and so <strong>recurred</strong>
 * ({@link CheckOutcome#RECURRED}, the review's I-1) - never raised again; a run keeps at most {@link #MAX_NEW_FINDINGS}
 * new ones ({@link CheckOutcome#FINDINGS_CAPPED} - the next run raises the next).
 *
 * <p><strong>No false pass.</strong> {@link CheckOutcome.Result#PASSED} only when the judge read everything and found
 * nothing: a source unread, a reading incomplete, a judge that threw or timed out, a month with no mapping version - each
 * {@code FAILED} with its reason, the exception's class logged (never its message, which may carry data). Every outcome
 * names the register version it judged by.
 *
 * <p><strong>The caller's contract</strong> (`P14-TSK-011`, the review's M-1): call {@link #run} <em>outside</em> any
 * transaction - each judge opens its own read-only snapshot and each raise its own transaction, so a surrounding
 * transaction would hold a connection idle for the whole run and could not see a raise roll back alone. A run is bounded
 * per statement ({@code finapp.accounting.checks.statement-timeout}) and per page, <strong>not in total</strong>: source
 * completeness reads the whole book through the proofs, so the close run's lease must exceed a run, or its worker must
 * renew it while the run lasts.
 */
@Slf4j
public final class AccountingChecks {

    /** The entries of one conformance page. */
    public static final int PAGE = JournalShapes.MAX_LIMIT;

    /** The most new breaks one run raises. */
    public static final int MAX_NEW_FINDINGS = 1000;

    /** One read on a verified {@code REPEATABLE READ READ ONLY} snapshot with a statement timeout, always rolled back. */
    public interface Snapshots {
        <R> R read(Function<Connection, R> work);
    }

    /** One unit of work: a transaction, committed when {@code work} returns. */
    public interface Transactions {
        <R> R inTransaction(Function<Connection, R> work);
    }

    private final GeneralLedger ledger;
    private final GlPins pins;
    private final JournalShapes shapes;
    private final SourceProofs proofs;
    private final AccountingBreaks breaks;
    private final Snapshots snapshots;
    private final Transactions transactions;
    private final GlEqualsLedger glEqualsLedger = new GlEqualsLedger();
    private final RuleConformance conformance;
    private final SourceCompleteness completeness = new SourceCompleteness();

    public AccountingChecks(GeneralLedger ledger, GlPins pins, JournalShapes shapes, SourceProofs proofs,
            PostingRuleRegister register, AccountingBreaks breaks, Snapshots snapshots, Transactions transactions) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.conformance = new RuleConformance(Objects.requireNonNull(register, "register"));
        this.breaks = Objects.requireNonNull(breaks, "breaks");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * Runs {@code check} for {@code period}: judges, raises every new finding, answers the outcome. Call it outside any
     * transaction (the class's contract).
     *
     * @param actor the run's - the platform for the close schedule, a person for a re-run
     * @throws IllegalArgumentException for a check that raises no breaks - the close run's other six are `P14-TSK-011`'s
     * @throws RuntimeException when a raise cannot be written - every break raised before it stays; re-run
     */
    public CheckOutcome run(YearMonth period, CloseCheckCode check, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        Judgement judgement = switch (check) {
            case GL_EQUALS_LEDGER -> judgeGl(period);
            case RULE_CONFORMANCE -> judgeConformance(period);
            case SOURCE_COMPLETENESS -> judgeSources(period);
            default -> throw new IllegalArgumentException(check + " raises no break - it is the close run's check"
                    + " (P14-TSK-011)");
        };
        int raised = 0;
        int lost = 0;
        for (BreakFinding finding : judgement.fresh()) {
            if (transactions.inTransaction(uow -> breaks.raise(uow, period, finding, actor, correlation))) {
                raised++;
            } else {
                // A concurrent run raised it between our read and our insert: a newborn, OPEN break.
                lost++;
            }
        }
        int knownOpen = judgement.known(BreakStatus.OPEN) + lost;
        int knownAcknowledged = judgement.known(BreakStatus.ACKNOWLEDGED);
        int knownResolved = judgement.known(BreakStatus.RESOLVED);
        int findings = judgement.fresh().size() + judgement.known(BreakStatus.OPEN) + knownAcknowledged + knownResolved;
        List<String> failures = new ArrayList<>();
        if (findings > 0) {
            failures.add(CheckOutcome.DISCREPANCIES);
        }
        if (knownResolved > 0) {
            failures.add(CheckOutcome.RECURRED);
        }
        failures.addAll(judgement.failures());
        return new CheckOutcome(period, check, failures.isEmpty() ? CheckOutcome.Result.PASSED
                : CheckOutcome.Result.FAILED, judgement.judgedBy(), findings, raised, knownOpen, knownAcknowledged,
                knownResolved, failures);
    }

    // ------------------------------------------------------------------ the judges

    /** What a judge found: the new findings to raise, the known ones counted per status, why it could not pass. */
    private record Judgement(String judgedBy, List<BreakFinding> fresh, Map<BreakStatus, Integer> knownByStatus,
            List<String> failures) {

        int known(BreakStatus status) {
            return knownByStatus.getOrDefault(status, 0);
        }
    }

    private Judgement judgeGl(YearMonth period) {
        try {
            return snapshots.read(snapshot -> {
                GlBalances balances = ledger.balances(snapshot, pins.forPeriod(snapshot, period), Optional.empty());
                Map<String, BreakStatus> held = breaks.held(snapshot, period, CloseCheckCode.GL_EQUALS_LEDGER);
                return split(GlEqualsLedger.judgedBy(balances), glEqualsLedger.judge(balances), held, List.of());
            });
        } catch (GlPins.NoActiveMapping none) {
            return new Judgement("gl-mapping:none", List.of(), Map.of(), List.of(CheckOutcome.NO_MAPPING));
        } catch (RuntimeException unreadable) {
            return unavailable("gl-mapping:none", CloseCheckCode.GL_EQUALS_LEDGER.name(), unreadable);
        }
    }

    private Judgement judgeConformance(YearMonth period) {
        try {
            return snapshots.read(snapshot -> {
                Map<String, BreakStatus> held = breaks.held(snapshot, period, CloseCheckCode.RULE_CONFORMANCE);
                RuleConformance.Tally tally = conformance.tally(held, MAX_NEW_FINDINGS);
                JournalReads.Range range = JournalReads.Range.of(period);
                Optional<JournalShapes.EntryCursor> after = Optional.empty();
                List<JournalShapes.EntryShape> page;
                do {
                    page = shapes.entries(snapshot, range, after, PAGE);
                    JournalShapes.EntryCursor previous = after.orElse(null);
                    for (JournalShapes.EntryShape entry : page) {
                        // The page is the month's, in keyset order, or it proves nothing: the adapter's defect.
                        if (!range.holds(entry.postingDate()) || (previous != null && !follows(previous, entry))) {
                            throw new IllegalStateException("the journal served an entry outside the month's keyset");
                        }
                        previous = entry.cursor();
                        tally.add(entry);
                    }
                    after = page.isEmpty() ? after : Optional.of(page.get(page.size() - 1).cursor());
                } while (page.size() == PAGE);
                return new Judgement(conformance.judgedBy(), tally.findings(), tally.known(),
                        tally.capped() ? List.of(CheckOutcome.FINDINGS_CAPPED) : List.of());
            });
        } catch (RuntimeException unreadable) {
            return unavailable(conformance.judgedBy(), CloseCheckCode.RULE_CONFORMANCE.name(), unreadable);
        }
    }

    private Judgement judgeSources(YearMonth period) {
        Map<String, BreakStatus> held;
        try {
            held = snapshots.read(snapshot -> breaks.held(snapshot, period, CloseCheckCode.SOURCE_COMPLETENESS));
        } catch (RuntimeException unreadable) {
            return unavailable(SourceCompleteness.JUDGED_BY, CloseCheckCode.SOURCE_COMPLETENESS.name(), unreadable);
        }
        List<BreakFinding> found = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            SourceCompleteness.Judged judged;
            try {
                judged = completeness.judge(source, snapshots.read(snapshot -> proofs.read(snapshot, source)));
            } catch (RuntimeException unreadable) {
                // Never a pass: a source that cannot be read leaves every proof it covers unavailable.
                logUnavailable(source.name(), unreadable);
                judged = completeness.unreadable(source);
            }
            found.addAll(judged.findings());
            judged.unavailable().forEach(proof -> failures.add(CheckOutcome.UNAVAILABLE + proof.name()));
        }
        return split(SourceCompleteness.JUDGED_BY, found, held, failures);
    }

    /** {@code found} split into the new (first {@link #MAX_NEW_FINDINGS}) and the known per status, with the failures. */
    private static Judgement split(String judgedBy, List<BreakFinding> found, Map<String, BreakStatus> held,
            List<String> failures) {
        List<BreakFinding> fresh = new ArrayList<>();
        Map<BreakStatus, Integer> known = new EnumMap<>(BreakStatus.class);
        Set<String> seen = new HashSet<>();
        boolean capped = false;
        for (BreakFinding finding : found) {
            if (!seen.add(finding.subject())) {
                continue;
            }
            BreakStatus holder = held.get(finding.subject());
            if (holder != null) {
                known.merge(holder, 1, Integer::sum);
            } else if (fresh.size() < MAX_NEW_FINDINGS) {
                fresh.add(finding);
            } else {
                capped = true;
            }
        }
        List<String> all = new ArrayList<>(failures);
        if (capped) {
            all.add(CheckOutcome.FINDINGS_CAPPED);
        }
        return new Judgement(judgedBy, fresh, known, all);
    }

    private static Judgement unavailable(String judgedBy, String what, RuntimeException unreadable) {
        logUnavailable(what, unreadable);
        return new Judgement(judgedBy, List.of(), Map.of(), List.of(CheckOutcome.UNAVAILABLE + what));
    }

    /** The review's M-2: the judge's exception by class - never its message, which may carry a figure or an id. */
    private static void logUnavailable(String what, RuntimeException unreadable) {
        log.warn("An accounting check's judge could not read {} - the check is FAILED, never passed: {}", what,
                unreadable.getClass().getName());
    }

    /** True when {@code entry} comes after {@code previous} in {@code (posting_date, id)} order (PostgreSQL's uuid order). */
    private static boolean follows(JournalShapes.EntryCursor previous, JournalShapes.EntryShape entry) {
        int byDate = entry.postingDate().compareTo(previous.postingDate());
        return byDate > 0 || (byDate == 0 && JournalReads.LineCursor.compareIds(entry.id(), previous.entryId()) > 0);
    }
}
