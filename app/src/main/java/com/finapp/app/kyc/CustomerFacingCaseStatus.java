package com.finapp.app.kyc;

import com.finapp.kyc.KycCaseStatus;

/**
 * The customer-facing case-status vocabulary — the tipping-off shaping, in one definition
 * (`P2-TSK-016` for KYB, `P2-TSK-006` for the person's own KYC view).
 *
 * <p>{@code IN_PROGRESS} covers checks <em>and</em> review, deliberately: which of the two a
 * case is in is exactly what tipping-off forbids disclosing (plan §6, {@code INV-IDN-07}'s
 * reasoning) — a screening hit must be indistinguishable from ordinary processing in anything
 * the subject can see. This mapping is a security control, and a security control copied per
 * controller is one that drifts in exactly one of its copies; extracted the moment the second
 * customer-facing view arrived.
 *
 * <p>The exhaustive {@code switch} is the drift guard: a new {@link KycCaseStatus} fails
 * compilation here until somebody decides which side of the disclosure line it sits on.
 */
final class CustomerFacingCaseStatus {

    private CustomerFacingCaseStatus() {}

    static String of(KycCaseStatus status) {
        return switch (status) {
            case OPEN -> "OPEN";
            case CHECKS_IN_PROGRESS, IN_REVIEW -> "IN_PROGRESS";
            case READY_FOR_DECISION -> "PENDING_DECISION";
            case APPROVED -> "APPROVED";
            case REJECTED -> "REJECTED";
        };
    }
}
