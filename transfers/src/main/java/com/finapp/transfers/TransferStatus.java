package com.finapp.transfers;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The transfer machine (ADR-0044, {@code INV-LIFE-01}):
 *
 * <pre>INITIATED ──&gt; COMPLETED ──&gt; REVERSED
 *      └──────&gt; FAILED</pre>
 *
 * <p>Four states, five edges counting birth, and <strong>every state has a producer</strong>:
 * {@code INITIATED} is the aggregate's birth inside the execution transaction (never durably
 * observed — ADR-0043 commits the outcome in the same transaction), {@code COMPLETED} and
 * {@code FAILED} are the execution command's ({@code P4-TSK-005}), {@code REVERSED} is the
 * reversal command's ({@code P4-TSK-009}). The conventional rich machine was rejected state by
 * state in ADR-0044: {@code VALIDATED}/{@code AUTHORIZED} are preconditions, not durable facts;
 * {@code PROCESSING} belongs to an outcome somebody else decides (Phase 5's payments);
 * {@code CANCELLED} needs a window ADR-0043 closed.
 *
 * <p><strong>{@code COMPLETED} is stable, not terminal — exactly one outgoing edge</strong>, a
 * deliberate reading of {@code INV-LIFE-04}: the reversal is a one-way move into another terminal,
 * driven by its own command, whose ledger effect is a <em>new</em> entry — the original posting is
 * byte-identical afterwards. The alternative (terminal {@code COMPLETED} plus a nullable reversal
 * reference beside it) stores "what happened to this transfer?" in two places free to disagree.
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate every
 * transition rather than the ones somebody remembered — the {@code CustomerAccountStatus} idiom.
 * The schema {@code CHECK} is generated from {@link #sqlValueList()} and the transition trigger's
 * edge set from {@link #permittedTransitions()}; both consumers are {@code P4-TSK-004}'s
 * migration-reconciliation test, which fails the build if this enum and {@code V002} disagree.
 */
public enum TransferStatus {

    /**
     * Born, not yet judged. Exists in the aggregate and in the lifecycle history rows, never in a
     * committed snapshot of {@code transfers.transfer} (ADR-0043: the row commits with its
     * outcome). Nothing transitions <em>to</em> this state — birth is the only door, and no
     * method on {@link Transfer} targets it.
     */
    INITIATED,

    /** The posting committed. Stable, not terminal: the one outgoing edge is the reversal's. */
    COMPLETED,

    /**
     * A committed domain refusal, carrying its enumerated {@link FailureReason} and no posting.
     * Terminal ({@code INV-LIFE-04}).
     */
    FAILED,

    /**
     * Reversed by its own command: a new referencing journal entry ({@code INV-REV-01}) and this
     * state move, one transaction. Terminal.
     */
    REVERSED;

    /** The states reachable from this one. */
    public Set<TransferStatus> permittedTransitions() {
        return switch (this) {
            case INITIATED -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.of(REVERSED);
            case FAILED, REVERSED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(TransferStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code V002}'s {@code CHECK} ({@code P4-TSK-004}). */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list ({@code P4-TSK-004}'s trigger and any
     * one-live-per-dimension predicate a later task earns). Generated so "terminal" has one
     * definition ({@code P2-TSK-005}'s reasoning) — and note what it deliberately excludes:
     * {@code COMPLETED}, whose stable-not-terminal reading is the machine's own (above).
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(TransferStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
