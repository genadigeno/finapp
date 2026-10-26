package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.risk.DeviceHash;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Risk's party join (`P13-TSK-007`; `DESIGN-007` D5): a party is itself, an identity's party is identity's, every other
 * kind is placed in no party yet - and an identity never registered, or a reference that cannot be an identity's,
 * places nothing rather than throwing into the inbox. `P13-TSK-016`: the case's accountable party places a merchant's
 * organisation too (`DESIGN-016` D6), never through the hint's {@code partyOf}.
 */
@DisplayName("risk's party join over identity (P13-TSK-007)")
class IdentitySubjectPartiesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            IdentitySubjectPartiesTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    @Test
    @DisplayName("a party is itself; an identity is its party; an unknown identity, a non-v7 reference and every other"
            + " kind place nothing")
    void thePartyBehindEachSubject() {
        UUID party = IDS.next();
        Identity identity = Identity.create(IDS, CLOCK, party, new LoginIdentifier("profile.join"));
        IdentitySubjectParties join = new IdentitySubjectParties(identities(Map.of(identity.id(), identity)), merchants(Map.of()));

        assertThat(join.partyOf(UOW, SignalSubject.of(SubjectKind.PARTY, party))).contains(party);
        assertThat(join.partyOf(UOW, SignalSubject.of(SubjectKind.IDENTITY, identity.id().value()))).contains(party);
        assertThat(join.partyOf(UOW, SignalSubject.of(SubjectKind.IDENTITY, IDS.next()))).as("never registered").isEmpty();
        assertThat(join.partyOf(UOW, SignalSubject.of(SubjectKind.IDENTITY, UUID.randomUUID()))).as("a v4 is no identity")
                .isEmpty();
        for (SubjectKind kind : new SubjectKind[] {SubjectKind.ACCOUNT, SubjectKind.MERCHANT, SubjectKind.BENEFICIARY,
                SubjectKind.SESSION, SubjectKind.CREDIT_AGREEMENT}) {
            assertThat(join.partyOf(UOW, SignalSubject.of(kind, IDS.next()))).as(kind.name()).isEmpty();
        }
        assertThat(join.partyOf(UOW, SignalSubject.device(DeviceHash.of("a phone")))).isEmpty();
    }

    @Test
    @DisplayName("P13-TSK-013, the reverse join: a party's identities through identity's own read, bounded, in the"
            + " store's order - none for a party that holds none")
    void theIdentitiesAPartyHolds() {
        UUID party = IDS.next();
        Identity first = Identity.create(IDS, CLOCK, party, new LoginIdentifier("profile.first"));
        Identity second = Identity.create(IDS, CLOCK, party, new LoginIdentifier("profile.second"));
        Identity stranger = Identity.create(IDS, CLOCK, IDS.next(), new LoginIdentifier("profile.stranger"));
        List<Integer> limits = new ArrayList<>();
        IdentitySubjectParties join = new IdentitySubjectParties(identities(Map.of(first.id(), first,
                second.id(), second, stranger.id(), stranger), limits), merchants(Map.of()));

        assertThat(join.identitiesOf(UOW, party)).containsExactlyInAnyOrder(first.id().value(), second.id().value());
        assertThat(join.identitiesOf(UOW, IDS.next())).isEmpty();
        assertThat(limits).as("always bounded by the port's bound").containsOnly(RiskPartyIdentities.BOUND);
    }

    @Test
    @DisplayName("P13-TSK-016, the accountable party: a merchant's organisation party for a case act's self-dealing"
            + " judgement - never through partyOf (the profile hint's join), an unknown merchant placing nothing; every"
            + " other kind as partyOf places it")
    void theAccountablePartyPlacesAMerchant() {
        UUID organisation = IDS.next();
        UUID merchant = IDS.next();
        UUID party = IDS.next();
        Identity identity = Identity.create(IDS, CLOCK, party, new LoginIdentifier("profile.accountable"));
        IdentitySubjectParties join = new IdentitySubjectParties(identities(Map.of(identity.id(), identity)),
                merchants(Map.of(merchant, organisation)));

        SignalSubject merchantSubject = SignalSubject.of(SubjectKind.MERCHANT, merchant);
        assertThat(join.accountablePartyOf(UOW, merchantSubject)).contains(organisation);
        assertThat(join.partyOf(UOW, merchantSubject)).as("never the profile hint's join").isEmpty();
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.MERCHANT, IDS.next()))).isEmpty();
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.PARTY, party))).contains(party);
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.IDENTITY, identity.id().value())))
                .contains(party);
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.ACCOUNT, IDS.next()))).isEmpty();
    }

    @Test
    @DisplayName("P13-TSK-025, the accountable party: a credit agreement's party - a loan's borrower, else a BNPL"
            + " obligation's customer - for a default's collections case; never through partyOf (a default pulls no"
            + " profile permit); an unknown agreement or a non-v7 reference placing nothing, the obligation never asked"
            + " once the loan answered")
    void theAccountablePartyPlacesACreditAgreement() {
        UUID borrower = IDS.next();
        UUID customer = IDS.next();
        UUID loan = IDS.next();
        UUID obligation = IDS.next();
        List<String> asked = new ArrayList<>();
        CreditAgreementParties agreements = new CreditAgreementParties() {
            @Override
            public Optional<UUID> partyOfLoan(Connection unitOfWork, com.finapp.lending.LoanId id) {
                asked.add("loan");
                return id.value().equals(loan) ? Optional.of(borrower) : Optional.empty();
            }

            @Override
            public Optional<UUID> partyOfObligation(Connection unitOfWork, com.finapp.bnpl.BnplObligationId id) {
                asked.add("obligation");
                return id.value().equals(obligation) ? Optional.of(customer) : Optional.empty();
            }
        };
        IdentitySubjectParties join = new IdentitySubjectParties(identities(Map.of()), merchants(Map.of()), agreements);

        SignalSubject loanSubject = SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan);
        assertThat(join.accountablePartyOf(UOW, loanSubject)).contains(borrower);
        assertThat(asked).as("the loan answered: the obligation is never asked").containsExactly("loan");
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, obligation)))
                .contains(customer);
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, IDS.next())))
                .as("no such agreement").isEmpty();
        asked.clear();
        assertThat(join.accountablePartyOf(UOW, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, UUID.randomUUID())))
                .as("a v4 is no agreement's").isEmpty();
        assertThat(asked).as("nothing read for a reference no agreement can have").isEmpty();
        assertThat(join.partyOf(UOW, loanSubject)).as("never the profile hint's join").isEmpty();
        assertThat(new IdentitySubjectParties(identities(Map.of()), merchants(Map.of()))
                .accountablePartyOf(UOW, loanSubject)).as("the suites' join places no agreement").isEmpty();
    }

    /** Merchant's store answering {@code findById} with a merchant whose organisation party is the map's. */
    @SuppressWarnings("unchecked")
    private static com.finapp.merchant.MerchantStore<Connection> merchants(Map<UUID, UUID> organisations) {
        return (com.finapp.merchant.MerchantStore<Connection>) Proxy.newProxyInstance(
                IdentitySubjectPartiesTest.class.getClassLoader(),
                new Class<?>[] {com.finapp.merchant.MerchantStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        UUID id = ((com.finapp.merchant.MerchantId) args[1]).value();
                        return Optional.ofNullable(organisations.get(id)).map(organisation -> merchant(id, organisation));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static com.finapp.merchant.Merchant merchant(UUID id, UUID organisation) {
        return com.finapp.merchant.Merchant.rehydrate(com.finapp.merchant.MerchantId.of(id), organisation, "Acme Ltd",
                "Acme", com.finapp.sharedkernel.money.CurrencyCode.of("EUR"), com.finapp.merchant.MerchantStatus.ACTIVE,
                CLOCK.instant(), CLOCK.instant());
    }

    private static IdentityStore<Connection> identities(Map<IdentityId, Identity> known) {
        return identities(known, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private static IdentityStore<Connection> identities(Map<IdentityId, Identity> known, List<Integer> limits) {
        return (IdentityStore<Connection>) Proxy.newProxyInstance(IdentitySubjectPartiesTest.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.ofNullable(known.get((IdentityId) args[1]));
                    }
                    if (method.getName().equals("findIdsByParty")) {
                        limits.add((Integer) args[2]);
                        return known.values().stream().filter(identity -> identity.partyId().equals(args[1]))
                                .map(Identity::id).sorted(java.util.Comparator.comparing(IdentityId::value))
                                .limit((Integer) args[2]).toList();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
