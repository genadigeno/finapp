package com.finapp.app.recovery;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("a role-holder's completion on the public surface is refused before its token is judged: nothing spent, nothing replaced")
    void staffCompletionIsRefused() {
        RecoveryWorld world = new RecoveryWorld(true);
        assertThat(world.complete(Surface.UNMARKED)).as("one answer, a wrong token's").isFalse();
        assertThat(world.recoveryCalls).as("the token is never presented to the store").isEmpty();
        assertThat(world.written).singleElement().satisfies(record ->
                assertThat(record.changeSummary()).contains("operation=POST /v1/recoveries/{id}/completion surface=unmarked"));
        assertThat(world.refusals("recovery_completion", "unmarked")).isEqualTo(1.0);
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
