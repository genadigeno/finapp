package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An administrator never suspends the last {@code ACTIVE} identity that can grant roles; risk's automated suspension is not
 * bound by that (`P15-TSK-032`, item 42 (d) as decided). The locks come before the status write, and the count is read after
 * them. What the database's locks do with two real transactions is {@code LastAdministratorDatabaseTest}'s and
 * {@code RoleAssignmentConcurrencyDatabaseTest}'s, owed (C14); here the rule, its order, and what it makes of counts read
 * after the lock and before it.
 */
@DisplayName("an administrator never suspends the last identity that can grant roles; risk may (P15-TSK-032)")
class LastAdministratorSuspensionTest {

    private static final IdentityId FIRST = IdentityId.next(RevocationWorld.IDS);
    private static final IdentityId SECOND = IdentityId.next(RevocationWorld.IDS);
    private static final IdentityId THIRD = IdentityId.next(RevocationWorld.IDS);

    private final RevocationWorld world = new RevocationWorld();

    @Test
    @DisplayName("the last active administrator: refused, nothing written, recorded, announced or ended")
    void theLastActiveGranterIsNotSuspended() {
        world.holds(SECOND, RoleName.ADMINISTRATOR);

        assertThat(world.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR))
                .isEqualTo(IdentityAdministration.Suspension.LAST_ROLE_GRANTER);

        assertThat(world.calls).containsExactly("findById", "lockForRoleChange", "liveRolesOf", "lockHoldingsOf", "activeHoldingsOf");
        assertThat(world.written).isEmpty();
        assertThat(world.suspended).isEmpty();
        assertThat(world.liveSessions).isEqualTo(2);
    }

    @Test
    @DisplayName("the locks before the status write: the subject's row, then the granting holdings, then the fresh count - then"
            + " the write, the sessions, the record and the event")
    void theLocksComeBeforeTheStatusWrite() {
        world.holds(FIRST, RoleName.ADMINISTRATOR);
        world.holds(SECOND, RoleName.ADMINISTRATOR);

        assertThat(world.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR))
                .isEqualTo(IdentityAdministration.Suspension.SUSPENDED);

        assertThat(world.calls).containsExactly("findById", "lockForRoleChange", "liveRolesOf", "lockHoldingsOf",
                "activeHoldingsOf", "moveStatus SUSPENDED", "revokeAllFor " + SECOND, "audit identity.SessionRevoked",
                "audit identity.IdentitySuspended", "outbox identity.IdentitySuspended");
        assertThat(world.grantingAsked).containsExactly(java.util.Set.of(RoleName.ADMINISTRATOR));
        assertThat(world.activeGranters()).containsExactly(FIRST);
    }

    @Test
    @DisplayName("a suspended administrator does not count; one holding no granting role takes the identity's lock alone")
    void aSuspendedHolderDoesNotCountAndADeskTakesNoHoldingsLock() {
        world.holds(SECOND, RoleName.ADMINISTRATOR);
        world.holdsSuspended(THIRD, RoleName.ADMINISTRATOR);
        assertThat(world.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR))
                .isEqualTo(IdentityAdministration.Suspension.LAST_ROLE_GRANTER);

        world.calls.clear();
        IdentityId reviewer = IdentityId.next(RevocationWorld.IDS);
        world.holds(reviewer, RoleName.KYC_REVIEWER);
        assertThat(world.suspend(reviewer, FIRST, SuspensionCause.ADMINISTRATOR))
                .isEqualTo(IdentityAdministration.Suspension.SUSPENDED);
        assertThat(world.calls).contains("lockForRoleChange").doesNotContain("lockHoldingsOf", "activeHoldingsOf");
    }

    @Test
    @DisplayName("risk's automated suspension is not bound by the rule: a compromised last administrator is suspended, and risk's"
            + " path takes none of the rule's locks")
    void risksSuspensionIsNotBound() {
        world.holds(SECOND, RoleName.ADMINISTRATOR);

        assertThat(world.suspend(SECOND, FIRST, SuspensionCause.RISK)).isEqualTo(IdentityAdministration.Suspension.SUSPENDED);

        assertThat(world.calls).doesNotContain("lockForRoleChange", "lockHoldingsOf", "activeHoldingsOf");
        assertThat(world.activeGranters()).as("security wins over availability there").isEmpty();
    }

    @Test
    @DisplayName("two administrators suspending each other at once, the last two: exactly one is left, whichever goes first")
    void twoAdministratorsSuspendingEachOtherLeaveOne() throws Exception {
        for (int round = 0; round < 20; round++) {
            RevocationWorld locked = new RevocationWorld();
            locked.meetAtTheLock = true;
            locked.holds(FIRST, RoleName.ADMINISTRATOR);
            locked.holds(SECOND, RoleName.ADMINISTRATOR);

            List<Object> outcomes = race(
                    () -> locked.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR),
                    () -> locked.suspend(FIRST, SECOND, SuspensionCause.ADMINISTRATOR));

            assertThat(outcomes).as("round %d", round).containsExactlyInAnyOrder(
                    IdentityAdministration.Suspension.SUSPENDED, IdentityAdministration.Suspension.LAST_ROLE_GRANTER);
            assertThat(locked.activeGranters()).as("round %d: one administrator remains", round).hasSize(1);
        }
    }

    @Test
    @DisplayName("a suspension racing a revocation of its own actor's granting role: exactly one of them acts, and an active"
            + " administrator remains")
    void aSuspensionRacingTheActorsRevocationLeavesOne() throws Exception {
        for (int round = 0; round < 20; round++) {
            RevocationWorld locked = new RevocationWorld();
            locked.meetAtTheLock = true;
            locked.holds(FIRST, RoleName.ADMINISTRATOR);
            locked.holds(SECOND, RoleName.ADMINISTRATOR);

            // FIRST suspends SECOND while SECOND takes FIRST's administrator role away.
            List<Object> outcomes = race(
                    () -> locked.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR),
                    () -> locked.revoke(FIRST, RoleName.ADMINISTRATOR, SECOND));

            assertThat(outcomes.stream().filter(outcome -> outcome == IdentityAdministration.Suspension.SUSPENDED
                    || outcome == IdentityAdministration.RoleRevocation.REVOKED)).as("round %d: %s", round, outcomes).hasSize(1);
            assertThat(locked.activeGranters()).as("round %d", round).hasSize(1);
        }
    }

    @Test
    @DisplayName("the control: over counts that did not wait for the lock - read before it, as one statement's snapshot would -"
            + " both suspensions land and nobody is left, which is what the lock and the fresh count are for")
    void countsThatDidNotWaitLetBothLand() throws Exception {
        RevocationWorld unlocked = new RevocationWorld();
        unlocked.serialised = false;
        unlocked.holds(FIRST, RoleName.ADMINISTRATOR);
        unlocked.holds(SECOND, RoleName.ADMINISTRATOR);
        CyclicBarrier bothCounted = new CyclicBarrier(2);
        unlocked.afterRead = () -> {
            try {
                bothCounted.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        assertThat(race(
                () -> unlocked.suspend(SECOND, FIRST, SuspensionCause.ADMINISTRATOR),
                () -> unlocked.suspend(FIRST, SECOND, SuspensionCause.ADMINISTRATOR)))
                .containsOnly(IdentityAdministration.Suspension.SUSPENDED);
        assertThat(unlocked.activeGranters()).isEmpty();
    }

    private static List<Object> race(Callable<?>... acts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(acts.length);
        CyclicBarrier start = new CyclicBarrier(acts.length);
        try {
            List<Future<?>> outcomes = new java.util.ArrayList<>();
            for (Callable<?> act : acts) {
                outcomes.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return act.call();
                }));
            }
            List<Object> answered = new java.util.ArrayList<>();
            for (Future<?> outcome : outcomes) {
                answered.add(outcome.get(30, TimeUnit.SECONDS));
            }
            return answered;
        } finally {
            pool.shutdownNow();
        }
    }
}
