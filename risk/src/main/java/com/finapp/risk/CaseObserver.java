package com.finapp.risk;

import java.time.Duration;

/**
 * The case's meters, as a port (`P13-TSK-016`; {@code PHASE_13_PLAN.md} section 15) - {@code app}'s
 * {@code RiskCaseMetrics}: {@code finapp.risk.case{kind,status}} per move into a status (the birth's {@code OPEN}
 * included) and {@code finapp.risk.case.resolution{kind}} per disposal, on the database's stamps. Codes only - never a
 * subject, a person or a disposition's reason ({@code INV-RSK-08}). The gauges ({@code case.age}, {@code case.backlog})
 * are `-018`'s - `app`'s {@code RiskCaseBacklogMetrics} over {@link RiskCaseStore#backlog}, read from the queue.
 */
public interface CaseObserver {

    /** Records nothing - the suites' default. */
    CaseObserver NONE = new CaseObserver() {
        @Override
        public void moved(CaseKind kind, CaseStatus status) {
            // nothing
        }

        @Override
        public void resolved(CaseKind kind, Duration openToDisposed) {
            // nothing
        }
    };

    /** A case of {@code kind} moved into {@code status} - born {@code OPEN}, taken, escalated, disposed. */
    void moved(CaseKind kind, CaseStatus status);

    /** A case of {@code kind} was disposed {@code openToDisposed} after it opened. */
    void resolved(CaseKind kind, Duration openToDisposed);
}
