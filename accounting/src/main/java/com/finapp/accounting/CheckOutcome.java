package com.finapp.accounting;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * One run of a break-raising check (`P14-TSK-010`; {@code PHASE_14_PLAN.md} sections 8.1 T-check, 13.3): what the close
 * run's attempt row (`P14-TSK-011`) records - the verdict, the register version it judged by, what it found and what it
 * raised. {@link Result#PASSED} only when the check judged everything it covers and found nothing; any discrepancy, any
 * unavailable proof, any judgement it could not make is {@link Result#FAILED} with its reasons - <strong>never a false
 * pass</strong> ({@code INV-REC-02}).
 *
 * <p><strong>What a known finding is held by</strong> (the independent review's I-1): a finding a break of the period
 * and check already holds is counted, never raised again - and counted by that break's status, because they mean
 * different things to the close: one held {@code OPEN} awaits a decision, one held {@code ACKNOWLEDGED} is disclosed,
 * and one held {@code RESOLVED} is a discrepancy <strong>still present</strong> after its break was resolved by
 * reference to a correcting entry - reported as {@link #RECURRED}, never folded into "disclosed". Whether a recurrence
 * should instead raise a new break (a generation in the key, or re-raise on {@code RESOLVED}) is an owner item for
 * `-011`, ADR-0117's open question.
 *
 * @param judgedBy the register version: {@code posting-rules:v<n>}, {@code gl-mapping:v<n>} ({@code gl-mapping:none}
 *     when no version applies), {@code source-proofs:v<n>}
 * @param findings every discrepancy the run found - new or already a break
 * @param raised the breaks this run raised
 * @param knownOpen findings an {@code OPEN} break already held (a concurrent run's newborn among them)
 * @param knownAcknowledged findings an {@code ACKNOWLEDGED} break held - disclosed
 * @param knownResolved findings a {@code RESOLVED} break held - a recurrence ({@link #RECURRED})
 * @param failures why the check did not pass, each a code: {@link #DISCREPANCIES}, {@link #RECURRED},
 *     {@link #NO_MAPPING}, {@link #FINDINGS_CAPPED}, {@code UNAVAILABLE:<proof or judge>}
 */
public record CheckOutcome(
        YearMonth period,
        CloseCheckCode check,
        Result result,
        String judgedBy,
        int findings,
        int raised,
        int knownOpen,
        int knownAcknowledged,
        int knownResolved,
        List<String> failures) {

    /** The run found discrepancies - each a break, new or known. */
    public static final String DISCREPANCIES = "DISCREPANCIES";

    /** A discrepancy a {@code RESOLVED} break holds is still present - its correction did not remove it. */
    public static final String RECURRED = "RECURRED";

    /** No GL mapping version is pinned by, or in force for, the period - the GL cannot be judged. */
    public static final String NO_MAPPING = "NO_MAPPING";

    /** More new discrepancies than one run raises - the next run raises the rest. */
    public static final String FINDINGS_CAPPED = "FINDINGS_CAPPED";

    /** The prefix of a proof or judge that could not be read. */
    public static final String UNAVAILABLE = "UNAVAILABLE:";

    /** A close check's two verdicts this run can reach; {@code DISCLOSED} is the close's (`P14-TSK-011`, `-012`). */
    public enum Result {
        PASSED,
        FAILED
    }

    public CheckOutcome {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(judgedBy, "judgedBy");
        failures = List.copyOf(failures);
        if ((result == Result.PASSED) != failures.isEmpty()) {
            throw new IllegalArgumentException("a check passes exactly when nothing failed it");
        }
        if (findings < 0 || raised < 0 || knownOpen < 0 || knownAcknowledged < 0 || knownResolved < 0
                || raised + knownOpen + knownAcknowledged + knownResolved > findings) {
            throw new IllegalArgumentException("a check raises or knows at most what it found");
        }
        if ((findings > 0) != failures.contains(DISCREPANCIES)) {
            throw new IllegalArgumentException("a check says DISCREPANCIES exactly when it found one");
        }
        if ((knownResolved > 0) != failures.contains(RECURRED)) {
            throw new IllegalArgumentException("a check says RECURRED exactly when a resolved break's discrepancy is"
                    + " still present");
        }
    }

    /** True when the check judged everything it covers and found nothing. */
    public boolean passed() {
        return result == Result.PASSED;
    }

    /** Every finding a break already held, whatever its status. */
    public int known() {
        return knownOpen + knownAcknowledged + knownResolved;
    }
}
