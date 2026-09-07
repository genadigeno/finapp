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
 * <h2>One role, because Phase 1 has one kind of privileged actor</h2>
 *
 * <p>There is deliberately no `CUSTOMER` role. Every session endpoint is available to every
 * session-holder against their **own** resources, and the control there is ownership rather than
 * permission (`P1-TSK-016`). A `CUSTOMER` role would have to be assigned at registration, would
 * grant nothing, and would be a row per person that no check ever reads.
 *
 * <p>ADR-0031 records role explosion as a medium-term risk and declines to pre-solve it. One role is
 * where that starts.
 */
public enum RoleName {

    /** Everything Phase 1 calls privileged. Held by nobody until somebody assigns it. */
    ADMINISTRATOR(EnumSet.of(PermissionName.IDENTITY_SUSPEND, PermissionName.ROLE_ASSIGN));

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
