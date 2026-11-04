package com.finapp.accounting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * One line of a return (`P14-TSK-018`; {@code accounting V010}'s {@code regulatory_submission_line}; {@code INV-ACC-13}): the
 * amount - exact minor units, a currency, a scale - and its lineage, the run's id and hash and the figure's resolved path.
 * Every field required: a line no issued figure explains cannot be built.
 *
 * @param ordinal its place on the form, from 1 - one line per currency in currency order
 * @param source the report definition the run answers
 * @param figure the path that found it, the wildcard replaced
 */
public record RegulatoryFormLine(int ordinal, String code, String label, String currency, BigInteger minor, int scale, ReportRunId run, String runSha256, String source, String figure) {

    public RegulatoryFormLine {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(minor, "minor");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(runSha256, "runSha256");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(figure, "figure");
        if (ordinal < 1) {
            throw new IllegalArgumentException("a form line's ordinal starts at 1");
        }
    }

    /** The amount, exactly: never a float, and never read across currencies. */
    public BigDecimal amount() {
        return new BigDecimal(minor, scale);
    }

    /** An amount stays out of a log line. */
    @Override
    public String toString() {
        return "RegulatoryFormLine[" + code + ", " + currency + "]";
    }
}
