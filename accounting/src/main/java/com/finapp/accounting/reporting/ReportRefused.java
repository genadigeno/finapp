package com.finapp.accounting.reporting;

import java.util.List;
import java.util.Objects;

/**
 * A definition refuses to answer (`P14-TSK-016`; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-06}): what it read cannot stand as this
 * report, and a report that stood anyway would be the one failure a statement must never have. Not a read that failed: the read succeeded, and
 * what it found is the finding.
 *
 * <p>The run ends {@code FAILED}, {@code DEFINITION_FAILED}: a {@code RunFailure} is a closed list {@code accounting V004} holds, and a refusal is the
 * definition's own. Its {@link #summary()} is on the run's {@code accounting.ReportGenerated} record, so the reason is not lost. At a regeneration it is a
 * divergence ({@code ReportRunGenerator.regenerate}): a FINAL run that can no longer be produced is what that door exists to find.
 *
 * <p><strong>Codes only</strong> ({@code INV-AUD-02}): a reason, and purposes or currencies by name. Never an amount, a balance or a counterparty's position, so
 * the message may reach a log line or an audit summary.
 */
public final class ReportRefused extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** How many subjects a summary names: {@code platform.audit_record}'s summary is bounded, and the first few say what is wrong. */
    static final int MOST_SUBJECTS = 16;

    /** Why a definition would not answer. */
    public enum Reason {
        /** A journal line the run's version does not map, or any line with no version: it has no section, so it has no place on the statement. */
        UNCLASSIFIED,
        /** The books do not balance: the equation's difference is not zero in a currency, or the journal is not zero before, in or through the period. */
        OUT_OF_BALANCE,
        /** The figures are not the ledger's own totals, or the code's range is not the period row's: a read lost or gained lines. */
        NOT_THE_LEDGERS,
        /** A currency the run's valuation rate set does not value, with a figure that is not zero: a statement cannot stand with a hole. */
        TRANSLATION_INCOMPLETE,
        /** A FINAL run of a period not yet {@code CLOSED}: what it discloses is still being decided. The claim refuses first; this is the second door. */
        NOT_CLOSED,
        /** A {@code CLOSED} period with no approved close at the run's revision and lock: something bypassed the machine. */
        CLOSE_RECORD_MISSING
    }

    private final Reason reason;
    private final transient List<String> subjects;

    /**
     * @param reason why
     * @param subjects what it is about, by code - purposes and owner kinds, or currencies; empty when the reason says it all
     */
    public ReportRefused(Reason reason, List<String> subjects) {
        super(summaryOf(Objects.requireNonNull(reason, "reason"), List.copyOf(subjects)));
        this.reason = reason;
        this.subjects = List.copyOf(subjects);
    }

    public Reason reason() {
        return reason;
    }

    /** What it is about, by code. */
    public List<String> subjects() {
        return subjects;
    }

    /** The reason and the first {@link #MOST_SUBJECTS} subjects, as the audit record carries them: {@code OUT_OF_BALANCE:EUR,USD}. */
    public String summary() {
        return getMessage();
    }

    private static String summaryOf(Reason reason, List<String> subjects) {
        if (subjects.isEmpty()) {
            return reason.name();
        }
        String named = String.join(",", subjects.subList(0, Math.min(subjects.size(), MOST_SUBJECTS)));
        return reason.name() + ":" + named + (subjects.size() > MOST_SUBJECTS ? ",+" + (subjects.size() - MOST_SUBJECTS) : "");
    }
}
