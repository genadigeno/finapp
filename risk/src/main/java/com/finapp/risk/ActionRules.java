package com.finapp.risk;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a case may propose, and on what (`P13-TSK-017`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112 decision 2; owner
 * decision C10) - judged by {@link RiskActions#propose} under the case's lock and again, for every writer, by
 * {@code V013}'s {@code risk_action_permits_only_machine_edges} ({@code RiskActionMigrationTest} holds the two to one
 * list). The target is never the analyst's to choose freely:
 *
 * <ul>
 *   <li>a party's case ({@code FRAUD}, {@code ATO}, {@code AML}) proposes identity's three - {@code SUSPEND_IDENTITY},
 *       {@code REINSTATE_IDENTITY}, {@code REVOKE_SESSIONS} - on <strong>its own party</strong> (C10: an ATO case's
 *       response; the platform never resets a credential, and no such kind exists);</li>
 *   <li>a {@code MERCHANT_ABUSE} case proposes {@code SUSPEND_MERCHANT} on <strong>its own merchant</strong> (`-014`'s
 *       note);</li>
 *   <li>any case but {@code COLLECTIONS} proposes a held movement's resolution - release or denial of a withdrawal,
 *       capture or void of a card payment - on <strong>a movement the case holds</strong>: its opening assessment, or one
 *       the system attached, sealed {@code HOLD} for that operation, whose command ref is the target;</li>
 *   <li>nothing else: {@code COLLECTIONS} proposes nothing (C3); the kinds with no owning command are unavailable
 *       ({@link ActionKind#available()}); {@code RESCREEN_PARTY} is the rescreening sweep's (`-022`), never a case's.</li>
 * </ul>
 */
public final class ActionRules {

    /** Identity's three, a party's case's. */
    public static final Set<ActionKind> IDENTITY_KINDS = EnumSet.of(ActionKind.SUSPEND_IDENTITY,
            ActionKind.REINSTATE_IDENTITY, ActionKind.REVOKE_SESSIONS);

    /** The case kinds whose subject is a party and which propose identity's kinds. */
    public static final Set<CaseKind> PARTY_CASES = EnumSet.of(CaseKind.FRAUD, CaseKind.ATO, CaseKind.AML);

    /** Merchant's one, a merchant-abuse case's. */
    public static final Set<ActionKind> MERCHANT_KINDS = EnumSet.of(ActionKind.SUSPEND_MERCHANT);

    /** The case kinds that propose merchant's kind. */
    public static final Set<CaseKind> MERCHANT_CASES = EnumSet.of(CaseKind.MERCHANT_ABUSE);

    /** A held withdrawal's two. */
    public static final Set<ActionKind> HELD_WITHDRAWAL_KINDS = EnumSet.of(ActionKind.RELEASE_HELD_WITHDRAWAL,
            ActionKind.DENY_HELD_WITHDRAWAL);

    /** A held card payment's two. */
    public static final Set<ActionKind> HELD_PAYMENT_KINDS = EnumSet.of(ActionKind.CAPTURE_HELD_PAYMENT,
            ActionKind.VOID_HELD_PAYMENT);

    private ActionRules() {}

    /** True when {@code kind} acts on a held movement the case must hold. */
    public static boolean onAHeldMovement(ActionKind kind) {
        return HELD_WITHDRAWAL_KINDS.contains(kind) || HELD_PAYMENT_KINDS.contains(kind);
    }

    /** The operation whose sealed {@code HOLD} a held kind resolves - empty for any other kind. */
    public static Optional<RiskOperation> heldOperation(ActionKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (HELD_WITHDRAWAL_KINDS.contains(kind)) {
            return Optional.of(RiskOperation.WITHDRAWAL);
        }
        if (HELD_PAYMENT_KINDS.contains(kind)) {
            return Optional.of(RiskOperation.CARD_PAYMENT);
        }
        return Optional.empty();
    }

    /**
     * True when a case of {@code kind} over a subject of {@code subject} may propose {@code action} at all - before its
     * target is judged. Only an available kind.
     */
    public static boolean admits(CaseKind kind, SubjectKind subject, ActionKind action) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        if (!action.available()) {
            return false;
        }
        if (IDENTITY_KINDS.contains(action)) {
            return PARTY_CASES.contains(kind) && subject == SubjectKind.PARTY;
        }
        if (MERCHANT_KINDS.contains(action)) {
            return MERCHANT_CASES.contains(kind) && subject == SubjectKind.MERCHANT;
        }
        if (onAHeldMovement(action)) {
            return kind != CaseKind.COLLECTIONS;
        }
        return false;
    }

    /** The case's own subject a party's or merchant's action acts on - empty for a held kind (its target is asked). */
    public static boolean actsOnTheSubject(ActionKind action) {
        return IDENTITY_KINDS.contains(action) || MERCHANT_KINDS.contains(action);
    }

    /** The closed code a case's action hands its owner: {@code RISK_CASE_<kind>} - never the analyst's text. */
    public static String reasonCode(CaseKind kind) {
        return "RISK_CASE_" + Objects.requireNonNull(kind, "kind").name();
    }
}
