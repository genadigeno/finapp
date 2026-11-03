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
 * <p><strong>One rule for a finding a person decided on</strong> (the owner's ruling, 2026-11-02, ADR-0117; built by `P14-TSK-012`): a
 * finding is compared with the evidence a person decided on. Held by an {@code OPEN} break it awaits a decision. Held by a decided break and
 * <strong>unchanged</strong> it counts as disclosed - an {@code ACKNOWLEDGED} break's by its disclosure, a {@code RESOLVED} one's by its correcting
 * entry, so an immutable subject no longer wedges a close. <strong>Changed</strong>, it is raised again under the next generation, needing its own
 * four eyes, and the check is {@code FAILED} ({@link #FINDING_CHANGED}, its subject named). Evidence holds codes, ids and counts and never an amount, so a
 * change of amount alone is not seen: the limit the owner accepted.
 *
 * @param judgedBy the register version: {@code posting-rules:v<n>}, {@code gl-mapping:v<n>} ({@code gl-mapping:none}
 *     when no version applies), {@code source-proofs:v<n>}
 * @param findings every discrepancy the run found - new or already a break
 * @param raised the breaks this run raised
 * @param knownOpen findings an {@code OPEN} break already held (a concurrent run's newborn among them)
 * @param knownAcknowledged findings an {@code ACKNOWLEDGED} break held, unchanged - disclosed
 * @param knownResolved findings a {@code RESOLVED} break held, unchanged - disclosed by its correcting entry
 * @param changed the subjects of findings that differ from what a person decided on, each raised under its next generation, sorted
 * @param failures why the check did not pass, each a code: {@link #DISCREPANCIES}, {@link #FINDING_CHANGED}, {@link #NO_MAPPING},
 *     {@link #FINDINGS_CAPPED}, {@code UNAVAILABLE:<proof or judge>}
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
        List<String> changed,
        List<String> failures) {

    /** The run found discrepancies - each a break, new or known. */
    public static final String DISCREPANCIES = "DISCREPANCIES";

    /** A finding a decided break holds no longer reads as what a person decided on: raised under its next generation. */
    public static final String FINDING_CHANGED = "FINDING_CHANGED";

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
        changed = List.copyOf(changed);
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
        if (changed.isEmpty() == failures.contains(FINDING_CHANGED) || changed.size() > findings) {
            throw new IllegalArgumentException("a check says FINDING_CHANGED exactly when a decided finding differs, and names"
                    + " only those");
        }
    }

    /** True when the check judged everything it covers and found nothing. */
    public boolean passed() {
        return result == Result.PASSED;
    }

    /** Every finding a person decided on and that stands unchanged - what a disclosable check may disclose. */
    public int decided() {
        return knownAcknowledged + knownResolved;
    }

    /** Every finding a break already held, whatever its status. */
    public int known() {
        return knownOpen + knownAcknowledged + knownResolved;
    }
}
