package com.finapp.risk;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The account-takeover sequence, judged from a verdict's hits (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 12.2,
 * 12.6, scenario 11; ADR-0110; {@code INV-RSK-04}, {@code INV-RSK-06}) - pure: no clock, no read, no state. A credential
 * change or a recovery within the lookback (<em>the anchor</em>), followed by a new payee and a new device
 * (<em>the continuations</em>) and money leaving the customer's control (<em>the movement</em>).
 *
 * <p><strong>Rules as data.</strong> Every element is a rule the pinned version carries and the engine judged - the
 * anchor's lookback is its {@code lookback_hours}, the payee's and the device's their {@code lookback_days}; this class
 * only reads the sealed outcomes, so a replay of the assessment re-derives the same answer, and the decision stays the
 * policy's (the WITHDRAWAL fixture's {@code RECENT_CREDENTIAL_CHANGE} is a hard {@code HOLD}; this class raises the
 * alert whatever was decided).
 *
 * <ul>
 *   <li><strong>The anchor</strong> - a fired {@link RuleKind#RECENT_CREDENTIAL_CHANGE} or
 *       {@link RuleKind#RECENT_RECOVERY}; each fired anchor kind is a sequence of its own (its own alert, `-016` folding
 *       both into the party's one {@code ATO} case).</li>
 *   <li><strong>A continuation</strong> ({@link RuleKind#NEW_PAYEE}, {@link RuleKind#NEW_DEVICE}) that fired continues
 *       it; one whose reading is {@code ABSENT}, or that the version does not carry, cannot be judged and does not stop
 *       it - we did not look, which is not "not new" (the device is {@code ABSENT} on every movement today, `-008`); one
 *       judged and <em>not</em> fired - a payee or device seen before - breaks it: the money goes where it always
 *       went.</li>
 *   <li><strong>The movement</strong> - the judged operation sends money to a payee: {@link #MOVEMENTS}. The
 *       {@code MONITORING} verdict (the profile leg, over signals alone) has no movement and no payee or device reading -
 *       none is observable from signals today - so there the anchor alone shows the sequence: a recovery with no
 *       movement yet is an account takeover in preparation.</li>
 * </ul>
 */
public final class AtoSequence {

    /** The rule kinds that open a sequence - the seizure of control. */
    public static final Set<RuleKind> ANCHORS =
            Collections.unmodifiableSet(EnumSet.of(RuleKind.RECENT_CREDENTIAL_CHANGE, RuleKind.RECENT_RECOVERY));

    /** The rule kinds that continue it when judged - the prepared exit. */
    public static final Set<RuleKind> CONTINUATIONS =
            Collections.unmodifiableSet(EnumSet.of(RuleKind.NEW_PAYEE, RuleKind.NEW_DEVICE));

    /**
     * The judged operations that are the sequence's movement - money leaving the customer's control to a payee. Card and
     * wallet purchases at a merchant are not (`-014`'s abuse rules judge those).
     */
    public static final Set<RiskOperation> MOVEMENTS = Collections.unmodifiableSet(
            EnumSet.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CROSS_BORDER));

    private AtoSequence() {}

    /** The signal an anchor kind is read from - the identity's {@code CREDENTIAL_CHANGED} or {@code RECOVERY_COMPLETED}. */
    public static SignalKind anchorSignal(RuleKind anchor) {
        return switch (Objects.requireNonNull(anchor, "anchor")) {
            case RECENT_CREDENTIAL_CHANGE -> SignalKind.CREDENTIAL_CHANGED;
            case RECENT_RECOVERY -> SignalKind.RECOVERY_COMPLETED;
            default -> throw new IllegalArgumentException(anchor + " is not an account-takeover anchor");
        };
    }

    /**
     * The anchor kinds whose sequence {@code hits} show for {@code operation} - in {@link RuleKind} order; empty when the
     * operation is not a movement (nor the sweep's), no anchor fired, or a judged continuation did not fire.
     */
    public static Set<RuleKind> anchorsShown(RiskOperation operation, List<RuleHit> hits) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(hits, "hits");
        boolean monitoring = operation == RiskOperation.MONITORING;
        if (!monitoring && !MOVEMENTS.contains(operation)) {
            return Set.of();
        }
        EnumSet<RuleKind> anchors = EnumSet.noneOf(RuleKind.class);
        for (RuleHit hit : hits) {
            if (hit.fired() && ANCHORS.contains(hit.kind())) {
                anchors.add(hit.kind());
            }
        }
        if (anchors.isEmpty()) {
            return Set.of();
        }
        if (!monitoring) {
            for (RuleKind continuation : CONTINUATIONS) {
                List<RuleHit> judged = hits.stream().filter(hit -> hit.kind() == continuation).toList();
                boolean refuted = !judged.isEmpty()
                        && judged.stream().allMatch(hit -> hit.outcome() == RuleOutcome.NOT_FIRED);
                if (refuted) {
                    return Set.of();
                }
            }
        }
        return Collections.unmodifiableSet(anchors);
    }
}
