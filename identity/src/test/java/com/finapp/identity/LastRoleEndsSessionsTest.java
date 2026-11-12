package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A role taken away, and the last one ending every session its holder has, in the revocation's own unit of work
 * (`P15-TSK-032`; {@code FirstRoleEndsSessionsTest}'s neighbour). The coordinator's ruling under the owner's standing word: a
 * session issued at an operator door to a person who is staff no more must not keep serving, since a session is not bound to
 * the surface that issued it ({@code THREAT_MODEL.md} §6 item 39 (a3)). The real rows - the token refused on its next request,
 * a role beside another ending nothing - are {@code IdentityAdministrationDatabaseTest}'s, owed (C14).
 */
@DisplayName("a role revoked, and the last one ending every session its holder has, in the revocation's transaction (P15-TSK-032)")
class LastRoleEndsSessionsTest {

    private static final IdentityId ADMINISTRATOR = IdentityId.next(RevocationWorld.IDS);
    private static final IdentityId SUBJECT = IdentityId.next(RevocationWorld.IDS);

    private final RevocationWorld world = new RevocationWorld();

    @Test
    @DisplayName("the last role: read after the identity's lock, revoked, every session revoked on the revocation's unit of work,"
            + " after the row and before the record, which names the actor, the target, the role, the reason, the correlation and"
            + " the outcome")
    void theLastRoleEndsEverySession() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);

        assertThat(world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

        assertThat(world.calls).containsExactly("findById", "lockForRoleChange", "liveRolesOf", "revoke KYC_REVIEWER",
                "revokeAllFor " + SUBJECT, "audit identity.SessionRevoked", "audit identity.RoleAssigned");
        assertThat(world.written).filteredOn(record -> record.operation() == IdentityAuditAction.IDENTITY_ROLE_ASSIGNED)
                .singleElement().satisfies(record -> {
                    assertThat(record.actor().id()).isEqualTo(ADMINISTRATOR.value().toString());
                    assertThat(record.targetType()).isEqualTo("identity.Identity");
                    assertThat(record.targetId()).isEqualTo(SUBJECT.value().toString());
                    assertThat(record.reason()).hasValue("the incident closed, OPS-8812");
                    assertThat(record.correlationId().value()).isEqualTo("the-revocation");
                    assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
                    assertThat(record.changeSummary()).hasValue("revoked=KYC_REVIEWER, lastRole=true, sessionsRevoked=2");
                });
        assertThat(world.written).filteredOn(record -> record.operation() == IdentityAuditAction.SESSION_REVOKED)
                .singleElement().satisfies(record -> {
                    assertThat(record.targetId()).isEqualTo(SUBJECT.value().toString());
                    assertThat(record.actor().id()).as("the administrator decided it, not the holder").isEqualTo(ADMINISTRATOR.value().toString());
                    assertThat(record.changeSummary()).hasValue("sessionsRevoked=2");
                });
        assertThat(world.liveSessions).as("the former holder keeps no session the revocation did not see").isZero();
        assertThat(world.rolesOf(SUBJECT)).isEmpty();
    }

    @Test
    @DisplayName("the last role with no session open: revoked, the session revocation still asked, the record saying none were live")
    void theLastRoleWithNoSessionSaysSo() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);
        world.liveSessions = 0;

        assertThat(world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

        assertThat(world.calls).contains("revokeAllFor " + SUBJECT);
        assertThat(world.written).singleElement().satisfies(record ->
                assertThat(record.changeSummary()).hasValue("revoked=KYC_REVIEWER, lastRole=true, sessionsRevoked=0"));
    }

    @Test
    @DisplayName("a role revoked beside another ends nothing: the sessions serve what the identity still is, staff")
    void aRoleBesideAnotherEndsNothing() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);
        world.holds(SUBJECT, RoleName.ACCOUNTANT);

        assertThat(world.revoke(SUBJECT, RoleName.ACCOUNTANT, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

        assertThat(world.calls).doesNotContain("revokeAllFor " + SUBJECT);
        assertThat(world.liveSessions).isEqualTo(2);
        assertThat(world.written).singleElement().satisfies(record ->
                assertThat(record.changeSummary()).hasValue("revoked=ACCOUNTANT, lastRole=false"));
        assertThat(world.rolesOf(SUBJECT)).containsExactly(RoleName.KYC_REVIEWER);
    }

    @Test
    @DisplayName("a second revocation of a role no longer held answers as the first did, and writes, records and ends nothing more")
    void aRetriedRevocationIsIdempotentInEffect() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);
        assertThat(world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);
        world.calls.clear();
        world.written.clear();
        world.liveSessions = 1; // a session the former holder opened since, as a customer

        assertThat(world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.NOT_HELD);

        assertThat(world.calls).as("read, and nothing written").containsExactly("findById", "lockForRoleChange", "liveRolesOf");
        assertThat(world.written).isEmpty();
        assertThat(world.liveSessions).as("a customer's session the second attempt had no business ending").isEqualTo(1);
    }

    @Test
    @DisplayName("a role never held: the same answer, nothing written - the identity does not hold it, as asked")
    void aRoleNeverHeldIsNotHeld() {
        world.holds(SUBJECT, RoleName.ACCOUNTANT);

        assertThat(world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isEqualTo(IdentityAdministration.RoleRevocation.NOT_HELD);

        assertThat(world.calls).doesNotContain("revoke KYC_REVIEWER").doesNotContain("revokeAllFor " + SUBJECT);
        assertThat(world.written).isEmpty();
        assertThat(world.rolesOf(SUBJECT)).containsExactly(RoleName.ACCOUNTANT);
    }

    @Test
    @DisplayName("refused for oneself and for nobody, before anything is read or locked: nothing revoked, recorded or ended")
    void aRefusedRevocationEndsNothing() {
        world.holds(ADMINISTRATOR, RoleName.KYC_REVIEWER);
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);

        assertThat(world.revoke(ADMINISTRATOR, RoleName.KYC_REVIEWER, ADMINISTRATOR))
                .as("every administrative record names two parties").isEqualTo(IdentityAdministration.RoleRevocation.SELF);
        // No scope entered: a refusal reaches no record, and one that tried would fail for want of a correlation.
        assertThat(world.administration(false).revokeRole(RevocationWorld.UNIT_OF_WORK, SUBJECT, RoleName.KYC_REVIEWER,
                ADMINISTRATOR, "because")).isEqualTo(IdentityAdministration.RoleRevocation.NOT_FOUND);

        assertThat(world.calls).doesNotContain("lockForRoleChange", "liveRolesOf");
        assertThat(world.written).isEmpty();
        assertThat(world.liveSessions).isEqualTo(2);
        assertThat(world.rolesOf(ADMINISTRATOR)).containsExactly(RoleName.KYC_REVIEWER);
    }

    @Test
    @DisplayName("a session revocation that fails fails the revocation: no record is written, and the caller's transaction rolls"
            + " the row back")
    void aFailedSessionRevocationFailsTheRevocation() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);
        world.sessionRevocationFails = new IdentityStorageException("Could not revoke the sessions: planted");

        assertThatThrownBy(() -> world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR)).isSameAs(world.sessionRevocationFails);

        assertThat(world.written).as("a revocation on the record whose holder's sessions still serve is the state this prevents")
                .isEmpty();
    }

    @Test
    @DisplayName("a desk's role locks no other identity's holdings: only a role that can grant roles reads who else holds one")
    void aDesksRoleLocksItsHoldersRowsAlone() {
        world.holds(SUBJECT, RoleName.KYC_REVIEWER);

        world.revoke(SUBJECT, RoleName.KYC_REVIEWER, ADMINISTRATOR);

        assertThat(world.calls).doesNotContain("lockHoldingsOf", "activeHoldingsOf");
        assertThat(world.grantingAsked).isEmpty();
    }
}
