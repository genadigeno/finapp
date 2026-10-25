package com.finapp.risk;

/**
 * What the regulatory report tells its meters (`P13-TSK-021`; {@code PHASE_13_PLAN.md} section 15's
 * {@code finapp.risk.report{status}}; {@code DESIGN-021} D11) - implemented in {@code app}: every status a report reaches,
 * and every answer the authority gave a drive. Closed codes only - never an id, a reference or a subject
 * ({@code INV-RSK-08}).
 */
public interface ReportObserver {

    /** Observes nothing - the suites' default. */
    ReportObserver NONE = new ReportObserver() {
        @Override
        public void moved(RegulatoryReportStatus status) {}

        @Override
        public void answered(RegulatoryFilingProvider.Outcome outcome) {}
    };

    /** A report reached {@code status}. */
    void moved(RegulatoryReportStatus status);

    /** The authority answered a submission or an inquiry with {@code outcome}. */
    void answered(RegulatoryFilingProvider.Outcome outcome);
}
