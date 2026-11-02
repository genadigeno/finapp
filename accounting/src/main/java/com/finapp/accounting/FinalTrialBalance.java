package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Objects;

/**
 * The period's FINAL {@code TRIAL_BALANCE} run, as the close reads it (`P14-TSK-011`; `P14-TSK-009`'s review, M-6;
 * {@code INV-ACC-01} reported, {@code INV-ACC-09}) - a port, because a report's generation is {@code app}'s
 * ({@code ReportRunGenerator}): claimed with no parameters, generated when this caller holds it, and read only once it is
 * confirmed {@code FINAL} and of the definition's own code and version. Called outside any transaction.
 */
@FunctionalInterface
public interface FinalTrialBalance {

    /** The run's standing: a {@link Verdict} read from its frozen content, or why there is none. */
    sealed interface Reading permits InProgress, Unavailable, Verdict {}

    /** Another worker generates the run under its own lease - no answer yet, and not a failure. */
    record InProgress() implements Reading {}

    /** No FINAL run of this definition to read - each {@code failure} a code, and the checks reading it {@code FAILED}. */
    record Unavailable(String failure) implements Reading {

        public Unavailable {
            Objects.requireNonNull(failure, "failure");
        }
    }

    /**
     * The run's stored verdict: each flag exactly {@code true} in the content, never assumed - a member missing is false. {@code
     * passes} the trial balance's own; {@code openingBalanced}, {@code periodBalanced} and {@code balanced} its three legs
     * (`P14-TSK-009`'s review, I-1); {@code fullyMapped} what {@code GL_EQUALS_LEDGER} needs beside it.
     *
     * @param run the run's id as text
     */
    record Verdict(String run, int revision, int definitionVersion, String contentSha256, boolean passes,
            boolean openingBalanced, boolean periodBalanced, boolean balanced, boolean fullyMapped) implements Reading {

        public Verdict {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(contentSha256, "contentSha256");
        }

        /** The verdict and all three legs - any leg false fails it, whatever {@code passes} says. */
        public boolean holds() {
            return passes && openingBalanced && periodBalanced && balanced;
        }
    }

    /** Claims the run, generating it when this call holds it, and reads it. Never throws for a run that failed. */
    Reading read(AccountingPeriodId period, Actor actor, CorrelationId correlation);
}
