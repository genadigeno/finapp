package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a rail's payment is conducted (`P7-TSK-001`, ADR-0059 §2) — and, since `P7-TSK-002`,
 * <strong>the owner of the attempt's machine</strong>: which states a model's attempt may
 * occupy and which edges it may move along are properties of the model, not of a status, so
 * they are declared here and consumed by the aggregate's guard, the store's writer-side check
 * and the `V012` reconciliation alike ({@code INV-LIFE-01}, {@code INV-LIFE-02}).
 *
 * <p>Three models, deliberately not one: a generic "execute" with rail-neutral states is the
 * phase's first named risk, because it hides that a card capture stays reversible against the
 * platform for months while an instant payment is final in seconds. <strong>No non-terminal
 * state name is shared across models</strong> — {@code EXECUTED} is not {@code CAPTURED} — and
 * that disjointness is load-bearing: every edge's <em>from</em>-state identifies its model, so
 * {@link #anyPermits} is exact rather than a blessing, and a history row's vocabulary says
 * which machine moved (the terminals {@code FAILED} and {@code EXECUTED} are shared, and have
 * no edges to blur). {@code InteractionModelMachinesTest} pins the disjointness.
 */
public enum InteractionModel {

    /**
     * Authorize, then capture — Phase 5's seven states and eleven edges verbatim (they moved
     * here from {@code PaymentAttemptStatus} when the second machine arrived), plus the void
     * edges the card reversal task adds (`P7-TSK-004`). The outcome is decided by the issuer,
     * through the PSP and the network.
     */
    TWO_STEP,

    /**
     * One push executes the payment — a credit transfer, decided by the payer's PSP, the
     * scheme and the payee's PSP. {@code AWAITING_PAYER} exists only for a pay-in: the payer
     * authorizes at their own PSP, and the attempt waits for that PSP's answer, never for our
     * clock. Its operations arrive with its rails (`P7-TSK-006`, `-009`).
     */
    PUSH,

    /**
     * One book movement on the platform's own ledger, born {@code EXECUTED} or {@code FAILED}
     * inside the confirmation's transaction (ADR-0043's property): no dispatch, no unknown,
     * <strong>no edges</strong> — and therefore never a history row. The wallet rail
     * (`P7-TSK-011`).
     */
    BOOK;

    /** This model's machine: every state it may occupy, mapped to its legal exits. */
    public Map<PaymentAttemptStatus, Set<PaymentAttemptStatus>> edges() {
        EnumMap<PaymentAttemptStatus, Set<PaymentAttemptStatus>> edges =
                new EnumMap<>(PaymentAttemptStatus.class);
        switch (this) {
            case TWO_STEP -> {
                edges.put(PaymentAttemptStatus.AUTH_DISPATCHED,
                        EnumSet.of(PaymentAttemptStatus.AUTHORIZED, PaymentAttemptStatus.FAILED,
                                PaymentAttemptStatus.AUTH_UNKNOWN));
                edges.put(PaymentAttemptStatus.AUTH_UNKNOWN,
                        EnumSet.of(PaymentAttemptStatus.AUTHORIZED, PaymentAttemptStatus.FAILED));
                edges.put(PaymentAttemptStatus.AUTHORIZED,
                        EnumSet.of(PaymentAttemptStatus.CAPTURE_DISPATCHED));
                edges.put(PaymentAttemptStatus.CAPTURE_DISPATCHED,
                        EnumSet.of(PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED,
                                PaymentAttemptStatus.CAPTURE_UNKNOWN));
                edges.put(PaymentAttemptStatus.CAPTURE_UNKNOWN,
                        EnumSet.of(PaymentAttemptStatus.CAPTURED, PaymentAttemptStatus.FAILED));
                edges.put(PaymentAttemptStatus.CAPTURED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
                edges.put(PaymentAttemptStatus.FAILED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
            }
            case PUSH -> {
                edges.put(PaymentAttemptStatus.AWAITING_PAYER,
                        EnumSet.of(PaymentAttemptStatus.EXECUTION_DISPATCHED,
                                PaymentAttemptStatus.FAILED));
                edges.put(PaymentAttemptStatus.EXECUTION_DISPATCHED,
                        EnumSet.of(PaymentAttemptStatus.EXECUTION_UNKNOWN,
                                PaymentAttemptStatus.EXECUTED, PaymentAttemptStatus.FAILED));
                edges.put(PaymentAttemptStatus.EXECUTION_UNKNOWN,
                        EnumSet.of(PaymentAttemptStatus.EXECUTED, PaymentAttemptStatus.FAILED));
                edges.put(PaymentAttemptStatus.EXECUTED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
                edges.put(PaymentAttemptStatus.FAILED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
            }
            case BOOK -> {
                edges.put(PaymentAttemptStatus.EXECUTED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
                edges.put(PaymentAttemptStatus.FAILED,
                        EnumSet.noneOf(PaymentAttemptStatus.class));
            }
        }
        return edges;
    }

    /** The states this model's attempt may occupy at all. */
    public Set<PaymentAttemptStatus> statuses() {
        return edges().keySet();
    }

    /** Whether this model's machine has the edge. */
    public boolean permits(PaymentAttemptStatus from, PaymentAttemptStatus to) {
        Set<PaymentAttemptStatus> exits = edges().get(from);
        return exits != null && exits.contains(to);
    }

    /**
     * Whether ANY model's machine has the edge — exact, not a blessing, because non-terminal
     * states are model-exclusive (the class javadoc; pinned by test). The writer-side check
     * for a caller that holds an edge but not the row.
     */
    public static boolean anyPermits(PaymentAttemptStatus from, PaymentAttemptStatus to) {
        return Arrays.stream(values()).anyMatch(model -> model.permits(from, to));
    }

    /** The models as a SQL literal list (`V012`'s `CHECK`, the P5-TSK-008 reconciliation). */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(model -> "'" + model.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** This model's states as a SQL literal list, in enum-ordinal order (the generated order). */
    public String sqlStatusList() {
        return statuses().stream()
                .sorted()
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
