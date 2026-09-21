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
 * <h2>One role per kind of privileged actor — and still no `CUSTOMER` role</h2>
 *
 * <p>There is deliberately no `CUSTOMER` role. Every session endpoint is available to every
 * session-holder against their **own** resources, and the control there is ownership rather than
 * permission (`P1-TSK-016`). A `CUSTOMER` role would have to be assigned at registration, would
 * grant nothing, and would be a row per person that no check ever reads.
 *
 * <p>The second role arrived with the second privileged population (`P2-TSK-004`), not before:
 * ADR-0031 records role explosion as a medium-term risk and declines to pre-solve it, and a role
 * exists here when a distinct trust decision does. **The grants are pairwise disjoint on
 * purpose** — managing identities, reviewing cases and operating the ledger are different
 * decisions about different people, and the disjointness is what `RoleNameTest`'s exact-grant
 * assertions hold: a role quietly gaining another's permission is the mutation `P1-TSK-020`
 * recorded as untestable at one role, made a failing test by `P2-TSK-004` and held pairwise
 * over every role since `P3-TSK-007`.
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
    KYC_REVIEWER(EnumSet.of(PermissionName.KYC_REVIEW)),

    /**
     * Operates the platform's money and nothing else (`P3-TSK-007`, widened by `P4-TSK-009`):
     * commands postings, manual adjustments and transfer reversals over the surfaces that check
     * {@link PermissionName#LEDGER_POST}, {@link PermissionName#LEDGER_ADJUST} and
     * {@link PermissionName#TRANSFER_REVERSE}.
     *
     * <p><strong>One role holding three permissions</strong>, because a role exists when a
     * distinct trust decision does and there is one money-operating population — a new role for
     * the reversal would be a trust decision nothing takes (`P4-TSK-009`'s backlog sentence) —
     * while the permission vocabulary stays precise so `P3-TSK-017`'s adjustment endpoint and
     * `P4-TSK-009`'s reversal endpoint each check their own specifically. Holds none of the administrative or review
     * permissions, and they hold neither of these: posting the platform's money and managing
     * the people who hold it are different trust decisions, asserted pairwise and over HTTP in
     * both directions ({@code INV-AUD-03}).
     *
     * <p><strong>The self-elevation limit, restated rather than re-argued</strong>
     * (`P1-TSK-028`): an administrator holding {@code ROLE_ASSIGN} can grant themselves this
     * role, and what the split buys is that the escalation is a recorded grant in the trail
     * rather than a capability that was silently always there.
     */
    LEDGER_OPERATOR(
            EnumSet.of(
                    PermissionName.LEDGER_POST,
                    PermissionName.LEDGER_ADJUST,
                    PermissionName.TRANSFER_REVERSE,
                    // P5-TSK-015: the refund joins the one money-operating population - the
                    // same reasoning as the reversal's arrival, restated not re-argued.
                    PermissionName.PAYMENT_REFUND)),

    /**
     * Administers commercial counterparties and nothing else (`P6-TSK-003`): onboards
     * merchants and rules on their standing over the surfaces that check
     * {@link PermissionName#MERCHANT_ONBOARD} and {@link PermissionName#MERCHANT_ADMINISTER}.
     *
     * <p><strong>A new role, because this IS a distinct trust decision</strong>: deciding who
     * the platform does business with is neither managing identities
     * ({@link #ADMINISTRATOR}), nor reviewing verification cases ({@link #KYC_REVIEWER} - the
     * KYB decision is an input this role consumes, never one it makes), nor operating the
     * money ({@link #LEDGER_OPERATOR} - onboarding opens books and moves nothing through
     * them; the payout, when it arrives, is commanded over ITS OWN permission). Holds none of
     * the other populations' permissions and they hold neither of these, asserted pairwise
     * and over HTTP in both directions ({@code INV-AUD-03}).
     */
    MERCHANT_ADMINISTRATOR(
            EnumSet.of(
                    PermissionName.MERCHANT_ONBOARD, PermissionName.MERCHANT_ADMINISTER));

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
