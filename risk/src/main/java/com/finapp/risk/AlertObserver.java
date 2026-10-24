package com.finapp.risk;

/**
 * The alert's meters, as a port (`P13-TSK-015`; {@code PHASE_13_PLAN.md} section 15) - {@code app}'s
 * {@code RiskAlertMetrics} implements it: {@code finapp.risk.alert{kind,severity}} for every alert born and
 * {@code finapp.risk.alert.suppressed{reason_code}} for every approved suppression. Closed vocabularies only - never an
 * id, a subject, a rule's parameters or a reason ({@code INV-RSK-08}). Called inside the act's transaction: a caller that
 * rolls back after the birth has been counted - a counter's tolerance, never an audit's (`-004`'s
 * {@code RiskEvaluationObserver} shape).
 */
public interface AlertObserver {

    /** Counts nothing - the suites' default. */
    AlertObserver NONE = new AlertObserver() {
        @Override
        public void raised(AlertKind kind, AlertSeverity severity) {
        }

        @Override
        public void suppressed(SuppressionReason reason) {
        }
    };

    /** An alert was born. */
    void raised(AlertKind kind, AlertSeverity severity);

    /** A suppression was approved. */
    void suppressed(SuppressionReason reason);
}
