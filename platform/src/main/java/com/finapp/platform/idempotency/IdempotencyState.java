package com.finapp.platform.idempotency;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The lifecycle of an idempotency claim (ADR-0004, {@code INV-IDEM-01}).
 *
 * <p>Three states, and the middle one is the one that matters. A claim is inserted
 * <em>before</em> the command runs, so that the unique constraint arbitrates between
 * simultaneous duplicates; that means a record exists during a window in which the outcome is
 * genuinely unknown. Modelling that window explicitly is what stops a crashed request from
 * being read as either a success or a failure — the mistake {@code INV-LIFE-03} names for
 * external providers, and the same mistake applies to our own interrupted work.
 *
 * <p>The names are persisted values. They appear in a {@code CHECK} constraint on
 * {@code platform.idempotency_record}, and {@link #sqlValueList()} generates that constraint's
 * literal list from this enum so the two cannot drift — the same technique that keeps the
 * monetary scale bound in one place. Renaming a constant is therefore a schema change, not a
 * refactor.
 */
public enum IdempotencyState {

    /**
     * The key is claimed and the command is running. The outcome is unknown.
     *
     * <p>Not "probably failed". A process that dies here may already have committed its
     * financial effect, so a retry that assumed failure and re-executed would produce the
     * second effect this whole mechanism exists to prevent. Resolution is a bounded wait and
     * then a conflict, plus reclaim after a crash timeout (P0-TSK-016).
     */
    IN_PROGRESS,

    /**
     * The command finished and its outcome is stored. A retry replays that outcome rather than
     * running anything.
     */
    COMPLETED,

    /**
     * The command finished by failing, and that failure is itself the recorded outcome.
     *
     * <p>Distinct from {@link #IN_PROGRESS} because a known failure is a fact worth replaying:
     * a client retrying a request that was definitively rejected should be told so again, not
     * have it attempted a second time.
     */
    FAILED;

    /** True while the outcome is not yet known. */
    public boolean isTerminal() {
        return this != IN_PROGRESS;
    }

    /**
     * The states as a SQL literal list, for the {@code CHECK} constraint.
     *
     * <p>Generated rather than typed out, so adding a state without a migration is impossible
     * to do quietly: the constraint and the enum are one definition.
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(state -> "'" + state.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
