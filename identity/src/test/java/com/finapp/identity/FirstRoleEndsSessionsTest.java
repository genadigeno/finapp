package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

/**
 * A first role ends every session its grantee holds, in the grant's own unit of work (`P15-TSK-043`; {@code THREAT_MODEL.md}
 * §6 item 39 (a2), T3). A session a customer stepped up from the internet would otherwise serve the new member of staff at
 * {@code MULTI_FACTOR}. The real rows - the old token refused on its next request, a second role ending nothing - are
 * {@code IdentityAdministrationDatabaseTest}'s, owed to the exit database pass (C14).
 *
 * <p>The stores are fakes that record every call in order, so each case says what reached the database, on which unit of
 * work, and before or after the grant's record.
 */
@DisplayName("a first role ends every session its grantee holds, in the grant's transaction (P15-TSK-043)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class FirstRoleEndsSessionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final IdentityId ADMINISTRATOR = IdentityId.next(IDS);
    private static final IdentityId SUBJECT = IdentityId.next(IDS);
    private static final Connection UNIT_OF_WORK = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);

    /** Every call that reached a store or the trail, in order. */
    private final List<String> calls = new ArrayList<>();
    private final List<AuditRecord> written = new ArrayList<>();
    /** The live roles the grantee holds before the grant: what the database would answer. */
    private final Set<RoleName> live = EnumSet.noneOf(RoleName.class);
    private int liveSessions = 2;
    private boolean inserts = true;
    private RuntimeException revocationFails;

    @Test
    @DisplayName("a first role: granted, every live session revoked on the grant's unit of work, after the row and before the"
            + " record, which says so")
    void aFirstRoleEndsEverySession() {
        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);

        assertThat(calls).containsExactly("liveRolesOf", "assign KYC_REVIEWER", "revokeAllFor " + SUBJECT,
                "audit identity.SessionRevoked", "audit identity.RoleAssigned");
        assertThat(written).filteredOn(record -> record.operation() == IdentityAuditAction.IDENTITY_ROLE_ASSIGNED)
                .singleElement().satisfies(record -> {
                    assertThat(record.targetId()).isEqualTo(SUBJECT.value().toString());
                    assertThat(record.actor().id()).isEqualTo(ADMINISTRATOR.value().toString());
                    assertThat(record.reason()).hasValue("joins the review desk");
                    assertThat(record.changeSummary()).hasValue("granted=KYC_REVIEWER, firstRole=true, sessionsRevoked=2");
                });
        assertThat(written).filteredOn(record -> record.operation() == IdentityAuditAction.SESSION_REVOKED)
                .singleElement().satisfies(record -> {
                    assertThat(record.targetId()).isEqualTo(SUBJECT.value().toString());
                    assertThat(record.actor().id()).as("the administrator decided it, not the grantee")
                            .isEqualTo(ADMINISTRATOR.value().toString());
                    assertThat(record.changeSummary()).hasValue("sessionsRevoked=2");
                });
        assertThat(liveSessions).as("the grantee holds no session the grant did not see").isZero();
    }

    @Test
    @DisplayName("a first role with no session open: granted, the revocation still asked, and the record says none were live")
    void aFirstRoleWithNoSessionSaysSo() {
        liveSessions = 0;

        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);

        assertThat(calls).contains("revokeAllFor " + SUBJECT);
        assertThat(written).singleElement().satisfies(record ->
                assertThat(record.changeSummary()).hasValue("granted=KYC_REVIEWER, firstRole=true, sessionsRevoked=0"));
    }

    @Test
    @DisplayName("a second role ends nothing: its holder's sessions were issued at doors that knew it was staff")
    void aSecondRoleEndsNothing() {
        live.add(RoleName.KYC_REVIEWER);

        assertThat(assign(RoleName.ACCOUNTANT)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);

        assertThat(calls).doesNotContain("revokeAllFor " + SUBJECT);
        assertThat(liveSessions).isEqualTo(2);
        assertThat(written).singleElement().satisfies(record ->
                assertThat(record.changeSummary()).hasValue("granted=ACCOUNTANT, firstRole=false"));
    }

    @Test
    @DisplayName("a retried first grant finds the role held and ends nothing more: the first attempt ended them, or granted nothing")
    void aRetriedGrantEndsNothingMore() {
        inserts = false;

        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.ALREADY_HELD);

        assertThat(calls).doesNotContain("revokeAllFor " + SUBJECT);
        assertThat(written).isEmpty();
    }

    @Test
    @DisplayName("a re-grant after a revocation is a first role again: only live roles decide, and in between it was a customer")
    void aReGrantAfterARevocationIsAFirstRoleAgain() {
        // The revoked holding is a row the database keeps; liveRolesOf's statement filters it out (revoked_at IS NULL), which
        // this fake models by answering the live set only. The statement itself is IdentityAdministrationDatabaseTest's.
        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);
        live.clear();
        liveSessions = 1;
        calls.clear();
        written.clear();

        assertThat(assign(RoleName.KYC_REVIEWER)).isEqualTo(IdentityAdministration.RoleGrant.GRANTED);

        assertThat(calls).contains("revokeAllFor " + SUBJECT);
        assertThat(written).filteredOn(record -> record.operation() == IdentityAuditAction.IDENTITY_ROLE_ASSIGNED)
                .singleElement().satisfies(record ->
                        assertThat(record.changeSummary()).hasValue("granted=KYC_REVIEWER, firstRole=true, sessionsRevoked=1"));
    }

    @Test
    @DisplayName("a refused grant - to oneself, to nobody, or a money role with no factor - ends nothing and records nothing")
    void aRefusedGrantEndsNothing() {
        assertThat(administration().assignRole(UNIT_OF_WORK, ADMINISTRATOR, RoleName.KYC_REVIEWER, ADMINISTRATOR, "because"))
                .isEqualTo(IdentityAdministration.RoleGrant.SELF);
        assertThat(assign(RoleName.LEDGER_OPERATOR)).as("no factor enrolled")
                .isEqualTo(IdentityAdministration.RoleGrant.FACTOR_REQUIRED);

        assertThat(calls).doesNotContain("revokeAllFor " + SUBJECT).doesNotContain("assign LEDGER_OPERATOR");
        assertThat(written).isEmpty();
        assertThat(liveSessions).isEqualTo(2);
    }

    @Test
    @DisplayName("a revocation that fails fails the grant: no record is written, and the caller's transaction rolls the row back")
    void aFailedRevocationFailsTheGrant() {
        revocationFails = new IdentityStorageException("Could not revoke the sessions: planted");

        assertThatThrownBy(() -> assign(RoleName.KYC_REVIEWER)).isSameAs(revocationFails);

        assertThat(written).as("a grant on the record whose sessions still serve would be the state this prevents").isEmpty();
    }

    // ----------------------------------------------------------------------------------------------- the world

    /** Under the request's scopes, as the grant door runs it. */
    private IdentityAdministration.RoleGrant assign(RoleName role) {
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-grant")));
                var actor = SecurityContext.enter(new Actor(ADMINISTRATOR.value().toString(), ActorType.EMPLOYEE))) {
            return administration().assignRole(UNIT_OF_WORK, SUBJECT, role, ADMINISTRATOR, "joins the review desk");
        }
    }

    @SuppressWarnings("unchecked")
    private IdentityAdministration administration() {
        IdentityStore<Connection> identities = (IdentityStore<Connection>) Proxy.newProxyInstance(IdentityStore.class.getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.of(new SpringObjenesis().newInstance(Identity.class));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RoleAssignmentStore<Connection> assignments = (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(
                RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class}, (proxy, method, args) -> {
                    assertThat(args[0]).as("%s on the grant's unit of work", method.getName()).isSameAs(UNIT_OF_WORK);
                    switch (method.getName()) {
                        case "liveRolesOf" -> {
                            calls.add("liveRolesOf");
                            return Set.copyOf(live);
                        }
                        case "assign" -> {
                            calls.add("assign " + args[2]);
                            if (inserts) {
                                live.add((RoleName) args[2]);
                            }
                            return inserts;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        SessionStore<Connection> sessionStore = (SessionStore<Connection>) Proxy.newProxyInstance(
                SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("revokeAllFor")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertThat(args[0]).as("the revocation joins the grant's unit of work").isSameAs(UNIT_OF_WORK);
                    calls.add("revokeAllFor " + args[1]);
                    if (revocationFails != null) {
                        throw revocationFails;
                    }
                    int ended = liveSessions;
                    liveSessions = 0;
                    return ended;
                });
        MfaEnrolmentStore<Connection> enrolments = (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(
                MfaEnrolmentStore.class.getClassLoader(), new Class<?>[] {MfaEnrolmentStore.class},
                (proxy, method, args) -> Optional.empty());
        com.finapp.platform.audit.AuditWriter<Connection> trail = (uow, record) -> {
            calls.add("audit " + record.operation().code());
            written.add(record);
        };
        return new IdentityAdministration(IDS, CLOCK, identities, new Authorization(assignments, IDS, CLOCK, trail),
                new SessionRevocation(sessionStore, IDS, CLOCK, trail), trail,
                (uow, envelope, payload, mediaType) -> { }, enrolments);
    }
}
