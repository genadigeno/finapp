package com.finapp.accounting;

import com.finapp.accounting.reporting.CanonicalJson;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one close check attempt concluded (`P14-TSK-011`; {@code PHASE_14_PLAN.md} sections 6, 13.5; {@code
 * INV-ACC-09}): its status, the versions it read ({@code judgedBy}), why it failed and its evidence - canonical JSON
 * text ({@link CanonicalJson}, no JSON number): codes, ids, counts and, for a disclosure, aggregate figures per
 * currency in minor units. Never an owner reference, a person or a reason.
 *
 * <p><strong>A verdict that could lie refuses to exist</strong>: {@link CloseCheckStatus#FAILED} exactly when {@code
 * failures} is not empty, never {@link CloseCheckStatus#PENDING}, and {@link CloseCheckStatus#DISCLOSED} only for a
 * {@link CloseCheckCode#disclosable() disclosable} check - {@code accounting V007} holds the same for every writer.
 */
public record CheckVerdict(
        CloseCheckCode check, CloseCheckStatus status, String judgedBy, List<String> failures, String evidence) {

    /** {@code accounting V007}'s {@code close_check_evidence_bounded}. */
    public static final int MAX_EVIDENCE = 16000;

    /** {@code accounting V007}'s {@code close_check_failures_bounded}, the codes joined by {@link #SEPARATOR}. */
    public static final int MAX_FAILURES = 2000;

    /** Between the codes as stored - a code holds none. */
    public static final String SEPARATOR = ",";

    /** {@code accounting V007}'s {@code close_check_judged_by_a_version}. */
    public static final Pattern JUDGED_BY = Pattern.compile("[a-z][a-z-]*(:[A-Za-z0-9_.-]+){1,2}");

    public CheckVerdict {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(judgedBy, "judgedBy");
        Objects.requireNonNull(evidence, "evidence");
        failures = List.copyOf(failures);
        if (status == CloseCheckStatus.PENDING) {
            throw new IllegalArgumentException("a verdict concludes an attempt");
        }
        if ((status == CloseCheckStatus.FAILED) == failures.isEmpty()) {
            throw new IllegalArgumentException("a check is FAILED exactly when something failed it");
        }
        if (status == CloseCheckStatus.DISCLOSED && !check.disclosable()) {
            throw new IllegalArgumentException(check + " is PASSED or it blocks the close - it is never DISCLOSED");
        }
        if (judgedBy.length() > 120 || !JUDGED_BY.matcher(judgedBy).matches()) {
            throw new IllegalArgumentException("a verdict names the versions it read");
        }
        if (evidence.length() < 2 || evidence.length() > MAX_EVIDENCE) {
            throw new IllegalArgumentException("a verdict's evidence is bounded");
        }
        for (String failure : failures) {
            if (failure.isBlank() || failure.contains(SEPARATOR)) {
                throw new IllegalArgumentException("a failure is one code");
            }
        }
        if (String.join(SEPARATOR, failures).length() > MAX_FAILURES) {
            throw new IllegalArgumentException("a verdict's failures are bounded");
        }
    }

    /** {@link CloseCheckStatus#PASSED}: everything read, nothing found. */
    public static CheckVerdict passed(CloseCheckCode check, String judgedBy, CanonicalJson.Obj evidence) {
        return new CheckVerdict(check, CloseCheckStatus.PASSED, judgedBy, List.of(), CanonicalJson.text(evidence));
    }

    /** {@link CloseCheckStatus#DISCLOSED}: read whole and not clean, on the record. */
    public static CheckVerdict disclosed(CloseCheckCode check, String judgedBy, CanonicalJson.Obj evidence) {
        return new CheckVerdict(check, CloseCheckStatus.DISCLOSED, judgedBy, List.of(), CanonicalJson.text(evidence));
    }

    /** {@link CloseCheckStatus#FAILED}, with why. */
    public static CheckVerdict failed(CloseCheckCode check, String judgedBy, List<String> failures,
            CanonicalJson.Obj evidence) {
        return new CheckVerdict(check, CloseCheckStatus.FAILED, judgedBy, failures, CanonicalJson.text(evidence));
    }
}
