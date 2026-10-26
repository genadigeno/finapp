package com.finapp.risk;

/**
 * The phase's censuses (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 13.3) - the {@code kind} tag of
 * {@code finapp.risk.census}, a bounded vocabulary ({@code INV-RSK-08}).
 */
public enum CensusKind {
    /** Assessments = judged commands, per operation ({@code INV-RSK-01}; {@link AssessmentCensus}). */
    ASSESSMENT,
    /** Monitored transactions = journal entries since the floor, by sequence ({@code INV-RSK-09}; {@link MonitoringCensus}). */
    MONITORING,
    /** One alert per (subject, rule, window) ({@code INV-RSK-06}; {@link AlertBornOnceCensus}). */
    ALERT_BORN_ONCE,
    /** One open case per (subject, kind) ({@code INV-RSK-06}; {@link CaseOneOpenCensus}). */
    CASE_ONE_OPEN,
    /** No filed report left undriven past its due ({@code ADR-0111} decision 6; {@link FilingCensus}). */
    FILING,
    /**
     * Per screening list version, every permit born counted by its enqueue and every clearance a signal naming the
     * version ({@code INV-KYC-04}, {@code INV-RSK-06}; {@link RescreeningCensus} - `P13-TSK-024`, the row `-022` routed).
     */
    RESCREENING
}
