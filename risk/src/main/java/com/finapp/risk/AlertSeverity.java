package com.finapp.risk;

/**
 * How urgent an alert is (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 10, 15) - the policy row that raises it names
 * the severity; the queue is oldest first within it, and {@code finapp.risk.alert{kind,severity}} counts by it. A bounded
 * vocabulary, so it may be a metric tag ({@code INV-RSK-08}: never an id, an amount or a name).
 */
public enum AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
