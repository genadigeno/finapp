package com.finapp.payments;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The dispute machine (`P7-TSK-012`, ADR-0061 §2) — the network's process around a contested
 * card payment, in the platform's own words ({@code INV-PAY-03}: the PSP's stage vocabulary
 * stops at the webhook door):
 *
 * <pre>
 * INQUIRY ──► CHARGED_BACK ──► REPRESENTED ──► WON
 *    │             │                 └───────► LOST
 *    │             ├───────────────────────────► LOST
 *    │             └───────────────────────────► ACCEPTED
 *    └──► CLOSED  (an inquiry that never became a chargeback)
 * </pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition; `V020`'s stage {@code CHECK}s, its birth trigger's entry list and its
 * machine trigger's edges are generated from here, and {@code PaymentsMigrationTest} fails the
 * build if they disagree.
 *
 * <h2>Every stage has one producer: the network's word</h2>
 *
 * <p>The platform never decides a stage. The card PSP's notification is the only producer
 * (the representment and acceptance commands of `P7-TSK-014` dispatch to the PSP and still
 * learn their outcome from it), so the machine's job is to record the network's statements
 * <em>once and in order</em> — whatever order they arrive in.
 *
 * <h2>Order-blind by the machine's own graph</h2>
 *
 * <p>ADR-0061 §2: a notification naming a stage later than the dispute's current one applies
 * the intervening stages in order. {@link #pathTo} is that rule, computed from
 * {@link #permittedTransitions()} rather than written a second time: the shortest walk along
 * the edges, which for this machine is exactly the stages the target <em>implies</em> — a
 * {@code WON} implies a chargeback and a representment, a {@code LOST} only the chargeback, and
 * an {@code INQUIRY} is implied by nothing. {@code DisputeStageTest} holds that property for
 * every pair of stages (the shortest walk lies inside every walk), so the rule cannot drift
 * into inventing a stage the network never needed to pass through.
 */
public enum DisputeStage {

    /** The issuer asks about the payment; nothing has moved. An entry stage. */
    INQUIRY,

    /** The network has taken the funds. An entry stage — and, from `P7-TSK-013`, the
     * chargeback's posting. */
    CHARGED_BACK,

    /** Evidence has been submitted against the chargeback. No money moves. */
    REPRESENTED,

    /** The network returned the funds — terminal; from `P7-TSK-013`, the chargeback's exact
     * inverse. */
    WON,

    /** The network upheld the chargeback — terminal. */
    LOST,

    /** The chargeback was accepted rather than contested — terminal. */
    ACCEPTED,

    /** An inquiry that never became a chargeback — terminal. */
    CLOSED;

    /** The stages a dispute may be born at (ADR-0061 §2, `RAIL_AND_DISPUTE_LIFECYCLES.md` §6). */
    private static final Set<DisputeStage> ENTRY =
            Collections.unmodifiableSet(EnumSet.of(INQUIRY, CHARGED_BACK));

    /** The stages reachable from this one along one edge. */
    public Set<DisputeStage> permittedTransitions() {
        return switch (this) {
            case INQUIRY -> EnumSet.of(CHARGED_BACK, CLOSED);
            case CHARGED_BACK -> EnumSet.of(REPRESENTED, LOST, ACCEPTED);
            case REPRESENTED -> EnumSet.of(WON, LOST);
            case WON, LOST, ACCEPTED, CLOSED -> EnumSet.noneOf(DisputeStage.class);
        };
    }

    public boolean canTransitionTo(DisputeStage target) {
        return permittedTransitions().contains(target);
    }

    /** Terminal is terminal ({@code INV-LIFE-04}): a second cycle is a new dispute, never a
     * reopened one (ADR-0061 §2). */
    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** Whether a dispute may be born at this stage — the only two the network opens with. */
    public boolean isEntry() {
        return ENTRY.contains(this);
    }

    /**
     * Whether the network has taken the funds by this stage: {@code CHARGED_BACK} and every
     * stage after it. Exactly these stages carry the chargeback's amount (`V020`'s coherence
     * rule) — the figure `P7-TSK-013` posts and a win reverses — because it ARRIVES with the
     * chargeback, the captured amount's discipline: an inquiry states only the transaction it
     * asks about, and a chargeback may take less than that.
     */
    public boolean isChargedBack() {
        return this != INQUIRY && this != CLOSED;
    }

    /** The entry stages, in declaration order. */
    public static Set<DisputeStage> entryStages() {
        return ENTRY;
    }

    /**
     * The shortest walk from this stage (exclusive) to {@code target} (inclusive) along the
     * machine's edges — ties broken by declaration order, so the answer is deterministic —
     * or empty when {@code target} does not lie ahead. {@code target == this} is the empty
     * walk: nothing to apply.
     */
    public Optional<List<DisputeStage>> pathTo(DisputeStage target) {
        Objects.requireNonNull(target, "target must not be null");
        if (target == this) {
            return Optional.of(List.of());
        }
        Map<DisputeStage, DisputeStage> reachedFrom = new EnumMap<>(DisputeStage.class);
        Deque<DisputeStage> frontier = new ArrayDeque<>();
        frontier.add(this);
        while (!frontier.isEmpty()) {
            DisputeStage at = frontier.removeFirst();
            // An EnumSet iterates in declaration order: the tie-break.
            for (DisputeStage next : at.permittedTransitions()) {
                if (next == this || reachedFrom.containsKey(next)) {
                    continue;
                }
                reachedFrom.put(next, at);
                if (next == target) {
                    List<DisputeStage> walk = new ArrayList<>();
                    for (DisputeStage step = target; step != this; step = reachedFrom.get(step)) {
                        walk.add(step);
                    }
                    Collections.reverse(walk);
                    return Optional.of(List.copyOf(walk));
                }
                frontier.addLast(next);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code target} lies ahead of this stage (or is this stage). */
    public boolean canReach(DisputeStage target) {
        return pathTo(target).isPresent();
    }

    /**
     * The shortest walk that <em>opens</em> a dispute and ends at {@code target}: an entry
     * stage first, then the stages the target implies. Every stage lies on such a walk — the
     * machine is connected from its entries, which {@code DisputeStageTest} asserts — so the
     * answer is never empty. A {@code WON} heard first therefore opens at
     * {@code CHARGED_BACK} and walks through {@code REPRESENTED}: the history shows every
     * stage the network's outcome implies.
     */
    public static List<DisputeStage> openingPathTo(DisputeStage target) {
        Objects.requireNonNull(target, "target must not be null");
        List<DisputeStage> best = null;
        for (DisputeStage entry : ENTRY) {
            if (entry == target) {
                return List.of(entry);
            }
            Optional<List<DisputeStage>> tail = entry.pathTo(target);
            if (tail.isPresent() && (best == null || tail.get().size() + 1 < best.size())) {
                List<DisputeStage> walk = new ArrayList<>();
                walk.add(entry);
                walk.addAll(tail.get());
                best = List.copyOf(walk);
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    target + " lies on no walk from an entry stage: the machine is disconnected");
        }
        return best;
    }

    /** The values as a SQL literal list — `V020`'s stage {@code CHECK}s are generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The entry stages as a SQL literal list — `V020`'s birth trigger is generated from this. */
    public static String entrySqlValueList() {
        return ENTRY.stream()
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The stages before any chargeback, as a SQL literal list — `V020`'s amount-coherence
     * {@code CHECK} is generated from this. */
    public static String notChargedBackSqlValueList() {
        return Arrays.stream(values())
                .filter(value -> !value.isChargedBack())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
