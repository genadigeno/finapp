package com.finapp.credit;

/**
 * Where a credit data request stands (`P10-TSK-006`; ADR-0085, the lifecycle document's data request) -
 * credit {@code V004}'s machine, held by an every-writer trigger.
 *
 * <p>{@code REQUESTED -> RECEIVED | UNAVAILABLE | CONSENT_WITHDRAWN}; {@code UNAVAILABLE -> REQUESTED} (a retry,
 * only before the deadline) {@code | CONSENT_WITHDRAWN}. {@code RECEIVED} and {@code CONSENT_WITHDRAWN} are
 * terminal: a withdrawal after the answer leaves the record {@code RECEIVED}, and the decision request acts on it
 * (G11). {@code UNAVAILABLE} is a state, never data ({@code INV-CRD-10}).
 */
public enum CreditDataRequestStatus {
    REQUESTED,
    RECEIVED,
    UNAVAILABLE,
    CONSENT_WITHDRAWN
}
