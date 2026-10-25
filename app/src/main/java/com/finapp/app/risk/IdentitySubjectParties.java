package com.finapp.app.risk;

import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStore;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import java.sql.Connection;
import java.util.List;
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
 * party at all; their signals are recorded and pull no permit. `-020` built the account's join for the monitoring rules
 * - {@link AccountMonitoredParties}, over accounts' system read of an agreement's customer - and deliberately did not
 * wire it here: every AML alert's subject is the {@code PARTY}, which this join already places, and an {@code ACCOUNT}
 * placed here would make every account signal pull its party's profile permit (an owner's choice, recorded); a
 * reference that is not an identity's shape (never a UUIDv7) places nothing rather than stalling the inbox.
 *
 * <p><strong>The reverse</strong> (`P13-TSK-013`): {@link RiskPartyIdentities} - the identities a party holds, for the
 * profile leg's account-takeover anchors (the signals name the identity; the leg judges the party).
 *
 * <p><strong>The accountable party</strong> (`P13-TSK-016`, {@code DESIGN-016} D6): a case act's self-dealing judgement
 * also places a {@link SubjectKind#MERCHANT} - its organisation party, merchant's {@code partyRef} read by id - so a
 * {@code MERCHANT_ABUSE} case is workable. Deliberately <em>not</em> {@link #partyOf}: that join drives `-007`'s profile
 * hint, and a merchant placed there would enrol every onboarded merchant's organisation in the profile sweep. The
 * merchant's people are not known to the platform (merchant access is by API key), so an analyst is refused only an
 * organisation's case that is their own party's - recorded for the owner.
 */
public final class IdentitySubjectParties implements RiskSubjectParties, RiskPartyIdentities {

    private final IdentityStore<Connection> identities;
    private final MerchantStore<Connection> merchants;

    public IdentitySubjectParties(IdentityStore<Connection> identities, MerchantStore<Connection> merchants) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.merchants = Objects.requireNonNull(merchants, "merchants");
    }

    @Override
    public Optional<UUID> accountablePartyOf(Connection unitOfWork, SignalSubject subject) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(subject, "subject");
        if (subject.kind() == SubjectKind.MERCHANT) {
            return merchants.findById(unitOfWork, MerchantId.of(UUID.fromString(subject.ref())))
                    .map(Merchant::partyRef);
        }
        return partyOf(unitOfWork, subject);
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

    /**
     * The reverse join (`P13-TSK-013`; {@link RiskPartyIdentities}): the identities {@code partyId} holds - identity's
     * {@code findIdsByParty}, every status, at most {@link RiskPartyIdentities#BOUND} - for the profile sweep's
     * account-takeover anchors. Read on the recompute's connection under the profile head's lock, never cached.
     */
    @Override
    public List<UUID> identitiesOf(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        return identities.findIdsByParty(unitOfWork, partyId, RiskPartyIdentities.BOUND).stream()
                .map(IdentityId::value).toList();
    }

    private static Optional<IdentityId> identity(String ref) {
        try {
            return Optional.of(IdentityId.of(UUID.fromString(ref)));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }
}
