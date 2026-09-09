package com.finapp.identity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A named bundle of permissions (`P1-TSK-020`, ADR-0031).
 *
 * <h2>The mapping is CODE, and the assignment is DATA</h2>
 *
 * <p>*Who* holds a role changes at runtime, so assignments live in `identity.role_assignment`.
 * *What a role means* does not, so it lives here - and granting a permission is therefore a reviewed
 * code change rather than a row somebody inserts.
 *
 * <p>That is ADR-0031's whole reason for choosing RBAC over a policy engine: *"authorization becomes
 * data, which means 'why was this denied?' is answered by evaluating a rule set rather than by
 * reading a method."* Putting the mapping in a table would give away exactly what the ADR paid for.
 *
 * <h2>Two roles, one per kind of privileged actor — and still no `CUSTOMER` role</h2>
 *
 * <p>There is deliberately no `CUSTOMER` role. Every session endpoint is available to every
 * session-holder against their **own** resources, and the control there is ownership rather than
 * permission (`P1-TSK-016`). A `CUSTOMER` role would have to be assigned at registration, would
 * grant nothing, and would be a row per person that no check ever reads.
 *
 * <p>The second role arrived with the second privileged population (`P2-TSK-004`), not before:
 * ADR-0031 records role explosion as a medium-term risk and declines to pre-solve it, and a role
 * exists here when a distinct trust decision does. **The two grants are disjoint on purpose** —
 * managing identities and reviewing cases are different decisions about different people, and the
 * disjointness is what `RoleNameTest`'s exact-grant assertions hold: a role quietly gaining the
 * other's permission is the mutation `P1-TSK-020` recorded as untestable at one role, and this is
 * the task that made it fail the build.
 */
public enum RoleName {

    /** Everything Phase 1 calls privileged. Held by nobody until somebody assigns it. */
    ADMINISTRATOR(EnumSet.of(PermissionName.IDENTITY_SUSPEND, PermissionName.ROLE_ASSIGN)),

    /**
     * Reviews KYC/KYB cases and nothing else (`P2-TSK-004`).
     *
     * <p>Holds neither {@code IDENTITY_SUSPEND} nor {@code ROLE_ASSIGN}: a reviewer refused by
     * the administrative endpoints is asserted over HTTP, in both directions, because least
     * privilege is only real when it is tested from the attacker's side ({@code INV-AUD-03}).
     * The first assignment needs no bootstrap: administrators exist and hold
     * {@code ROLE_ASSIGN}, so a reviewer arrives through the ordinary audited endpoint.
     */
    KYC_REVIEWER(EnumSet.of(PermissionName.KYC_REVIEW));

    private final Set<PermissionName> permissions;

    RoleName(Set<PermissionName> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    /** What holding this role grants. Immutable, so no caller can widen it at run time. */
    public Set<PermissionName> permissions() {
        return permissions;
    }

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
