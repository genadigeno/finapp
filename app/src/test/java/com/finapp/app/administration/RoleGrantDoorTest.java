package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The role grant's refusal and the check of standing holdings, at their doors (`P15-TSK-039`). The grant's rule is
 * {@code RoleGrantNeedsAFactorTest}'s; a real row is {@code IdentityAdministrationDatabaseTest}'s, owed. Since `P15-TSK-032` it
 * also holds that the revocation door is the only production path that takes a role away, as the grant's is the only one that
 * gives one; that door's own answers are {@code RoleRevocationDoorTest}'s.
 */
@org.junit.jupiter.api.Tag("architecture")
@DisplayName("the grant refused without a factor, and the standing holdings named, at the doors (P15-TSK-039)")
class RoleGrantDoorTest {

    private static final Instant AT = Instant.parse("2026-11-09T09:00:00Z");
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(AT, ZoneOffset.UTC), new SecureRandom());

    private IdentityAdministration.RoleGrant outcome = IdentityAdministration.RoleGrant.FACTOR_REQUIRED;
    private int served;
    private final List<Integer> limits = new ArrayList<>();

    private final IdentityAdministrationService service = new StubAdministrationService() {

        @Override
        public IdentityAdministration.RoleGrant assignRole(IdentityId subject, RoleName role, IdentityId actor, String reason) {
            return outcome;
        }

        @Override
        public IdentityAdministration.FactorlessHoldings factorlessHoldings(int limit) {
            limits.add(limit);
            List<RoleAssignmentStore.FactorlessHolding> holdings = new ArrayList<>();
            for (int i = 0; i < Math.min(served, limit); i++) {
                holdings.add(new RoleAssignmentStore.FactorlessHolding(IdentityId.next(IDS), RoleName.LEDGER_OPERATOR, AT));
            }
            return new IdentityAdministration.FactorlessHoldings(holdings, served > limit);
        }
    };

    @Test
    @DisplayName("a refused grant answers 409 identity.FactorRequiredForRole and says what the administrator's next step is")
    void aRefusedGrantAnswers() {
        IdentityAdministrationController door = new IdentityAdministrationController(service);
        RoleAssignmentRequest body = new RoleAssignmentRequest(RoleName.LEDGER_OPERATOR, "needs it for the payout desk");

        assertThatThrownBy(() -> door.assignRole(IDS.next().toString(), body, requestWithSession()))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.FACTOR_REQUIRED_FOR_ROLE);
                    assertThat(refused.errorCode().status()).isEqualTo(409);
                    assertThat(refused.errorCode().code()).isEqualTo("identity.FactorRequiredForRole");
                    assertThat(refused.clientDetail()).hasValue("this role needs its holder to enrol a second factor first.");
                });
    }

    @Test
    @DisplayName("the refusal is on the record: the grant's door declares it")
    void theGrantsDoorRecordsItsRefusals() throws Exception {
        assertThat(IdentityAdministrationController.class
                        .getMethod("assignRole", String.class, RoleAssignmentRequest.class, jakarta.servlet.http.HttpServletRequest.class)
                        .getAnnotation(RecordsRefusals.class))
                .isNotNull();
    }

    @Test
    @DisplayName("the check names each holding and says when there were more than it served")
    void theCheckNamesTheHoldings() {
        AccessReviewController door = new AccessReviewController(service);

        served = 2;
        assertThat(door.listFactorlessHoldings().holdings()).hasSize(2);
        assertThat(door.listFactorlessHoldings().truncated()).isFalse();

        served = 500;
        var many = door.listFactorlessHoldings();
        assertThat(many.holdings()).hasSize(200);
        assertThat(many.truncated()).isTrue();
        assertThat(limits).as("the page the door serves, and not one row past it").allMatch(limit -> limit == 200);
    }

    @Test
    @DisplayName("the only production path to a role is the one that checks the factor and ends a first role's sessions: no other"
            + " caller of Authorization.assign, by call or by method reference, and that one through the overload that carries the"
            + " consequence (P15-TSK-043)")
    void theOnlyGrantIsTheOneThatChecks() {
        theOnlyGrantIsTheDoors(production());
    }

    @Test
    @DisplayName("the only production path that takes a role away is the revocation door's: no other caller of Authorization.revoke,"
            + " by call or by method reference, and that one through the overload that carries the last role's session end;"
            + " the store's revoke reached from Authorization alone (P15-TSK-032)")
    void theOnlyRevocationIsTheDoors() {
        theOnlyRevocationIsTheDoors(production());
    }

    @Test
    @DisplayName("the rules can fail: a method reference to either five-argument overload, which is no method call in the bytecode,"
            + " is caught (P15-TSK-032's review)")
    void aMethodReferenceIsCaught() {
        com.tngtech.archunit.core.domain.JavaClasses planted =
                new com.tngtech.archunit.core.importer.ClassFileImporter().importClasses(PlantedReferences.class);
        assertThat(accessesTo(planted, com.finapp.identity.Authorization.class, "revoke")).as("the reference is seen").hasSize(1);
        assertThat(accessesTo(planted, com.finapp.identity.Authorization.class, "assign")).as("the reference is seen").hasSize(1);
        assertThatThrownBy(() -> theOnlyRevocationIsTheDoors(planted)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> theOnlyGrantIsTheDoors(planted)).isInstanceOf(AssertionError.class);
    }

    private static void theOnlyGrantIsTheDoors(com.tngtech.archunit.core.domain.JavaClasses classes) {
        var accesses = accessesTo(classes, com.finapp.identity.Authorization.class, "assign");
        assertThat(accesses.stream().map(access -> access.getOriginOwner().getSimpleName()).collect(java.util.stream.Collectors.toSet()))
                .as("a new caller of Authorization.assign is a grant that skips the factor check")
                .containsExactly("IdentityAdministration");
        // The five-argument assign stays for the database fixtures, ninety-nine calls in fifty classes, and ends no session: a
        // production grant through it would make a first role that leaves the customer's sessions serving (P15-TSK-043's review).
        assertThat(accesses).as("not vacuous").isNotEmpty().allSatisfy(access -> assertThat(access.getTarget().getRawParameterTypes())
                .as("%s reaches the overload that runs the grant's consequence", access.getOrigin().getFullName())
                .hasSize(6)
                .last().satisfies(consequence -> assertThat(consequence.isEquivalentTo(java.util.function.Function.class)).isTrue()));
    }

    private static void theOnlyRevocationIsTheDoors(com.tngtech.archunit.core.domain.JavaClasses classes) {
        var accesses = accessesTo(classes, com.finapp.identity.Authorization.class, "revoke");
        assertThat(accesses.stream().map(access -> access.getOrigin().getFullName()).collect(java.util.stream.Collectors.toSet()))
                .as("a new caller of Authorization.revoke takes a role away past the last-granter rule and the last role's session end")
                .singleElement().satisfies(origin -> assertThat(origin).startsWith(
                        com.finapp.identity.IdentityAdministration.class.getName() + ".revokeRole("));
        // The five-argument revoke stays for the database fixtures and ends no session: a production revocation through it would
        // leave a former member of staff's operator sessions serving.
        assertThat(accesses).as("not vacuous").isNotEmpty().allSatisfy(access -> assertThat(access.getTarget().getRawParameterTypes())
                .as("%s reaches the overload that runs the revocation's consequence", access.getOrigin().getFullName())
                .hasSize(6)
                .last().satisfies(consequence -> assertThat(consequence.isEquivalentTo(java.util.function.Function.class)).isTrue()));
        // And the store's revoke has one caller, Authorization's one body: no path marks a row around both.
        assertThat(accessesTo(classes, com.finapp.identity.RoleAssignmentStore.class, "revoke").stream()
                        .map(access -> access.getOriginOwner().getSimpleName())
                        .collect(java.util.stream.Collectors.toSet()))
                .containsExactly("Authorization");
    }

    private static com.tngtech.archunit.core.domain.JavaClasses production() {
        return new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }

    /**
     * Every access to a method named {@code name} of {@code owner} or a subtype: a call, or a method reference, which is an
     * {@code invokedynamic} and no call in the bytecode ({@code NoAmbientTimeRulesTest}'s finding). The compiler's bridge from a
     * generic interface to its implementation is no caller.
     */
    private static java.util.List<com.tngtech.archunit.core.domain.JavaCodeUnitAccess<?>> accessesTo(
            com.tngtech.archunit.core.domain.JavaClasses classes, Class<?> owner, String name) {
        return classes.stream()
                .flatMap(javaClass -> javaClass.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access instanceof com.tngtech.archunit.core.domain.JavaMethodCall
                        || access instanceof com.tngtech.archunit.core.domain.JavaMethodReference)
                .filter(access -> access.getName().equals(name) && access.getTargetOwner().isAssignableTo(owner))
                .filter(access -> !access.getOrigin().getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.BRIDGE))
                .<com.tngtech.archunit.core.domain.JavaCodeUnitAccess<?>>map(access -> access)
                .toList();
    }

    /** Two references to the five-argument overloads, which a rule reading only method calls would pass. */
    static final class PlantedReferences {

        interface FiveArguments {
            boolean apply(java.sql.Connection unitOfWork, IdentityId identity, RoleName role, IdentityId by, String reason);
        }

        static FiveArguments revokes(com.finapp.identity.Authorization authorization) {
            return authorization::revoke;
        }

        static FiveArguments assigns(com.finapp.identity.Authorization authorization) {
            return authorization::assign;
        }
    }

    private static MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION,
                Session.rehydrate(SessionId.next(IDS), IdentityId.next(IDS), Sensitive.of("hash"),
                        AssuranceLevel.PASSWORD, SessionStatus.ACTIVE, AT, AT.plusSeconds(3600), AT.plusSeconds(86400), null, null));
        return request;
    }
}
