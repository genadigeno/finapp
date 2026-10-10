package com.finapp.lending;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * One version of a loan agreement's identity (`P11-TSK-002`; the table is `P11-TSK-012`'s) - the consumer reference
 * credit records when the agreement takes a decision up ({@code ConsumerKind.LOAN_AGREEMENT}, `P11-TSK-001`).
 */
public final class LoanAgreementId extends EntityId {

    private LoanAgreementId(UUID value) {
        super(value);
    }

    public static LoanAgreementId next(IdGenerator ids) {
        return new LoanAgreementId(ids.next());
    }

    public static LoanAgreementId of(UUID value) {
        return new LoanAgreementId(value);
    }
}
