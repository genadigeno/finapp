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
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The role grant's refusal and the check of standing holdings, at their doors (`P15-TSK-039`). The grant's rule is
 * {@code RoleGrantNeedsAFactorTest}'s; a real row is {@code IdentityAdministrationDatabaseTest}'s, owed.
 */
@DisplayName("the grant refused without a factor, and the standing holdings named, at the doors (P15-TSK-039)")
class RoleGrantDoorTest {

    private static final Instant AT = Instant.parse("2026-11-09T09:00:00Z");
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(AT, ZoneOffset.UTC), new SecureRandom());

    private IdentityAdministration.RoleGrant outcome = IdentityAdministration.RoleGrant.FACTOR_REQUIRED;
    private int served;
    private final List<Integer> limits = new ArrayList<>();

    private final IdentityAdministrationService service = new IdentityAdministrationService(
            new SpringObjenesis().newInstance(IdentityAdministration.class), new TransactionTemplate(),
            (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> null)) {

        @Override
        public IdentityAdministration.RoleGrant assignRole(IdentityId subject, RoleName role, IdentityId actor, String reason) {
            return outcome;
        }

        @Override
        public List<RoleAssignmentStore.FactorlessHolding> factorlessHoldings(int limit) {
            limits.add(limit);
            List<RoleAssignmentStore.FactorlessHolding> holdings = new ArrayList<>();
            for (int i = 0; i < Math.min(served, limit); i++) {
                holdings.add(new RoleAssignmentStore.FactorlessHolding(IdentityId.next(IDS), RoleName.LEDGER_OPERATOR, AT));
            }
            return holdings;
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
        assertThat(limits).allMatch(limit -> limit == 201);
    }

    @Test
    @DisplayName("the only production path to a role is the one that checks the factor: no other caller of Authorization.assign")
    void theOnlyGrantIsTheOneThatChecks() {
        var callers = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp")
                .stream()
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .filter(call -> call.getName().equals("assign")
                        && call.getTargetOwner().isEquivalentTo(com.finapp.identity.Authorization.class))
                .map(call -> call.getOriginOwner().getSimpleName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(callers)
                .as("a new caller of Authorization.assign is a grant that skips the factor check")
                .containsExactly("IdentityAdministration");
    }

    private static MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION,
                Session.rehydrate(SessionId.next(IDS), IdentityId.next(IDS), Sensitive.of("hash"),
                        AssuranceLevel.PASSWORD, SessionStatus.ACTIVE, AT, AT.plusSeconds(3600), AT.plusSeconds(86400), null, null));
        return request;
    }
}
