package com.finapp.app.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The role-revocation door, {@code DELETE /v1/identities/{id}/roles/{role}} (`P15-TSK-032`, the design's Q1 as decided): what it
 * declares and what it answers for each outcome. The domain's rules - the last granter, the last role's session end, the record
 * - are {@code LastRoleEndsSessionsTest}'s and {@code LastRoleGranterTest}'s; the step-up at the real interceptor is
 * {@code RuledDoorsStepUpTest}'s; the permission is {@code RoutePermissionRegisterTest}'s register; the real rows over HTTP are
 * {@code IdentityAdministrationDatabaseTest}'s, owed (C14).
 */
@DisplayName("the role-revocation door: ROLE_ASSIGN on a MULTI_FACTOR session, its refusals recorded, idempotent in effect (P15-TSK-032)")
class RoleRevocationDoorTest {

    private static final Instant AT = Instant.parse("2026-11-12T09:00:00Z");
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(AT, ZoneOffset.UTC), new SecureRandom());
    private static final IdentityId ADMINISTRATOR = IdentityId.next(IDS);

    private IdentityAdministration.RoleRevocation outcome = IdentityAdministration.RoleRevocation.REVOKED;
    private final List<String> asked = new ArrayList<>();

    private final IdentityAdministrationService service = new StubAdministrationService() {

        @Override
        public IdentityAdministration.RoleRevocation revokeRole(IdentityId subject, RoleName role, IdentityId actor, String reason) {
            asked.add(subject.value() + " " + role + " by " + actor.value() + ": " + reason);
            return outcome;
        }
    };

    private final IdentityAdministrationController door = new IdentityAdministrationController(service);

    @Test
    @DisplayName("DELETE on the role, 204, ROLE_ASSIGN, MULTI_FACTOR and its refusals recorded, all declared on the handler")
    void theDoorDeclaresItsGuards() throws Exception {
        Method handler = handler();

        assertThat(handler.getAnnotation(DeleteMapping.class).value()).containsExactly("/{id}/roles/{role}");
        assertThat(handler.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ROLE_ASSIGN);
        assertThat(handler.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(handler.getAnnotation(RecordsRefusals.class)).as("refusals recorded as the grant's are").isNotNull();
    }

    @Test
    @DisplayName("revoked, and not held: the same 204 - the identity does not hold the role, as asked - the actor the session's,"
            + " the subject and the role the path's, the reason the body's")
    void revokedAndNotHeldAnswerAlike() {
        IdentityId subject = IdentityId.next(IDS);

        door.revokeRole(subject.value().toString(), RoleName.KYC_REVIEWER, new RoleRevocationRequest("left the desk, OPS-8812"),
                requestWithSession());
        outcome = IdentityAdministration.RoleRevocation.NOT_HELD;
        door.revokeRole(subject.value().toString(), RoleName.KYC_REVIEWER, new RoleRevocationRequest("left the desk, OPS-8812"),
                requestWithSession());

        assertThat(asked).containsOnly(subject.value() + " KYC_REVIEWER by " + ADMINISTRATOR.value() + ": left the desk, OPS-8812")
                .hasSize(2);
    }

    @Test
    @DisplayName("the last identity that can grant roles: 409 identity.LastRoleGranter, saying what the administrator does next")
    void theLastGranterAnswers409() {
        outcome = IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER;

        assertThatThrownBy(() -> revoke(IdentityId.next(IDS).value().toString()))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.LAST_ROLE_GRANTER);
                    assertThat(refused.errorCode().status()).isEqualTo(409);
                    assertThat(refused.errorCode().code()).isEqualTo("identity.LastRoleGranter");
                    assertThat(refused.clientDetail()).hasValue("grant this role to another person first.");
                });
    }

    @Test
    @DisplayName("oneself: 422 api.ValidationFailed, as the grant answers; nobody, or an identifier that names nobody: 404")
    void selfAndNobodyAnswerAsTheGrantDoes() {
        outcome = IdentityAdministration.RoleRevocation.SELF;
        assertThatThrownBy(() -> revoke(ADMINISTRATOR.value().toString()))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                    assertThat(refused.clientDetail()).hasValue("an administrator cannot revoke a role from their own identity");
                });

        outcome = IdentityAdministration.RoleRevocation.NOT_FOUND;
        assertThatThrownBy(() -> revoke(IdentityId.next(IDS).value().toString()))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));

        asked.clear();
        assertThatThrownBy(() -> revoke("not-a-uuid"))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
        assertThat(asked).as("a malformed identifier never reaches the domain").isEmpty();
    }

    @Test
    @DisplayName("the reason's bounds are the administrative bodies' own, the record's")
    void theReasonIsBoundedAsTheOthersAre() throws Exception {
        java.lang.reflect.Field reason = RoleRevocationRequest.class.getDeclaredField("reason");
        assertThat(reason.getAnnotation(jakarta.validation.constraints.Size.class).max()).isEqualTo(SuspensionRequest.REASON_MAX);
        assertThat(reason.getAnnotation(jakarta.validation.constraints.NotBlank.class)).isNotNull();
    }

    private void revoke(String subject) {
        door.revokeRole(subject, RoleName.ADMINISTRATOR, new RoleRevocationRequest("incident closed, OPS-8812"), requestWithSession());
    }

    private static Method handler() throws NoSuchMethodException {
        return IdentityAdministrationController.class.getMethod(
                "revokeRole", String.class, RoleName.class, RoleRevocationRequest.class, jakarta.servlet.http.HttpServletRequest.class);
    }

    private static MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION,
                Session.rehydrate(SessionId.next(IDS), ADMINISTRATOR, Sensitive.of("hash"),
                        AssuranceLevel.MULTI_FACTOR, SessionStatus.ACTIVE, AT, AT.plusSeconds(3600), AT.plusSeconds(86400), null, null));
        return request;
    }
}
