package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a role grants (`P1-TSK-020`, ADR-0031).
 *
 * <h2>This is the definition, not a convenience</h2>
 *
 * <p>ADR-0031 chose RBAC over a policy engine precisely so that <em>"why was this denied?"</em> is
 * answered by <strong>reading a method</strong> rather than by evaluating a rule set. That makes
 * {@link RoleName#permissions()} the platform's authorization policy in its entirety — and the
 * completion gate found it had <strong>no test at all</strong>, which is the {@link AssuranceLevel}
 * finding from {@code P1-TSK-013} repeated on the type that decides who may do anything privileged.
 *
 * <h2>The recorded limit, and the task that closed it</h2>
 *
 * <p>With <strong>one</strong> role holding <strong>both</strong> permissions, the mapping could not
 * be meaningfully mutated: {@code P1-TSK-020}'s gate found that {@code permissions()} returning
 * {@code EnumSet.allOf(PermissionName.class)} <strong>survived</strong>, correctly, because it was
 * the same set — and recorded that the interesting mutations become available at the second role.
 *
 * <p>{@code P2-TSK-004} is that role, and closing the limit is its acceptance criterion: a role
 * granting everything, a role gaining its sibling's permission, and the two grants swapped are all
 * caught now — by the exact-grant assertions here, and independently by the cross-population
 * refusal tests over HTTP ({@code DenyByDefaultDatabaseTest}), which is two controls blind in
 * different directions rather than a duplication.
 */
@DisplayName("what a role grants (P1-TSK-020)")
class RoleNameTest {

    @Test
    @DisplayName("ADMINISTRATOR grants exactly the two Phase 1 privileged permissions")
    void administratorGrantsExactlyTwo() {
        // containsExactlyInAnyOrder, never contains: a widening is the defect, and `contains` is
        // satisfied by a role that grants everything. Naming both is what makes a third permission
        // added to this role a failing test rather than a silent expansion of what an administrator
        // can do.
        assertThat(RoleName.ADMINISTRATOR.permissions())
                .as("granting a permission is a reviewed code change, so widening must fail a test")
                .containsExactlyInAnyOrder(
                        PermissionName.IDENTITY_SUSPEND, PermissionName.ROLE_ASSIGN);
    }

    @Test
    @DisplayName("KYC_REVIEWER grants exactly KYC_REVIEW - and neither administrative permission")
    void kycReviewerGrantsExactlyOne() {
        // The least-privilege split is the point of the role (P2-TSK-004), so it is asserted as
        // an exact set: this is the assertion that catches the previously-untestable mutations -
        // the role granting everything, or quietly gaining IDENTITY_SUSPEND or ROLE_ASSIGN.
        assertThat(RoleName.KYC_REVIEWER.permissions())
                .as("a reviewer reviews; managing identities is a different trust decision")
                .containsExactlyInAnyOrder(PermissionName.KYC_REVIEW);
    }

    @Test
    @DisplayName("LEDGER_OPERATOR grants exactly the two ledger permissions - nothing else")
    void ledgerOperatorGrantsExactlyTwo() {
        // One role, two permissions (P3-TSK-007): Phase 3 has one ledger-operating population,
        // and the vocabulary stays precise so P3-TSK-017 can check LEDGER_ADJUST specifically.
        // Exact set, so the role quietly gaining ROLE_ASSIGN - the permission that grants
        // permissions - is a failing test rather than a silent expansion.
        assertThat(RoleName.LEDGER_OPERATOR.permissions())
                .as("operating the ledger is not managing identities or reviewing cases")
                .containsExactlyInAnyOrder(
                        PermissionName.LEDGER_POST, PermissionName.LEDGER_ADJUST);
    }

    @Test
    @DisplayName("every pair of grants is disjoint, so the populations really are separate")
    void theGrantsArePairwiseDisjoint() {
        // No exact-set assertion alone says the SETS do not overlap - each pins its own role.
        // Pairwise over values() rather than a hand-picked pair (the P2-TSK-004 assertion,
        // generalised by P3-TSK-007 when the third role arrived), so a fourth role is held to
        // the property without anyone editing this test - the stale-list defect, closed the way
        // this repository closes it. A permission added to two roles fails here even if
        // somebody edits both exact-set tests to match.
        RoleName[] roles = RoleName.values();
        for (int i = 0; i < roles.length; i++) {
            for (int j = i + 1; j < roles.length; j++) {
                assertThat(roles[i].permissions())
                        .as("%s and %s must stay disjoint populations", roles[i], roles[j])
                        .doesNotContainAnyElementsOf(roles[j].permissions());
            }
        }
    }

    @Test
    @DisplayName("every permission is granted by some role, so none is unreachable")
    void noPermissionIsOrphaned() {
        // A permission no role grants is a check that can never pass - an endpoint declaring it
        // would be permanently refused, and the failure would present as an authorization bug rather
        // than as a missing grant. Cheap to state now; the thing nobody notices at the fifth role.
        Set<PermissionName> granted = EnumSet.noneOf(PermissionName.class);
        for (RoleName role : RoleName.values()) {
            granted.addAll(role.permissions());
        }
        assertThat(granted)
                .as("a permission no role grants is a check that can never pass")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(PermissionName.class));
    }

    @Test
    @DisplayName("the granted set cannot be widened at run time")
    void theGrantedSetIsImmutable() {
        // Set.copyOf in the constructor, asserted rather than trusted. A mutable set here would let
        // any caller holding a RoleName add a permission for every request on every instance,
        // permanently, with nothing failing - which is privilege escalation by accident.
        assertThatThrownBy(
                        () ->
                                RoleName.ADMINISTRATOR
                                        .permissions()
                                        .add(PermissionName.IDENTITY_SUSPEND))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the SQL value list is exactly the declared roles")
    void theSqlValueListMatchesTheDeclaration() {
        // The consumer is V010's CHECK constraint, reconciled by RoleAssignmentMigrationTest. This
        // is the other half: that the generator produces what the reconciliation compares. Kept and
        // made load-bearing, where PermissionName's identical method was deleted as dead - the
        // P1-TSK-013 disposition, where isLiveAt was kept and idleBoundAfterUseAt removed.
        assertThat(RoleName.sqlValueList())
                .isEqualTo("'ADMINISTRATOR', 'KYC_REVIEWER', 'LEDGER_OPERATOR'");
    }
}
