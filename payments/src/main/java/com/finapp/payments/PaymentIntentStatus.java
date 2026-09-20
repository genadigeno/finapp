package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The payment-intent machine (ADR-0045, {@code PAYMENT_LIFECYCLES.md} §2, {@code INV-LIFE-01}):
 *
 * <pre>REQUIRES_CONFIRMATION ──&gt; PROCESSING ──&gt; SUCCEEDED
 *           │                        └───────&gt; FAILED
 *           └──────────────────────&gt; CANCELLED</pre>
 *
 * <p>Five states, four edges, and <strong>every state has a producer and is durably
 * observable</strong> — ADR-0044's doctrine, applied to the machine it was written to precede.
 * Unlike the transfer's {@code INITIATED}, every state here is committed: creation commits
 * {@code REQUIRES_CONFIRMATION}, which is precisely the window that gives {@code CANCELLED} the
 * producer ADR-0044 found missing for transfers, and {@code PROCESSING} — refused there — is
 * earned here because the outcome belongs to a third party.
 *
 * <p><strong>{@code SUCCEEDED} is ADR-0045's <em>stable</em> state — no outgoing edge — and it
 * is in the terminal set, deliberately.</strong> The two statements are one fact read two ways:
 * {@link #isTerminal()} is the structural derivation ({@code permittedTransitions().isEmpty()},
 * the {@code TransferStatus} idiom), and {@code SUCCEEDED} has no exit because refund state is
 * the refund rows' fact — a refund is a new bounded operation referencing the <em>captured
 * attempt</em>, never an intent edge, so {@code PARTIALLY_REFUNDED}/{@code REFUNDED} are derived
 * by views and stored nowhere (one fact in two places would be free to disagree). That is
 * {@code INV-LIFE-04}'s own reading — <em>"subsequent economic changes are new operations
 * (reversal, refund, chargeback)"</em> — refund being the catalogue's named example. Where
 * ADR-0044's {@code COMPLETED} stayed out of the terminal set because it genuinely had an edge,
 * {@code SUCCEEDED} has none, and nothing downstream wants a live/stable split: the intent has
 * no one-live partial index ({@code PHASE_5_PLAN.md} §8 — that arbiter is the attempt's).
 *
 * <p>Deliberately absent, each with its reason recorded in ADR-0045: {@code REQUIRES_ACTION}
 * (no simulated provider issues a challenge yet — arrives with its producer),
 * {@code PARTIALLY_REFUNDED}/{@code REFUNDED} (derived, above), {@code DISPUTED} (Phase 7's
 * lifecycle, not an intent state).
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate every
 * transition rather than the ones somebody remembered. The schema {@code CHECK} is generated
 * from {@link #sqlValueList()} and the transition trigger's edge set from
 * {@link #permittedTransitions()}; both consumers are {@code P5-TSK-008}'s
 * migration-reconciliation test, and until it lands the fragments are pinned by literal in
 * {@code PaymentIntentTest} so the generators cannot drift unverified.
 */
public enum PaymentIntentStatus {

    /**
     * Created and awaiting the customer's confirmation. Durable — creation commits it
     * ({@code P5-TSK-009}) — and nothing transitions <em>to</em> it: birth is the only door,
     * and no method on {@link PaymentIntent} targets it.
     */
    REQUIRES_CONFIRMATION,

    /**
     * Confirmed; the attempt dispatched in the same transaction ({@code P5-TSK-009}, ADR-0046).
     * The outcome now belongs to the provider.
     */
    PROCESSING,

    /**
     * The attempt captured; the posting committed in the same outcome transaction
     * ({@code P5-TSK-010}). Stable with no outgoing edge, and terminal by structure (above):
     * refunds reference the captured attempt, never this row.
     */
    SUCCEEDED,

    /**
     * The attempt failed. The mapped, enumerated reason is the <em>attempt's</em> fact
     * ({@code PAYMENT_LIFECYCLES.md} §2, {@code INV-PAY-03}) — this row carries no copy of it.
     * Terminal; a customer who still wants to pay creates a new intent (ADR-0045 §4).
     */
    FAILED,

    /**
     * The customer's own withdrawal, only from {@code REQUIRES_CONFIRMATION}
     * ({@code P5-TSK-011}). Terminal: nothing dispatched, nothing posted.
     */
    CANCELLED;

    /** The states reachable from this one. */
    public Set<PaymentIntentStatus> permittedTransitions() {
        return switch (this) {
            case REQUIRES_CONFIRMATION -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED, CANCELLED -> EnumSet.noneOf(PaymentIntentStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(PaymentIntentStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code P5-TSK-008}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list ({@code P5-TSK-008}'s trigger reconciliation).
     * Generated so "terminal" has one definition ({@code P2-TSK-005}'s reasoning) — and note
     * what it deliberately <em>includes</em>: {@code SUCCEEDED}, whose stable-with-no-exit
     * reading is argued at the class javadoc, the inverse of {@code TransferStatus}'s recorded
     * exclusion of {@code COMPLETED}.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(PaymentIntentStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
