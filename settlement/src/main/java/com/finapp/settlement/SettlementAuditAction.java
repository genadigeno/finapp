package com.finapp.settlement;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The settlement module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning — the {@code PaymentsAuditAction} rule. The door's
 * own actions ({@code SettlementFileUploaded}, {@code SettlementFileAttested}, the content
 * read) arrive with the door (`P8-TSK-003`); the pull's with the pull (`P8-TSK-021`).
 */
@RequiredArgsConstructor
public enum SettlementAuditAction implements AuditableAction {

    /**
     * The door refused a delivery — instrument data in screened text, or a delivery over the
     * size or line bound (ADR-0066 §4). Written in the refusal's transaction; the change
     * summary names the source, the reason, the checksum and the shape's position, and
     * <strong>never the value</strong>. For a content refusal the
     * {@code settlement.refused_delivery} metadata row stands beside it; an over-bound
     * delivery leaves this record alone.
     */
    SETTLEMENT_DELIVERY_REFUSED(
            "settlement.SettlementDeliveryRefused",
            "The settlement door refused a delivery; the record names the source, the reason"
                    + " and the content address, never a value from the file.",
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
