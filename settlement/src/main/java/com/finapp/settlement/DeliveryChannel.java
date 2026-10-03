package com.finapp.settlement;

/**
 * How a delivery reached the door (`settlement.file.received_via`, ADR-0066 §1).
 *
 * <p>{@code UPLOAD} and {@code PULL} are the channels a source declares
 * ({@link SettlementSourceDescriptor}); {@code READMISSION} is an operator's recovery of a
 * wrongly rejected file (`P8-TSK-022`), always available and never declared. The channel decides
 * what authenticates the bytes: a pull is authenticated by the source's own confined credential,
 * an upload only by a second person's attestation, and a readmission inherits its original's —
 * or, for a never-attested or {@code DECLINED} original, needs an attestation of its own, by a
 * person distinct from every submitter along its chain (`V009`). Authentication follows the
 * row's channel, never its receipts.
 */
public enum DeliveryChannel {
    UPLOAD,
    PULL,
    READMISSION
}
