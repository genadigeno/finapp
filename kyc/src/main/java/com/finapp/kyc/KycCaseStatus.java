package com.finapp.kyc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a KYC/KYB case (`PHASE_2_PLAN.md` §5, {@code INV-LIFE-01}).
 *
 * <pre>
 * OPEN → CHECKS_IN_PROGRESS → { READY_FOR_DECISION | IN_REVIEW } → { APPROVED | REJECTED }
 * </pre>
 *
 * <p>{@code IN_REVIEW} leads only back to {@code READY_FOR_DECISION}: a reviewer resolves what
 * the checks raised, and the <em>decision</em> is a separate act ({@code INV-KYC-04} — a hit is
 * resolved by a person, and resolving it is not deciding the case).
 *
 * <p><strong>{@code APPROVED} and {@code REJECTED} are terminal, and that is the point of the
 * enum rather than a detail of it</strong> ({@code INV-LIFE-04}). Changed circumstances — new
 * evidence, a periodic re-verification, a sanctions list update — open a <em>new</em> case; the
 * decided one stays decided and stays true, which is what makes the decision defensible years
 * later ({@code INV-KYC-02}). The one-open-case index is built from exactly this terminal set,
 * so "terminal" and "frees the slot for a successor" are one definition.
 *
 * <p>The names are persisted values, in a {@code CHECK} constraint generated from
 * {@link #sqlValueList()} and a partial-index predicate generated from
 * {@link #sqlTerminalValueList()}; {@code KycCaseMigrationTest} fails the build if this enum and
 * the schema disagree — the {@code P0-TSK-022} pattern, both artefacts.
 */
public enum KycCaseStatus {

    /** The case exists; no check has been dispatched yet. */
    OPEN,

    /** At least one verification check has been dispatched and not all are terminal. */
    CHECKS_IN_PROGRESS,

    /**
     * Something needs a person: a check {@code HIT}, or went {@code INDETERMINATE} past its
     * retry budget ({@code INV-KYC-04} — silence resolves nothing, in either direction).
     */
    IN_REVIEW,

    /** Every check terminal and clear, or every review task resolved. Awaiting the decision. */
    READY_FOR_DECISION,

    /** Terminal. The platform decided the party may be onboarded. */
    APPROVED,

    /** Terminal. The platform decided the party may not be onboarded. */
    REJECTED;

    /**
     * The states reachable from this one.
     *
     * <p>Declared here rather than in the aggregate so the machine is readable in one place, and
     * so a test can enumerate every transition rather than the ones somebody remembered to write
     * — the {@code CustomerStatus} idiom.
     */
    public Set<KycCaseStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(CHECKS_IN_PROGRESS);
            case CHECKS_IN_PROGRESS -> EnumSet.of(READY_FOR_DECISION, IN_REVIEW);
            case IN_REVIEW -> EnumSet.of(READY_FOR_DECISION);
            case READY_FOR_DECISION -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED, REJECTED -> EnumSet.noneOf(KycCaseStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(KycCaseStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list, for the one-open-case index predicate.
     *
     * <p>Generated so that "terminal" has one definition: a state added to the machine without a
     * decision about whether it frees the open-case slot is a reconciliation failure in
     * {@code KycCaseMigrationTest}, never a silent widening of how many cases a customer can
     * hold open.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(KycCaseStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
