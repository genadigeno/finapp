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
 * @param judgedBy the register version: {@code posting-rules:v<n>}, {@code gl-mapping:v<n>} ({@code gl-mapping:none}
 *     when no version applies), {@code source-proofs:v<n>}
 * @param findings every discrepancy the run found - new or already a break
 * @param raised the breaks this run raised
 * @param known the findings a break already held (raised earlier, or by a concurrent run)
 * @param failures why the check did not pass, each a code: {@link #DISCREPANCIES}, {@link #NO_MAPPING},
 *     {@link #FINDINGS_CAPPED}, {@code UNAVAILABLE:<proof or judge>}
 */
public record CheckOutcome(
        YearMonth period,
        CloseCheckCode check,
        Result result,
        String judgedBy,
        int findings,
        int raised,
        int known,
        List<String> failures) {

    /** The run found discrepancies - each a break, new or known. */
    public static final String DISCREPANCIES = "DISCREPANCIES";

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
        if (findings < 0 || raised < 0 || known < 0 || raised + known > findings) {
            throw new IllegalArgumentException("a check raises or knows at most what it found");
        }
        if (findings > 0 && !failures.contains(DISCREPANCIES)) {
            throw new IllegalArgumentException("a check that found a discrepancy says so");
        }
    }

    /** True when the check judged everything it covers and found nothing. */
    public boolean passed() {
        return result == Result.PASSED;
    }
}
