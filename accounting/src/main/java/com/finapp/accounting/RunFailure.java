package com.finapp.accounting;

/**
 * Why a report run ended {@code FAILED} (`P14-TSK-008`; the entry's "a generation failure FAILED, a new revision allowed;
 * never a half run") - a closed code, never the failure's message, which could carry a value read from the books
 * ({@code INV-AUD-02}). {@code accounting V004}'s {@code report_run_failure_is_known} holds the list.
 */
public enum RunFailure {

    /** The build holds no definition of the run's code and version (a version retired from the code). */
    DEFINITION_UNKNOWN,
    /** The definition threw - a read it could not make, or a write the read-only snapshot refused. */
    DEFINITION_FAILED,
    /** A FINAL run's period was unlocked (or unlocked and locked again) between its claim and its completion. */
    PERIOD_MOVED,
    /** The content is not canonical (a refused character) or exceeds the stored bound. */
    CONTENT_INVALID,
    /** The run was reclaimed past its lease more times than the bound allows - a generator that never finishes. */
    ATTEMPTS_EXHAUSTED
}
