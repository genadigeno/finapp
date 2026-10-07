package com.finapp.credit;

import com.finapp.platform.audit.AuditableAction;
import lombok.RequiredArgsConstructor;

/**
 * The credit module's audited acts (`P10-TSK-006`, {@code INV-AUD-01}): every access to a person's credit data is
 * recorded where it is opened.
 */
@RequiredArgsConstructor
public enum CreditAuditAction implements AuditableAction {

    /**
     * The platform opened a bureau data request on the applicant's behalf (`P10-TSK-006`, ADR-0085) - written once,
     * by the opener, in the transaction that births the request; a retry is the same access under the same reference
     * and is an attempt row, never a second act.
     */
    BUREAU_DATA_REQUESTED(
            "credit.BureauDataRequested",
            "The platform opened a credit bureau data request for a decision request, under a current lawful basis"
                    + " (INV-CRD-03); the summary names the request and the source kind, never an attribute.",
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
