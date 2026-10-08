package com.finapp.credit;

/**
 * Why the platform abandoned a decision request (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1) - always
 * said, by {@code credit V010}'s {@code CHECK}: the party's standing lost, or a consent withdrawn mid-request.
 */
public enum ClosureReason {
    STANDING_LOST,
    CONSENT_WITHDRAWN
}
