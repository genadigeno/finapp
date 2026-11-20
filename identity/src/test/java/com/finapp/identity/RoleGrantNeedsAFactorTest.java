package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

/**
 * A role that can redirect a merchant's money is refused to an identity with no factor (`P15-TSK-039`,
 * {@code PRIVILEGED_ACCESS_REVIEW.md} §6). The real row and the concurrency of the grant are
 * {@code IdentityAdministrationDatabaseTest}'s, owed.
 */
@DisplayName("a factor is enrolled before a role that can redirect a merchant's money is granted (P15-TSK-039)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class RoleGrantNeedsAFactorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final IdentityId ADMINISTRATOR = IdentityId.next(IDS);
    private static final IdentityId SUBJECT = IdentityId.next(IDS);
    private static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    private final AtomicInteger grants = new AtomicInteger();
    private final AtomicInteger factorReads = new AtomicInteger();
    private final List<AuditRecord> written = new ArrayList<>();
    private boolean enrolled;
    private int standing = 1;

    @Test
    @DisplayName("every role that can act needs one (P17-TSK-102: every act asks for a second factor, so a factorless holder's"
            + " password would enrol a thief's), and every role that reads only behind a factor (P17-TSK-017) - today all of them")
    void whichRolesNeedAFactor() {
        assertThat(Arrays.stream(RoleName.values()).filter(RoleName::requiresEnrolledFactor).collect(Collectors.toSet()))
                .as("the rule is the permissions' own: a role needs a factor exactly when one of them guards an act or reads only"
                        + " behind a factor")
                .isEqualTo(Arrays.stream(RoleName.values())
                        .filter(role -> role.permissions().stream()
                                .anyMatch(permission -> permission.guardsAnAct() || permission.readsBehindAFactor()))
                        .collect(Collectors.toSet()))
                .as("and today that is every role")
                .isEqualTo(Set.of(RoleName.values()));
        assertThat(Set.of(RoleName.MERCHANT_ADMINISTRATOR, RoleName.LEDGER_OPERATOR, RoleName.ADMINISTRATOR, RoleName.RISK_ANALYST))
                .as("the four that needed one before P17-TSK-102 still do")
                .allMatch(RoleName::requiresEnrolledFactor);
    }

    @Test
    @DisplayName("a permission that only reads needs no factor, and one added later guards an act until somebody says otherwise")
    void onlyTheReadsNeedNone() {
        assertThat(Arrays.stream(PermissionName.values()).filter(permission -> !permission.guardsAnAct()))
                .containsExactlyInAnyOrder(PermissionName.ACCOUNTING_READ, PermissionName.FX_INVESTIGATE, PermissionName.AUDIT_READ,
                        PermissionName.ACCOUNT_READ, PermissionName.TRANSFER_READ,
                        // P17-TSK-032: every route under it reads.
                        PermissionName.PAYMENT_READ);
    }

    @Test
    @DisplayName("no factor: refused, nothing granted, nothing written - every role, an administrator's and a risk approver's"
            + " among them (P15-TSK-032, P17-TSK-102)")
    void aGrantWithNoFactorIsRefused() {
        for (RoleName role : RoleName.values()) {
            assertThat(assign(role)).as(role.name()).isEqualTo(IdentityAdministration.RoleGrant.FACTOR_REQUIRED);
        }
        assertThat(grants).hasValue(0);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("an enrolled factor: granted, the factor read on the grant's own unit of work")
    void aGrantWithAFactorIsMade() {
        enrolled = true;

        assertThat(assign(RoleName.LEDGER_OPERATOR)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);
        assertThat(grants).hasValue(1);
        assertThat(factorReads).hasValue(1);
    }

    @Test
    @DisplayName("a role that cannot redirect a merchant's money needs one too since P17-TSK-102 - the KYC reviewer, granted with"
            + " no factor until then - and the factor is read for it")
    void aRoleThatOnlyActsOnCasesNeedsOneToo() {
        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.FACTOR_REQUIRED);
        assertThat(factorReads).hasValue(1);
        assertThat(grants).hasValue(0);
    }

    @Test
    @DisplayName("the check is after the two older refusals: a self-grant and a stranger answer as they always did")
    void theOlderRefusalsComeFirst() {
        assertThat(administration().assignRole(UNIT_OF_WORK, ADMINISTRATOR, RoleName.LEDGER_OPERATOR, ADMINISTRATOR, "because"))
                .isEqualTo(IdentityAdministration.RoleGrant.SELF);
        assertThat(administration(false).assignRole(UNIT_OF_WORK, SUBJECT, RoleName.LEDGER_OPERATOR, ADMINISTRATOR, "because"))
                .isEqualTo(IdentityAdministration.RoleGrant.NOT_FOUND);
        assertThat(factorReads).hasValue(0);
    }

    @Test
    @DisplayName("no door removes a confirmed factor: the status set has no such state, and the store no write out of ACTIVE")
    void aConfirmedFactorCannotBeRemoved() throws Exception {
        assertThat(MfaFactorStatus.values())
                .as("a status past ACTIVE is the path around the grant's check: the task that builds removal must refuse a"
                        + " holder of a role that needs a factor (PRIVILEGED_ACCESS_REVIEW.md §6), and change this test")
                .containsExactly(MfaFactorStatus.PENDING, MfaFactorStatus.ACTIVE, MfaFactorStatus.DISCARDED);
        assertThat(java.util.Arrays.stream(MfaEnrolmentStore.class.getMethods()).map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("insert", "findPending", "findActive", "confirm", "consumeStep", "discardPending");
    }

    @Test
    @DisplayName("the check of standing holdings is recorded as a read, naming how many it served")
    void theCheckIsARecordedRead() throws Exception {
        IdentityAdministration.FactorlessHoldings found = read(200);

        assertThat(found.holdings()).singleElement().satisfies(holding -> assertThat(holding.role()).isEqualTo(RoleName.LEDGER_OPERATOR));
        assertThat(found.more()).isFalse();
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(IdentityAuditAction.FACTORLESS_HOLDINGS_READ);
            assertThat(record.changeSummary()).hasValue("served=1, continued=false, more=false");
        });
    }

    @Test
    @DisplayName("past the page, the record counts what was served and not the row read past it to learn there were more")
    void theRecordCountsWhatWasServed() throws Exception {
        standing = 3;

        IdentityAdministration.FactorlessHoldings found = read(2);

        assertThat(found.holdings()).hasSize(2);
        assertThat(found.more()).isTrue();
        assertThat(written).singleElement().satisfies(record -> assertThat(record.changeSummary()).hasValue("served=2, continued=false, more=true"));
    }

    private IdentityAdministration.FactorlessHoldings read(int mostServed) {
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-check")));
                var actor = SecurityContext.enter(new Actor(ADMINISTRATOR.value().toString(), ActorType.EMPLOYEE))) {
            return administration().factorlessHoldings(UNIT_OF_WORK, Optional.empty(), mostServed);
        }
    }

    /** Under the request's scopes, for the audit record a real grant writes. */
    private IdentityAdministration.RoleGrant assign(RoleName role) {
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-grant")));
                var actor = SecurityContext.enter(new Actor(ADMINISTRATOR.value().toString(), ActorType.EMPLOYEE))) {
            return administration().assignRole(UNIT_OF_WORK, SUBJECT, role, ADMINISTRATOR, "because");
        }
    }

    private IdentityAdministration administration() {
        return administration(true);
    }

    @SuppressWarnings("unchecked")
    private IdentityAdministration administration(boolean subjectExists) {
        IdentityStore<Connection> identities = (IdentityStore<Connection>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return subjectExists ? Optional.of(new SpringObjenesis().newInstance(Identity.class)) : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RoleAssignmentStore<Connection> assignments = (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(
                RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("assign")) {
                        grants.incrementAndGet();
                        return true;
                    }
                    // P15-TSK-043: a grant reads whether this is the subject's first role, after the identity's lock since
                    // P15-TSK-032. None held: a first role, whose sessions end - the fake store below has none open.
                    // FirstRoleEndsSessionsTest holds that half.
                    if (method.getName().equals("lockForRoleChange")) {
                        return null;
                    }
                    if (method.getName().equals("liveRolesOf")) {
                        return Set.of();
                    }
                    if (method.getName().equals("holdingsWithoutFactor")) {
                        // P15-TSK-032: a factorless administrator, and a factorless risk approver, are named by the check too;
                        // since P17-TSK-102 every role that can act, which today is every role.
                        assertThat((Set<RoleName>) args[1]).containsExactlyInAnyOrder(RoleName.values());
                        return java.util.Collections.nCopies(standing,
                                new RoleAssignmentStore.FactorlessHolding(SUBJECT, RoleName.LEDGER_OPERATOR, Instant.now(CLOCK)));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        MfaEnrolmentStore<Connection> enrolments = (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(
                MfaEnrolmentStore.class.getClassLoader(), new Class<?>[] {MfaEnrolmentStore.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).isEqualTo("findActive");
                    assertThat(args[0]).isSameAs(UNIT_OF_WORK);
                    assertThat(args[1]).isEqualTo(SUBJECT);
                    assertThat(args[2]).isEqualTo(MfaFactorType.TOTP);
                    factorReads.incrementAndGet();
                    return enrolled ? Optional.of(new Object()) : Optional.empty();
                });
        SessionStore<Connection> sessionStore = (SessionStore<Connection>) Proxy.newProxyInstance(
                SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).isEqualTo("revokeAllFor");
                    return 0;
                });
        SessionRevocation revocation = new SessionRevocation(sessionStore, IDS, CLOCK, (uow, record) -> written.add(record));
        return new IdentityAdministration(IDS, CLOCK, identities,
                new Authorization(assignments, IDS, CLOCK, (uow, record) -> written.add(record)), revocation,
                (uow, record) -> written.add(record), (uow, envelope, payload, mediaType) -> { }, enrolments);
    }
}
