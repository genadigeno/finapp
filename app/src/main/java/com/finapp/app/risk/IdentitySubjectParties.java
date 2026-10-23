package com.finapp.app.risk;

import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Risk's party join over identity's store (`P13-TSK-007`; {@link RiskSubjectParties}; the composition root's half,
 * because {@code risk} cannot see {@code identity}): the party behind a signal's subject, for the customer risk
 * profile's permit pull - a {@link SubjectKind#PARTY} is itself, an {@link SubjectKind#IDENTITY} the party identity
 * holds by value (the read {@code IdentityActingParty} makes). Read on the inbox handler's own connection, never cached.
 *
 * <p><strong>The other kinds answer empty</strong> (`DESIGN-007` D5): an account, a credit agreement, a merchant, a
 * beneficiary has no read by its own id without its owner in its module today, and a device or a session names no
 * party at all; their signals are recorded and pull no permit. The account's and the merchant's joins arrive with
 * `-020` (which must place a monitored transaction's subject anyway); a reference that is not an identity's shape
 * (never a UUIDv7) places nothing rather than stalling the inbox.
 */
public final class IdentitySubjectParties implements RiskSubjectParties {

    private final IdentityStore<Connection> identities;

    public IdentitySubjectParties(IdentityStore<Connection> identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public Optional<UUID> partyOf(Connection unitOfWork, SignalSubject subject) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(subject, "subject");
        return switch (subject.kind()) {
            case PARTY -> Optional.of(UUID.fromString(subject.ref()));
            case IDENTITY -> identity(subject.ref()).flatMap(id -> identities.findById(unitOfWork, id))
                    .map(found -> found.partyId());
            case ACCOUNT, DEVICE, MERCHANT, BENEFICIARY, SESSION, CREDIT_AGREEMENT -> Optional.empty();
        };
    }

    private static Optional<IdentityId> identity(String ref) {
        try {
            return Optional.of(IdentityId.of(UUID.fromString(ref)));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }
}
