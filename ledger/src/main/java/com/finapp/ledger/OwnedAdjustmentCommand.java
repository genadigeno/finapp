package com.finapp.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A reconciliation-owned adjustment proposal's input (`P8-TSK-006`, ADR-0071 §6): the break
 * resolution's ledger half, carried by {@code AdjustmentService.proposeOwned} inside the
 * caller's transaction.
 *
 * <p><strong>No idempotency key, deliberately</strong>: the owned propose joins its caller's
 * already-claimed transaction — the resolution's per-principal key (`P8-TSK-015`) is the
 * dedupe, and the resolution row stores the proposal id it minted — so a second key here
 * would be a second arbiter for one act.
 *
 * @param reference the resolution's identifier — the economic event the entry will name
 * @param reason an identifier-only statement derived by reconciliation (the resolution id,
 *     its kind and its code) — never the investigator's narrative, which stays
 *     {@code CONFIDENTIAL} on the resolution (ADR-0071 §5)
 * @param reasonCode one of the {@code RECONCILIATION} codes; the factory refuses the rest
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
        if (reasonCode.origin() != AdjustmentOrigin.RECONCILIATION) {
            throw new IllegalArgumentException(
                    "an owned proposal carries a RECONCILIATION reason code, not "
                            + reasonCode + " (ADR-0071 section 5)");
        }
        lines = List.copyOf(lines);
    }
}
