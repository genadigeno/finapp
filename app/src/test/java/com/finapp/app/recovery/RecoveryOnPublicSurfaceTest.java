package com.finapp.app.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.surface.RequestSurfaces;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import org.springframework.mock.web.MockHttpServletRequest;

import com.finapp.app.surface.Surface;
import com.finapp.app.surface.SurfaceRefusalMeters;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Authorization;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuditAction;
import com.finapp.identity.IdentityId;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.identity.RecoveryRequestStore;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff recover on the operator surface alone (`P15-TSK-042`; {@code THREAT_MODEL.md} T3, T31; {@code INV-IDN-06}).
 *
 * <p>The real {@link RecoveryApplicationService} and {@link RecoveryService} over a recovery store that records every call: on the
 * public surface a role-holder's initiation reaches neither the cancellation nor the creation of a request, and a completion
 * never reaches the token's judgement, so nothing is spent and no credential replaced. The answers are the controller's
 * unchanged ones, a {@code 202} and the one refusal of a wrong token. What real rows do is
 * {@code OperatorsRefusedOnPublicDatabaseTest}'s, owed.
 */
@DisplayName("recovery refuses staff on the public surface before anything is cancelled, created or spent (P15-TSK-042)")
class RecoveryOnPublicSurfaceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a role-holder's initiation on the public surface touches no recovery request, and is recorded")
    void staffInitiationIsRefused() {
        RecoveryWorld world = new RecoveryWorld(true);
        world.initiate(Surface.PUBLIC);
        assertThat(world.recoveryCalls).as("neither the cancellation of a live request nor a new one").isEmpty();
        assertThat(world.written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.PUBLIC_SURFACE_REFUSED);
            assertThat(record.targetId()).isEqualTo(world.holder.value().toString());
            assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM);
            assertThat(record.changeSummary()).contains("operation=POST /v1/recoveries surface=public");
        });
        assertThat(world.refusals("recovery", "public")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a role-holder's completion on the public surface, and behind the edge with no marker, is refused before its token is judged: nothing spent, nothing replaced")
    void staffCompletionIsRefused() {
        for (Surface refusing : java.util.List.of(Surface.PUBLIC, Surface.UNMARKED)) {
            RecoveryWorld world = new RecoveryWorld(true);
            assertThat(world.complete(refusing)).as("%s: one answer, a wrong token's", refusing).isFalse();
            assertThat(world.recoveryCalls).as("%s: the token is never presented to the store", refusing).isEmpty();
            assertThat(world.written).singleElement().satisfies(record -> assertThat(record.changeSummary())
                    .contains("operation=POST /v1/recoveries/{id}/completion surface=" + refusing.tag()));
            assertThat(world.refusals("recovery_completion", refusing.tag())).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("the controller hands the service the surface its own request carries, at both doors: marked public, staff are refused;"
            + " marked operator, they recover")
    void theControllerPassesTheRequestsSurface() {
        RecoveryWorld world = new RecoveryWorld(true);
        RecoveryController controller = new RecoveryController(world.service(), new RequestSurfaces(false));
        world.inAFlow(() -> controller.initiateRecovery(new RecoveryInitiationRequest("someone"), marked("public")));
        assertThat(world.recoveryCalls).isEmpty();
        String id = RecoveryRequestId.next(IDS).value().toString();
        RecoveryCompletionRequest completion =
                new RecoveryCompletionRequest(Sensitive.of("a-token"), Sensitive.of("a new password of length"));
        assertThatThrownBy(() -> world.inAFlow(() -> controller.completeRecovery(id, completion, marked("public"))))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
        assertThat(world.recoveryCalls).isEmpty();
        assertThat(world.written).extracting(record -> record.changeSummary().orElseThrow())
                .containsExactly("operation=POST /v1/recoveries surface=public",
                        "operation=POST /v1/recoveries/{id}/completion surface=public");

        world.inAFlow(() -> controller.initiateRecovery(new RecoveryInitiationRequest("someone"), marked("operator")));
        assertThat(world.recoveryCalls).as("the operator edge's request reaches the store").containsExactly("cancelLiveFor", "initiate");
    }

    private static MockHttpServletRequest marked(String surface) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/recoveries");
        request.addHeader(RequestSurfaces.HEADER, surface);
        return request;
    }

    @Test
    @DisplayName("a customer's recovery on the public surface goes on as before: the role read, then the request's own statements")
    void aCustomerRecovers() {
        RecoveryWorld world = new RecoveryWorld(false);
        world.initiate(Surface.PUBLIC);
        assertThat(world.roleReads).hasValue(1);
        assertThat(world.recoveryCalls).containsExactly("cancelLiveFor", "initiate");
        world.complete(Surface.PUBLIC);
        assertThat(world.recoveryCalls).containsExactly("cancelLiveFor", "initiate", "consume");
        assertThat(world.written).isEmpty();
    }

    @Test
    @DisplayName("on the operator surface staff recover, and no role is read")
    void staffRecoverOnTheOperatorSurface() {
        RecoveryWorld world = new RecoveryWorld(true);
        world.initiate(Surface.OPERATOR);
        world.complete(Surface.OPERATOR);
        assertThat(world.roleReads).hasValue(0);
        assertThat(world.recoveryCalls).containsExactly("cancelLiveFor", "initiate", "consume");
        assertThat(world.written).isEmpty();
    }

}
