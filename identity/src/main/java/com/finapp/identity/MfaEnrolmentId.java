package com.finapp.identity;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/** An MFA enrolment's aggregate identifier (`P1-TSK-017`). Not secret, and never presented. */
public final class MfaEnrolmentId extends EntityId {

    private MfaEnrolmentId(UUID value) {
        super(value);
    }

    public static MfaEnrolmentId next(IdGenerator ids) {
        return new MfaEnrolmentId(ids.next());
    }

    public static MfaEnrolmentId of(UUID value) {
        return new MfaEnrolmentId(value);
    }
}
