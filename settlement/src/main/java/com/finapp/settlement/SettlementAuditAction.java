package com.finapp.settlement;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The settlement module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning — the {@code PaymentsAuditAction} rule. The door's
 * own actions arrived with the door (`P8-TSK-003`); the pull's arrive with the pull
 * (`P8-TSK-021`).
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
            false),

    /**
     * A person introduced settlement evidence over the upload door (`P8-TSK-003`, ADR-0066
     * §1). Written in the reception's one transaction; the change summary names the source,
     * the channel and the content address — never a value or the business date's claim. A
     * duplicate delivery writes its receipt and no second record.
     */
    SETTLEMENT_FILE_UPLOADED(
            "settlement.SettlementFileUploaded",
            "An operator uploaded a settlement file; the record names the source, the channel"
                    + " and the content address, never the content.",
            false),

    /**
     * A second person attested an uploaded file (`INV-SET-07`, `INV-AUD-04`): the
     * {@code NULL → value} fact that lets the accept leg (`P8-TSK-009`) act on it. Written in
     * the attestation's transaction; distinctness from the uploader is refused at the domain
     * and by {@code CHECK} before this record can exist.
     */
    SETTLEMENT_FILE_ATTESTED(
            "settlement.SettlementFileAttested",
            "A second person attested an uploaded settlement file, making it eligible for"
                    + " acceptance once parsed.",
            false),

    /**
     * Somebody read a settlement file's raw content (`INV-REC-10`): one record per read, the
     * reason required, committed with the read before a byte is served. A verification
     * failure — tamper, transplant, truncation — is the same record with outcome
     * {@code FAILED}, and nothing is served.
     */
    SETTLEMENT_FILE_CONTENT_READ(
            "settlement.SettlementFileContentRead",
            "A settlement file's raw content was read (or a read failed verification), with"
                    + " the investigator's recorded reason.",
            true);

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
