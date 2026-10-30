package com.finapp.ledger;

import java.time.Duration;

/**
 * The observation seam on the ledger's write path (`P3-TSK-020`, `PHASE_3_PLAN.md` §15):
 * every journal-write command — posting, reversal, adjustment — reports its outcome and its
 * latency through this port, and the composition root decides what listens.
 *
 * <h2>Why a port, and why on every command</h2>
 *
 * <p>{@code ledger} must not see a metrics library: what {@code finapp.ledger.posting}
 * counts is this module's fact, and what publishes it is the composition root's decision —
 * the slf4j split. And the seam sits on the <strong>commands</strong> rather than at any one
 * call site because the write path is one ({@code INV-LED-04}): a count incremented per door
 * is a count a new door silently loses (the {@code MeteredKycCaseStore} argument), where a
 * command that cannot be constructed without an observer forces the next caller — Phase 4's
 * transfer wiring — to decide rather than forget. That is also why the parameter is
 * <strong>required</strong>: a defaulted overload is the balanced path a later author
 * optimises away.
 *
 * <h2>What an observation is, and is not</h2>
 *
 * <p>A monitoring approximation, never financial truth ({@code INV-EVT-02}'s spirit): the
 * command joins the <em>caller's</em> transaction, so a command observed {@code POSTED} may
 * still be rolled back by the caller that owns the commit. The journal is the record; the
 * counter answers "how many, how fast, how often refused" and nothing else. No amount, no
 * account, no identifier crosses this seam ({@code INV-AUD-02}).
 */
public interface PostingObserver {

    /** The bounded outcome vocabulary — the {@code outcome} tag's values, always an enum. */
    enum Outcome {
        /** The command executed and its entry was appended. */
        POSTED,
        /** The command replayed a recorded outcome; no new entry exists. */
        REPLAYED,
        /** The command threw — a caller's refusal or an infrastructure failure alike. */
        REFUSED
    }

    /** Called once per command invocation, whatever the outcome. */
    void observe(Outcome outcome, Duration elapsed);

    /** The journal-write command a cutoff refusal came through - the meter's tag, always an enum. */
    enum Command {
        POSTING,
        REVERSAL,
        ADJUSTMENT
    }

    /**
     * The command was refused by the posting cutoff ({@link PeriodClosedException}, {@code ledger.PeriodClosed};
     * `P14-TSK-004`) - observed <em>in addition to</em> {@link #observe} with {@link Outcome#REFUSED}, so
     * {@code finapp.ledger.posting} keeps counting every refusal and {@code finapp.accounting.cutoff.refused} singles
     * out the back-dated ones.
     *
     * <p>Defaulted to nothing so {@link #NONE} and the tests' lambdas stay lambdas; the production meters override it,
     * and {@code LedgerWriteMetersTest} pins that they do. The ledger cannot name the <em>requesting</em> module - a
     * command carries no module - so the tag is the command (an owner item for {@code P14-TSK-006}).
     */
    default void refusedAtTheCutoff(Command command) {}

    /**
     * Observes nothing — for tests and tools that measure nothing. Production wiring passes
     * the meters; this constant exists so a test asserting posting semantics does not have
     * to invent one, not so a bean can take the quiet path.
     */
    PostingObserver NONE = (outcome, elapsed) -> {};
}
