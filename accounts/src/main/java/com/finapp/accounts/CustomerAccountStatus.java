package com.finapp.accounts;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Customer Account machine (`PHASE_3_PLAN.md` §4, `INV-LIFE-01`):
 *
 * <pre>PENDING → ACTIVE → { SUSPENDED ⇄ ACTIVE } → CLOSED</pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate every
 * transition rather than the ones somebody remembered — the {@code KycCaseStatus} idiom. The
 * schema {@code CHECK} is generated from {@link #sqlValueList()} and the one-live-account index
 * predicate from {@link #sqlTerminalValueList()}; {@code CustomerAccountMigrationTest} fails the
 * build if this enum and `V002` disagree.
 *
 * <p><strong>{@code PENDING} has no producer</strong>: opening is gated on the customer's
 * verification and, once that gate passes, nothing else pends — {@link CustomerAccount#open}
 * creates {@code ACTIVE} directly. The state exists for a product that needs its own approval
 * step (a Phase 6 concern), the {@code AssuranceLevel.STRONG} precedent: what is under guard is
 * the machine, and a value with no producer is not a value with no meaning.
 *
 * <p><strong>{@code CLOSED} is reachable from {@code ACTIVE} only.</strong> A suspended account
 * is under an administrative question; ending the agreement without resolving it would fold two
 * decisions into one act. If `P3-TSK-014`'s close design needs the wider edge, adding it here is
 * one line — and the migration reconciliation makes it a reviewed one.
 */
public enum CustomerAccountStatus {

    /** Awaiting an approval step no Phase 3 product has. No producer yet, by design. */
    PENDING,

    /** The agreement is live. The state {@link CustomerAccount#open} creates. */
    ACTIVE,

    /** Administratively frozen. No producer yet; the machine models it (`INV-LIFE-02`). */
    SUSPENDED,

    /** Terminal (`INV-LIFE-04`). The agreement is over; the accounting history is not. */
    CLOSED;

    /** The states reachable from this one. */
    public Set<CustomerAccountStatus> permittedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(ACTIVE);
            case ACTIVE -> EnumSet.of(SUSPENDED, CLOSED);
            case SUSPENDED -> EnumSet.of(ACTIVE);
            case CLOSED -> EnumSet.noneOf(CustomerAccountStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(CustomerAccountStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list, for the one-live-account index predicate.
     * Generated so "terminal" and "frees the slot for a successor" are one definition
     * (`P2-TSK-005`'s reasoning): a closed account frees the customer to open a new agreement;
     * a state added without deciding which side of the predicate it sits on fails the build.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(CustomerAccountStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
