package com.finapp.risk;

import java.sql.Connection;

/**
 * What a recorded signal raises in T-sig itself (`P13-TSK-025`; {@code PHASE_13_PLAN.md} sections 7.1 T-sig, 3.1 C3) -
 * the alert a <em>fact</em> calls for, raised in the inbox handler's own transaction beside the signal, so the signal,
 * its alert and its case commit together or not at all. Only a few kinds raise anything: a fraud or monitoring rule's
 * alert is T-eval's or T-mon's, judged over many signals; a fact's alert is one signal's own.
 *
 * <p>{@link RiskSignals#record} hands this port the <strong>standing</strong> signal on every delivery that reaches the
 * handler - the one just born, or the one an earlier delivery recorded (read back by its source event) - so the raise
 * is re-asked of the alert's own unique on a duplicate and converges on a replay of a signal recorded before the alert
 * existed. {@link #NONE} raises nothing (the suites' shape, and the composition's before `-025`).
 */
public interface SignalAlerts {

    /** Raises nothing. */
    SignalAlerts NONE = new SignalAlerts() {
        @Override
        public boolean raises(SignalKind kind) {
            return false;
        }

        @Override
        public void raise(Connection unitOfWork, RiskSignal standing) {
            throw new IllegalStateException("nothing is raised from a signal here");
        }
    };

    /** True when a signal of {@code kind} raises an alert of its own. */
    boolean raises(SignalKind kind);

    /**
     * Raises - births, then folds, one {@code raiseAll} - what {@code standing} calls for, on the caller's unit of work.
     * A conflict on the alert's unique is an answer, never an error; a failure aborts the caller's transaction.
     */
    void raise(Connection unitOfWork, RiskSignal standing);
}
