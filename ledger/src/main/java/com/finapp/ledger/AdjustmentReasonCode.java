package com.finapp.ledger;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * The closed category of an adjustment's justification (`P8-TSK-006`, {@code INV-REV-04}'s
 * "reason code" made a column; ADR-0071 §5). The prose {@code reason} stays the narrative —
 * this is what a report can group by and a control can close over.
 *
 * <p><strong>Each code knows its origin</strong>, and the pairing holds at three ranks: the
 * {@link AdjustmentProposal} constructor, `V015`'s generated pairing {@code CHECK}, and the
 * factories — {@code propose} assigns {@code MANUAL_CORRECTION} server-side (the generic
 * request shape is unchanged, ADR-0015), {@code proposeOwned} takes exactly the
 * {@code RECONCILIATION} codes.
 *
 * <p><strong>{@code UNCODED} exists for history only</strong>: `V015` backfills every
 * pre-Phase-8 row with it ({@code INV-HIST-01} — the rows were written before codes
 * existed, and inventing one now would be rewriting the record), and a {@code BEFORE
 * INSERT} trigger refuses it on every new proposal, for every writer.
 *
 * <p><strong>{@code RECONCILIATION_OFFSET} was deliberately dropped</strong> from ADR-0071
 * §5's drafted set at this task's design (the transition's B14, decided before `V015`
 * shipped — migrations are forward-only): no Phase 8 kind produces it — {@code
 * OFFSET_SUSPENSE} posts nothing of its own (its offset is a system {@code POSTING} keyed
 * {@code recon-suspense:}) — and the platform keeps no producerless member. A future
 * offset-posting kind widens the generated {@code CHECK} with its own forward migration,
 * the `V014` pattern.
 */
@RequiredArgsConstructor
public enum AdjustmentReasonCode {

    /** The generic door's one code, assigned server-side — a person's correction. */
    MANUAL_CORRECTION(AdjustmentOrigin.MANUAL),

    /** A break written off to `RECONCILIATION_LOSSES` (ADR-0071 §5, `WRITE_OFF`). */
    RECONCILIATION_WRITE_OFF(AdjustmentOrigin.RECONCILIATION),

    /** Parked value attributed to its owner (`TRANSFER_TO_ACCOUNT`). */
    RECONCILIATION_TRANSFER(AdjustmentOrigin.RECONCILIATION),

    /** Aged unattributable value recognised as a gain (`RECOGNISE_GAIN`, owner decision O5). */
    RECONCILIATION_GAIN(AdjustmentOrigin.RECONCILIATION),

    /** History only: rows born before `V015`. Refused on every new proposal, by trigger. */
    UNCODED(AdjustmentOrigin.MANUAL);

    private final AdjustmentOrigin origin;

    /** The origin this code pairs with — one definition for the domain and the schema. */
    public AdjustmentOrigin origin() {
        return origin;
    }

    /** The `V015` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The code→origin pairing as a SQL predicate, for `V015`'s coherence {@code CHECK} —
     * one definition, two artefacts (the {@code AccountPurpose.sqlOwnerKindRule()} pattern).
     */
    public static String sqlPairingRule() {
        return Arrays.stream(values())
                .map(
                        code ->
                                "(reason_code = '"
                                        + code.name()
                                        + "' AND origin = '"
                                        + code.origin().name()
                                        + "')")
                .collect(Collectors.joining(" OR "));
    }
}
