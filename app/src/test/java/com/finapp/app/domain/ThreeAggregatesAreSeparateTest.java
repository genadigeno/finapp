package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.LoginIdentifier;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.Party;
import com.finapp.party.PartyId;
import com.finapp.party.PartyKind;
import com.finapp.party.PartyName;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Party, Customer and Identity are three things, not one (ADR-0029, {@code P1-TSK-005}).
 *
 * <p>This is the task's acceptance criterion and Phase 1's exit criterion: <em>the three are
 * separately persisted with distinct lifecycles, proven by a test that fails if any two are
 * merged</em>. It lives in {@code app} because {@code app} is the only module that sees both
 * {@code party} and {@code identity} — which is itself part of what is being asserted.
 *
 * <h2>What "merged" would actually look like</h2>
 *
 * <p>Nobody merges three aggregates deliberately. It happens by way of the simplest first story:
 * one registration form, one row, one status. So the assertions below are the four shapes a merged
 * model cannot represent, written as the cases they are rather than as an abstract claim about
 * separation — a person who is not a customer, a customer who is not a person, a Party with two
 * logins, and lifecycles that move independently.
 *
 * <p>If any two were merged, at least one of these stops compiling or stops passing.
 */
@DisplayName("Party, Customer and Identity are three aggregates (ADR-0029)")
class ThreeAggregatesAreSeparateTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a Party can exist with no Customer relationship at all")
    void aPersonWhoIsNotACustomer() {
        // A beneficial owner. We must record that they exist - KYB requires it - and they are not
        // our customer and may never be. A merged model has nowhere to put them: every row would
        // be a customer by construction.
        Party beneficialOwner =
                Party.register(IDS, CLOCK, PartyKind.PERSON, new PartyName("Ada Lovelace"));

        assertThat(beneficialOwner.id()).isNotNull();
        // Nothing about a Party mentions a relationship, a status, or a login. That absence is the
        // assertion: a Party that carried a status would be a Customer wearing another name.
        assertThat(Party.class.getMethods())
                .as("Party must expose no lifecycle, because existence has no states")
                .noneMatch(method -> method.getName().contains("status"))
                .noneMatch(method -> method.getName().contains("suspend"))
                .noneMatch(method -> method.getName().contains("close"));
    }

    @Test
    @DisplayName("a Customer can be an organisation, which has no login and is not a person")
    void aCustomerWhoIsNotAPerson() {
        Party company =
                Party.register(
                        IDS, CLOCK, PartyKind.ORGANISATION, new PartyName("Analytical Engines Ltd"));
        Customer relationship = Customer.open(IDS, CLOCK, company.id());

        assertThat(company.kind()).isEqualTo(PartyKind.ORGANISATION);
        assertThat(relationship.partyId()).isEqualTo(company.id());
    }

    @Test
    @DisplayName("one Party holds two Identities: a retired login and its replacement")
    void onePartyWithTwoIdentities() {
        // The case that makes merging most obviously wrong. If Identity and Party were one row,
        // replacing a login would mean either editing the person or inventing a second person.
        Party person = Party.register(IDS, CLOCK, PartyKind.PERSON, new PartyName("Ada Lovelace"));

        Identity retired =
                Identity.create(IDS, CLOCK, person.id().value(), new LoginIdentifier("ada.old"))
                        .close(CLOCK);
        Identity current =
                Identity.create(IDS, CLOCK, person.id().value(), new LoginIdentifier("ada.new"));

        assertThat(retired.id()).isNotEqualTo(current.id());
        assertThat(retired.partyId()).isEqualTo(current.partyId()).isEqualTo(person.id().value());
        assertThat(retired.status()).isEqualTo(IdentityStatus.CLOSED);
        assertThat(current.canAuthenticate()).isTrue();
    }

    @Test
    @DisplayName("the lifecycles move independently: suspending a login is not suspending a relationship")
    void lifecyclesMoveIndependently() {
        // The property a shared status column would destroy, and the one a support ticket finds
        // first: a credential compromise suspends the login and must not suspend the commercial
        // relationship, and closing a relationship must not silently delete the record of who
        // logged in.
        Party person = Party.register(IDS, CLOCK, PartyKind.PERSON, new PartyName("Ada Lovelace"));
        Customer relationship = Customer.open(IDS, CLOCK, person.id()).activate(CLOCK);
        Identity login =
                Identity.create(IDS, CLOCK, person.id().value(), new LoginIdentifier("ada.l"));

        Identity suspendedLogin = login.suspend(CLOCK);

        assertThat(suspendedLogin.canAuthenticate()).isFalse();
        assertThat(relationship.status())
                .as("the relationship is untouched by what happened to the login")
                .isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    @DisplayName("the three identifier types are not interchangeable, and never compare equal")
    void identifiersAreNotInterchangeable() {
        // The compile-time half of the separation. EntityId includes the concrete class in its
        // identity, so two identifiers of different kinds carrying the same UUID are different
        // identifiers - which is what stops a PartyId reaching a customer_id column through a
        // mapping mistake that would otherwise type-check.
        //
        // That a PartyId cannot be PASSED where a CustomerId is required is enforced by javac and
        // is therefore not assertable at run time; it is proven by this file compiling at all,
        // since every call above passes the specific type.
        UUID shared = IDS.next();

        PartyId party = PartyId.of(shared);
        CustomerId customer = CustomerId.of(shared);
        IdentityId identity = IdentityId.of(shared);

        assertThat(party).isNotEqualTo(customer);
        assertThat(customer).isNotEqualTo(identity);
        assertThat(identity).isNotEqualTo(party);
        assertThat(party.value()).isEqualTo(customer.value()).isEqualTo(identity.value());
    }

    @Test
    @DisplayName("Identity references a Party by value, never by PartyId")
    void identityReferencesPartyByValue() throws Exception {
        // identity has no compile-time dependency on party (ADR-0029), so the reference is a plain
        // UUID. This method is exactly where someone would later "tidy" that into a PartyId, which
        // would require adding the dependency the two modules exist without - so the signature is
        // asserted rather than left to be noticed in review.
        //
        // The classpath half is PartyModuleIsolationTest and IdentityModuleIsolationTest.
        assertThat(Identity.class.getMethod("partyId").getReturnType())
                .as("a cross-module reference is a value, not a typed identifier")
                .isEqualTo(UUID.class);
    }

    @Test
    @DisplayName("neither aggregate carries the other's state, so there is nothing to disagree")
    void noAggregateCarriesAnothersState() {
        // The subtler merge: not one class, but one class quietly holding a copy of another's
        // status "for convenience". Two places to record one fact is two places that can disagree,
        // and the copy is the one nobody updates.
        assertThat(Customer.class.getMethods())
                .as("Customer must not carry an identity's state")
                .noneMatch(method -> method.getReturnType().equals(IdentityStatus.class));
        assertThat(Identity.class.getMethods())
                .as("Identity must not carry a customer's state")
                .noneMatch(method -> method.getReturnType().equals(CustomerStatus.class));
    }
}
