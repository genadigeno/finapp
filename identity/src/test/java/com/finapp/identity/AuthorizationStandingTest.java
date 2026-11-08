package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An identity's standing - the roles read once, deciding both the permission and the population an operator act is
 * recorded in (`P15-TSK-021`) - and the refused step-up's record.
 */
@DisplayName("an identity's standing and the refused step-up's record (P15-TSK-021)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class AuthorizationStandingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("an identity holding any live role is the operator population; one holding none is a customer")
    void holdingARoleMakesAPersonStaff() {
        assertThat(new Authorization.Standing(Set.of()).actorType()).isEqualTo(ActorType.CUSTOMER);
        for (RoleName role : RoleName.values()) {
            assertThat(new Authorization.Standing(Set.of(role)).actorType()).as(role.name()).isEqualTo(ActorType.EMPLOYEE);
        }
        assertThat(new Authorization.Standing(EnumSet.allOf(RoleName.class)).actorType()).isEqualTo(ActorType.EMPLOYEE);
    }

    @Test
    @DisplayName("the standing permits exactly what its roles grant, and nothing without a role")
    void theStandingPermitsWhatItsRolesGrant() {
        Authorization.Standing none = new Authorization.Standing(Set.of());
        Authorization.Standing ledger = new Authorization.Standing(Set.of(RoleName.LEDGER_OPERATOR));
        for (PermissionName permission : PermissionName.values()) {
            assertThat(none.permits(permission)).as(permission.name()).isFalse();
            assertThat(ledger.permits(permission)).as(permission.name())
                    .isEqualTo(RoleName.LEDGER_OPERATOR.permissions().contains(permission));
        }
    }

    @Test
    @DisplayName("one read decides both: the store is asked once, and permits agrees with the standing it read")
    void oneReadDecidesBoth() {
        AtomicInteger reads = new AtomicInteger();
        Authorization authorization = new Authorization(rolesAnswering(Set.of(RoleName.ADMINISTRATOR), reads), IDS, CLOCK,
                (uow, record) -> { });
        Authorization.Standing standing = authorization.standingOf(unanswering(), IdentityId.next(IDS));
        assertThat(reads).hasValue(1);
        assertThat(standing.actorType()).isEqualTo(ActorType.EMPLOYEE);
        assertThat(standing.permits(PermissionName.ROLE_ASSIGN)).isTrue();
        assertThat(authorization.permits(unanswering(), IdentityId.next(IDS), PermissionName.ROLE_ASSIGN)).isTrue();
        assertThat(reads).hasValue(2);
    }

    @Test
    @DisplayName("a refused step-up is recorded FAILED against the identity, naming the operation and nothing else")
    void aRefusedStepUpIsRecorded() {
        List<AuditRecord> written = new ArrayList<>();
        Authorization authorization = new Authorization(rolesAnswering(Set.of(), new AtomicInteger()), IDS, CLOCK,
                (uow, record) -> written.add(record));
        IdentityId identity = IdentityId.next(IDS);
        Actor operator = new Actor(identity.value().toString(), ActorType.EMPLOYEE);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope acting = SecurityContext.enter(operator)) {
            authorization.recordStepUpRefusal(unanswering(), identity, "POST /v1/ledger/adjustments/{id}/approval");
        }
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.STEP_UP_REFUSED);
            assertThat(record.actor()).isEqualTo(operator);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(identity.value().toString());
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary()).contains("operation=POST /v1/ledger/adjustments/{id}/approval");
        });
    }

    @Test
    @DisplayName("a refused step-up outside an actor's scope is not recorded as anybody: it fails")
    void noActorNoRecord() {
        Authorization authorization = new Authorization(rolesAnswering(Set.of(), new AtomicInteger()), IDS, CLOCK,
                (uow, record) -> { });
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            assertThatThrownBy(() -> authorization.recordStepUpRefusal(unanswering(), IdentityId.next(IDS), "GET /x"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static RoleAssignmentStore<Connection> rolesAnswering(Set<RoleName> roles, AtomicInteger reads) {
        return (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(RoleAssignmentStore.class.getClassLoader(),
                new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("liveRolesOf")) {
                        reads.incrementAndGet();
                        return roles;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Connection unanswering() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
