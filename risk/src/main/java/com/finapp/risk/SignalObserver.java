package com.finapp.risk;

/**
 * What recording a signal tells the operators (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15's
 * {@code finapp.risk.signal{kind,outcome}}; `-005`'s hand-off) - every {@link RiskSignals#record} by the kind recorded and
 * whether it was born or already stood ({@link RiskSignals.Outcome}). Codes only, never a subject, a source or an
 * attribute ({@code INV-RSK-08}). Counted inside the recording's transaction: a delivery rolled back after its signal
 * was born - a stalled partition among them - is counted on each attempt (a counter's tolerance, never an audit's).
 * Implemented in {@code app} by {@code RiskSignalMetrics}; {@link #NONE} counts nothing.
 */
@FunctionalInterface
public interface SignalObserver {

    /** Counts nothing - the suites' default. */
    SignalObserver NONE = (kind, outcome) -> { };

    /** A signal of {@code kind} recorded with {@code outcome}. */
    void recorded(SignalKind kind, RiskSignals.Outcome outcome);
}
