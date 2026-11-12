package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A revocation never leaves nobody who can grant roles (`P15-TSK-032`): the role holding {@code ROLE_ASSIGN} is refused to the
 * last {@code ACTIVE} identity holding it, read with every such holding locked. What the database's lock does with two real
 * transactions - the second waiting, then re-reading the rows the first revoked - is
 * {@code RoleAssignmentConcurrencyDatabaseTest#theSecondRevocationReadsTheFirst}'s, owed (C14); here the rule, and what it
 * makes of a read that waited and of one that did not.
 */
@DisplayName("the last active identity that can grant roles keeps that role, and two revocations of the last two cannot both land"
        + " (P15-TSK-032)")
class LastRoleGranterTest {

    private static final IdentityId FIRST = IdentityId.next(RevocationWorld.IDS);
    private static final IdentityId SECOND = IdentityId.next(RevocationWorld.IDS);
    private static final IdentityId THIRD = IdentityId.next(RevocationWorld.IDS);

    private final RevocationWorld world = new RevocationWorld();

    @Test
    @DisplayName("the roles that can grant roles are read from what each role grants: today ADMINISTRATOR alone")
    void theGrantingRolesAreTheCodes() {
        assertThat(Authorization.rolesGranting(PermissionName.ROLE_ASSIGN)).containsExactly(RoleName.ADMINISTRATOR);
    }

    @Test
    @DisplayName("the last active holder of ADMINISTRATOR: refused, nothing written, recorded or ended")
    void theLastActiveGranterIsRefused() {
        world.holds(SECOND, RoleName.ADMINISTRATOR);

        assertThat(world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST))
                .isEqualTo(IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER);

        assertThat(world.grantingAsked).containsExactly(Set.of(RoleName.ADMINISTRATOR));
        assertThat(world.calls).doesNotContain("revoke ADMINISTRATOR").doesNotContain("revokeAllFor " + SECOND);
        assertThat(world.written).isEmpty();
        assertThat(world.rolesOf(SECOND)).containsExactly(RoleName.ADMINISTRATOR);
    }

    @Test
    @DisplayName("a suspended holder cannot sign in to grant anything, so it does not count: the last active one is still refused")
    void aSuspendedHolderDoesNotCount() {
        world.holds(SECOND, RoleName.ADMINISTRATOR);
        world.holdsSuspended(THIRD, RoleName.ADMINISTRATOR);

        assertThat(world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST))
                .isEqualTo(IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER);
    }

    @Test
    @DisplayName("another active holder: revoked, and the role beside another ends nothing; a suspended holder's own role is"
            + " revocable, leaving the active ones as they were")
    void anotherActiveGranterAdmitsIt() {
        world.holds(FIRST, RoleName.ADMINISTRATOR);
        world.holds(SECOND, RoleName.ADMINISTRATOR);
        world.holds(SECOND, RoleName.KYC_REVIEWER);
        world.holdsSuspended(THIRD, RoleName.ADMINISTRATOR);

        assertThat(world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);
        assertThat(world.revoke(THIRD, RoleName.ADMINISTRATOR, FIRST)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

        assertThat(world.written).filteredOn(record -> record.operation() == IdentityAuditAction.IDENTITY_ROLE_ASSIGNED)
                .extracting(record -> record.changeSummary().orElseThrow())
                .containsExactly("revoked=ADMINISTRATOR, lastRole=false", "revoked=ADMINISTRATOR, lastRole=true, sessionsRevoked=2");
        assertThat(world.rolesOf(FIRST)).containsExactly(RoleName.ADMINISTRATOR);
    }

    @Test
    @DisplayName("not held comes before the rule: a retry after the revocation landed answers as it did, never a refusal")
    void aRetryIsNotRefused() {
        world.holds(FIRST, RoleName.ADMINISTRATOR);
        world.holds(SECOND, RoleName.ADMINISTRATOR);
        assertThat(world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST)).isEqualTo(IdentityAdministration.RoleRevocation.REVOKED);

        assertThat(world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST))
                .as("SECOND no longer holds it, and FIRST is now the last: the retry is the first answer again")
                .isEqualTo(IdentityAdministration.RoleRevocation.NOT_HELD);
    }

    @Test
    @DisplayName("an administrator's own granting role is refused as SELF, before anything is read")
    void oneselfIsRefusedFirst() {
        world.holds(FIRST, RoleName.ADMINISTRATOR);
        world.holds(SECOND, RoleName.ADMINISTRATOR);

        assertThat(world.revoke(FIRST, RoleName.ADMINISTRATOR, FIRST)).isEqualTo(IdentityAdministration.RoleRevocation.SELF);
        assertThat(world.calls).isEmpty();
    }

    @Test
    @DisplayName("two administrators revoking each other at once, the last two: with the read locked one lands and one is refused,"
            + " whichever goes first; with it unlocked both land and nobody is left - which is what the lock is for")
    void twoRevocationsOfTheLastTwo() throws Exception {
        for (int round = 0; round < 20; round++) {
            RevocationWorld locked = new RevocationWorld();
            locked.holds(FIRST, RoleName.ADMINISTRATOR);
            locked.holds(SECOND, RoleName.ADMINISTRATOR);

            List<IdentityAdministration.RoleRevocation> outcomes = race(locked);

            assertThat(outcomes).as("round %d", round).containsExactlyInAnyOrder(
                    IdentityAdministration.RoleRevocation.REVOKED, IdentityAdministration.RoleRevocation.LAST_ROLE_GRANTER);
            assertThat(locked.live).as("one administrator remains").hasSize(1);
        }

        RevocationWorld unlocked = new RevocationWorld();
        unlocked.serialised = false;
        unlocked.holds(FIRST, RoleName.ADMINISTRATOR);
        unlocked.holds(SECOND, RoleName.ADMINISTRATOR);
        // Both reads meet before either writes: READ COMMITTED with no lock.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        unlocked.afterRead = subject -> {
            try {
                bothRead.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        assertThat(race(unlocked)).as("the control: the rule alone, over reads that did not wait")
                .containsOnly(IdentityAdministration.RoleRevocation.REVOKED);
        assertThat(unlocked.live).as("nobody left who can grant").isEmpty();
    }

    /** FIRST revokes SECOND's ADMINISTRATOR and SECOND revokes FIRST's, released together. */
    private static List<IdentityAdministration.RoleRevocation> race(RevocationWorld world) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<IdentityAdministration.RoleRevocation> one = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return world.revoke(SECOND, RoleName.ADMINISTRATOR, FIRST);
            });
            Future<IdentityAdministration.RoleRevocation> other = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return world.revoke(FIRST, RoleName.ADMINISTRATOR, SECOND);
            });
            return List.of(one.get(30, TimeUnit.SECONDS), other.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
