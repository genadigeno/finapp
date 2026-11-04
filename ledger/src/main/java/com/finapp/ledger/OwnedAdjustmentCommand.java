package com.finapp.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * An owned adjustment proposal's input (`P8-TSK-006`, ADR-0071 §6; `P14-TSK-013`): the ledger
 * half of a break resolution, carried by {@code AdjustmentService.proposeOwned}, or of a
 * prior-period adjustment, carried by {@code AdjustmentService.proposePriorPeriod}, inside the
 * caller's transaction.
 *
 * <p><strong>No idempotency key, deliberately</strong>: the owned propose joins its caller's
 * already-claimed transaction — the owner's per-principal key is the dedupe, and the owner's
 * row stores the proposal id it minted — so a second key here would be a second arbiter for
 * one act.
 *
 * @param reference the owner's identifier — the economic event the entry will name
 * @param reason an identifier-only statement derived by the owner (its id, its kind and its
 *     code) — never the person's narrative, which stays on the owner's row (ADR-0071 §5)
 * @param reasonCode a code of an owned origin; {@code MANUAL} codes are refused here, and each
 *     door refuses the other owner's
 */
public record OwnedAdjustmentCommand(
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        String reason,
        AdjustmentReasonCode reasonCode,
        List<JournalLine> lines) {

    public OwnedAdjustmentCommand {
        Objects.requireNonNull(postingDate, "postingDate is a domain input and must be given");
        Objects.requireNonNull(valueDate, "valueDate is a domain input and must be given");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        if (reasonCode.origin() == AdjustmentOrigin.MANUAL) {
            throw new IllegalArgumentException(
                    "an owned proposal carries an owner's reason code, not "
                            + reasonCode + " (ADR-0071 section 5)");
        }
        lines = List.copyOf(lines);
    }
}
