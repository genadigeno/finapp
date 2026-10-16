package com.finapp.bnpl;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * One version of a BNPL agreement's identity (`P12-TSK-002`; the table is `P12-TSK-013`'s) - the consumer reference
 * credit records when the agreement takes a decision up ({@code ConsumerKind.BNPL_AGREEMENT}, `P12-TSK-001`).
 */
public final class BnplAgreementId extends EntityId {

    private BnplAgreementId(UUID value) {
        super(value);
    }

    public static BnplAgreementId next(IdGenerator ids) {
        return new BnplAgreementId(ids.next());
    }

    public static BnplAgreementId of(UUID value) {
        return new BnplAgreementId(value);
    }
}
