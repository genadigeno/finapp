package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The reconciliation module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with
 * the commands whose designs fix their meaning — the {@code PaymentsAuditAction} rule. The
 * backfill's and the report's arrived with `P8-TSK-007`; matching's, the breaks' and the
 * resolutions' arrive with their tasks.
 */
@RequiredArgsConstructor
public enum ReconciliationAuditAction implements AuditableAction {

    /**
     * A controller adopted the opening position (`P8-TSK-007`, ADR-0067 §8): the keyed,
     * reasoned backfill that walks Phases 5–7's completed clearing operations through the
     * live opener's own path. One record per recorded run; the change summary carries the
     * per-producer counts — <strong>counts only, never an amount</strong>
     * ({@code INV-AUD-02}) — and the reason is the controller's own.
     */
    OPENING_POSITION_RECORDED(
            "reconciliation.OpeningPositionRecorded",
            "A reconciliation controller adopted the opening position: history's completed"
                    + " clearing operations opened as tracked expectations, with the"
                    + " controller's recorded reason and the counts.",
            true),

    /**
     * Somebody read a reconciliation report (`P8-TSK-007`, ADR-0072; the
     * {@code payments.ChargebackRatioRead} precedent): the positions report carries
     * amounts, so every serving is on the record — the report's name and period, never its
     * figures.
     */
    REPORT_READ(
            "reconciliation.ReportRead",
            "A reconciliation report carrying amounts was served; the record names the"
                    + " report, never its figures.",
            false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
