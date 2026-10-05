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
     * Operates the platform's money and nothing else (`P3-TSK-007`, widened by `P4-TSK-009`,
     * `P5-TSK-015`, `P6-TSK-012`, `P7-TSK-003` and `P7-TSK-012`): commands postings, manual
     * adjustments, transfer reversals, refunds and a merchant's payout on its behalf, routes
     * payments and administers disputes, over the surfaces that check
     * {@link PermissionName#LEDGER_POST}, {@link PermissionName#LEDGER_ADJUST},
     * {@link PermissionName#TRANSFER_REVERSE}, {@link PermissionName#PAYMENT_REFUND},
     * {@link PermissionName#MERCHANT_PAYOUT}, {@link PermissionName#PAYMENT_ROUTING_ADMINISTER}
     * and {@link PermissionName#DISPUTE_ADMINISTER}.
     *
     * <p><strong>One role holding the seven money-operating permissions</strong> (it said three
     * until `P6-DOC-001`, and five until `P7-TSK-012` - the routing arrival went uncounted
     * here), because a role exists when a
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
                    PermissionName.PAYMENT_REFUND,
                    // P6-TSK-012: the operator-initiated payout joins it too - money leaving
                    // the platform, the refund's reasoning pointed at the merchant. Where it
                    // goes stays four-eyes-guarded (ADR-0056); only when it is asked for moves.
                    PermissionName.MERCHANT_PAYOUT,
                    // P7-TSK-003: routing joins the one money-operating population - how
                    // money travels is this desk's judgement (ADR-0060; the permission's own
                    // javadoc carries the future-split reasoning, the FEE_ADMINISTER shape).
                    PermissionName.PAYMENT_ROUTING_ADMINISTER,
                    // P7-TSK-012: disputes join it too - a chargeback is money forced back
                    // through the rail, and answering it is payment operations (ADR-0061;
                    // the permission's javadoc carries the future dispute-desk split).
                    PermissionName.DISPUTE_ADMINISTER)),

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
     * them; the payout is commanded over ITS OWN permission, {@link
     * PermissionName#MERCHANT_PAYOUT}, which `P6-TSK-012` gave the money-operating population).
     * Holds none of
     * the other populations' permissions and they hold neither of these, asserted pairwise
     * and over HTTP in both directions ({@code INV-AUD-03}).
     */
    MERCHANT_ADMINISTRATOR(
            EnumSet.of(
                    PermissionName.MERCHANT_ONBOARD,
                    PermissionName.MERCHANT_ADMINISTER,
                    // P6-TSK-004: pricing joins the one counterparty-administering population
                    // - the PAYMENT_REFUND arrival restated, not re-argued. Its OWN permission
                    // because a commercial desk setting prices and a risk desk ruling on
                    // standing are a real future split; one role because nothing has yet taken
                    // that decision, and a role for a split nobody has made is a trust
                    // decision nobody took.
                    PermissionName.FEE_ADMINISTER,
                    // P6-TSK-011: approving a payout destination joins the same population -
                    // four-eyes is two distinct identities (P3-TSK-021's shape), and the
                    // approval statement refuses the proposer whatever roles they hold.
                    PermissionName.PAYOUT_DESTINATION_APPROVE)),

    /**
     * Runs settlement and reconciliation (`P8-TSK-003`, ADR-0066, owner decision O1):
     * introduces settlement evidence, attests another person's upload, and investigates what
     * arrived over the surfaces that check {@link PermissionName#SETTLEMENT_INGEST} and
     * {@link PermissionName#RECONCILIATION_INVESTIGATE}.
     * Since `P8-TSK-015` it also resolves breaks ({@link PermissionName#RECONCILIATION_RESOLVE}):
     * the template-bound, four-eyes resolution through the ledger's owned adjustment calls.
     *
     * <p><strong>A fifth role, because this IS a distinct trust decision</strong>: judging
     * whether the outside world's account of the money matches ours is neither operating the
     * money ({@link #LEDGER_OPERATOR} - whose desk's work this population CHECKS, which is
     * exactly why the two must stay separate populations), nor administering counterparties,
     * identities or cases. Holds none of the other populations' permissions and they hold
     * neither of these, asserted pairwise and over HTTP in both directions
     * ({@code INV-AUD-03}).
     *
     * <p><strong>The four-eyes control is inside the role, not between roles</strong>: any
     * two holders of {@code SETTLEMENT_INGEST} satisfy {@code INV-SET-07}, but never one
     * person twice - distinctness is by actor id, at the domain and by {@code CHECK}
     * (`P8-TSK-003`).
     */
    RECONCILIATION_OPERATOR(
            EnumSet.of(
                    PermissionName.SETTLEMENT_INGEST,
                    PermissionName.RECONCILIATION_INVESTIGATE,
                    PermissionName.RECONCILIATION_RESOLVE,
                    // P9-TSK-013: the FX legs' investigator reads the trade's rate chain.
                    PermissionName.FX_INVESTIGATE)),

    /**
     * Controls the reconciliation register (`P8-TSK-007`, ADR-0067 §8, owner decision O1's
     * second role): performs the opening-position backfill — and, as later tasks land, the
     * register-shaping acts of their kind — over the surfaces that check
     * {@link PermissionName#RECONCILIATION_ADMINISTER}.
     *
     * <p><strong>A sixth role, because this IS a distinct trust decision</strong>: adopting
     * history into the register decides what every proof and break is judged against, which
     * is neither feeding evidence in nor investigating it ({@link #RECONCILIATION_OPERATOR}
     * — the desk this one oversees), and certainly not operating the money. Holds none of
     * the other populations' permissions and they hold none of this, asserted pairwise and
     * over HTTP in both directions ({@code INV-AUD-03}).
     */
    RECONCILIATION_CONTROLLER(EnumSet.of(PermissionName.RECONCILIATION_ADMINISTER)),

    /**
     * Sets FX prices and availability (`P9-TSK-007`, ADR-0075 §7): holds
     * {@link PermissionName#FX_ADMINISTER} - pricing policy versions under four eyes, and the
     * pair and provider kill switch.
     *
     * <p><strong>A seventh role, because this IS a distinct trust decision</strong>: the margin
     * every conversion freezes and posts is the platform's revenue policy, which is neither
     * operating the money ({@link #LEDGER_OPERATOR}, which will reverse trades), nor judging
     * the outside world's account of it, nor administering identities. Holds none of the
     * other populations' permissions and they hold none of this, asserted pairwise and over
     * HTTP in both directions ({@code INV-AUD-03}). Since `P9-TSK-015` it also holds
     * {@code CROSSBORDER_ADMINISTER}: the corridors' transfer fees and limits are the same policy.
     */
    FX_CONTROLLER(EnumSet.of(PermissionName.FX_ADMINISTER, PermissionName.CROSSBORDER_ADMINISTER));

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
