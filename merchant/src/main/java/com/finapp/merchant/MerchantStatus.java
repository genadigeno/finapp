package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Merchant machine (`P6-TSK-003`, `PHASE_6_PLAN.md` §5, `INV-LIFE-01`):
 *
 * <pre>ACTIVE ⇄ SUSPENDED, ACTIVE → CLOSED</pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition rather than the ones somebody remembered — the {@code KycCaseStatus} idiom.
 * The schema {@code CHECK} is generated from {@link #sqlValueList()};
 * {@code MerchantMigrationTest} fails the build if this enum and `V002` disagree.
 *
 * <p><strong>Every state has a producer from day one</strong> (ADR-0044's doctrine):
 * onboarding creates {@code ACTIVE} directly — the KYB gate is what pends, and once it passes
 * nothing else does, the {@code CustomerAccountStatus.PENDING}-has-no-producer reasoning taken
 * to its conclusion by simply not modelling a state this phase cannot reach. Suspension and
 * reinstatement are the operator's reasoned acts; closure ends the commercial relationship.
 *
 * <p><strong>What suspension means — and does not</strong>
 * ({@code CHECKOUT_MERCHANT_LIFECYCLES.md} §5): {@code SUSPENDED} gates <em>new
 * dispatches</em> — sessions, payouts — at their own commands, in later tasks. It never
 * touches arrived outcomes or the payable: a suspended merchant's money stays theirs and
 * stays explainable, which is why suspension is reversible and carries a reason.
 *
 * <p><strong>{@code CLOSED} is reachable from {@code ACTIVE} only</strong> — the
 * {@code CustomerAccountStatus} rule, same reasoning: a suspended merchant is under an
 * administrative question, and ending the relationship without resolving it would fold two
 * decisions into one act.
 */
public enum MerchantStatus {

    /** The commercial relationship is live. The state onboarding creates. */
    ACTIVE,

    /** Administratively frozen: new dispatches refuse; landed money still lands. Reversible. */
    SUSPENDED,

    /** Terminal (`INV-LIFE-04`). The relationship is over; the books are not. */
    CLOSED;

    /** The states reachable from this one. */
    public Set<MerchantStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(SUSPENDED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE);
            case CLOSED -> EnumSet.noneOf(MerchantStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(MerchantStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
