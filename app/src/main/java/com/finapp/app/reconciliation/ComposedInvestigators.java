package com.finapp.app.reconciliation;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.PermissionName;
import com.finapp.reconciliation.Investigators;
import java.sql.Connection;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reconciliation's {@link Investigators} port over identity's public reads (the Phase 8 -> 9
 * transition, SEC-06; ADR-0064 - reconciliation compiles against no sibling but the ledger): a
 * break's assignee is an identity that exists, stands {@code ACTIVE}, and holds
 * {@link PermissionName#RECONCILIATION_INVESTIGATE} through a live role - the same
 * {@link Authorization#permits} the boundary asks of the investigator's own request, read on the
 * assignment's connection.
 */
@RequiredArgsConstructor
public final class ComposedInvestigators implements Investigators {

    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final Authorization authorization;

    @Override
    public boolean investigates(Connection unitOfWork, UUID principal) {
        IdentityId identity;
        try {
            identity = IdentityId.of(principal);
        } catch (IllegalArgumentException notAnIdentityIdentifier) {
            // A mistyped or foreign UUID (ADR-0013: identities are UUIDv7) names nobody - the
            // caller refuses it 422; it never reaches a read or a 500.
            return false;
        }
        return identities
                        .findById(unitOfWork, identity)
                        .filter(found -> found.status() == IdentityStatus.ACTIVE)
                        .isPresent()
                && authorization.permits(
                        unitOfWork, identity, PermissionName.RECONCILIATION_INVESTIGATE);
    }
}
