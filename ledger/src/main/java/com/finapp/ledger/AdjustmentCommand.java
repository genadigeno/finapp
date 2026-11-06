package com.finapp.ledger;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A request to <em>propose</em> a manual adjusting entry (`P3-TSK-017`, {@code INV-REV-04};
 * the proposal step since `P3-TSK-021`) — a person choosing to move value the system would
 * not have moved by itself, which makes this the highest-risk financial action on the
 * platform and its justification the only evidence of its legitimacy.
 *
 * <p><strong>The reason is required here, at the domain</strong>: a command record without
 * one is unconstructible, mirroring `V004`'s implication {@code CHECK} and
 * {@code AuditRecord}'s own refusal — three layers, one number
 * ({@code AuditRecord.MAX_REASON_LENGTH}), reconciled by test. Actor and correlation come
 * from the established contexts, never from fields; the authorising permission
 * ({@code LEDGER_ADJUST}) is the boundary's (ADR-0031).
 *
 * <p><strong>This command posts nothing</strong> ({@code INV-AUD-04}, `P3-TSK-021`): it
 * records an {@link AdjustmentProposal}, and the journal entry is posted by a
 * <em>second</em> person's approval — which is also why no approver appears here: an
 * approver field on a request would be a name anyone can type, not an authorised act.
 *
 * @param idempotencyKey the caller's key ({@code INV-IDEM-01})
 * @param postingDate the accounting date — a domain input, never a clock read
 * @param valueDate when the adjustment's value applies
 * @param reference the originating economic event, identifier-shaped
 * @param reason the justification, free prose written by a person — never rendered by
 *     {@code toString}, bound for the reason columns only
 * @param lines the money; balance is validated at entry construction, before any claim
 */
public record AdjustmentCommand(
        String idempotencyKey,
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        String reason,
        List<JournalLine> lines) {

    public AdjustmentCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(postingDate, "postingDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new AdjustmentReasonRefusedException(
                    "an adjustment records its reason (INV-REV-04): the justification is the"
                            + " only evidence the movement was legitimate");
        }
        // P14-DOC-001 (the exit review, routed by P14-TSK-026's fix round): the reason and the reference are stored in the
        // proposal, its audit record and the posted entry, and served to the approver - so neither may hold a card-number
        // or bank-account shape (InstrumentShapes, the platform's one screen; INV-AUD-02), as the rebuild's and every
        // accounting reason already may not. The message never repeats the text it refused.
        if (InstrumentShapes.holdsAny(reason)) {
            throw new AdjustmentReasonRefusedException(
                    "an adjustment's reason must not hold a card-number or bank-account shape");
        }
        if (InstrumentShapes.holdsAny(reference)) {
            throw new AdjustmentReasonRefusedException(
                    "an adjustment's reference must not hold a card-number or bank-account shape");
        }
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }

    /** Identifiers and dates — never the reason (a person's free prose) or an amount. */
    @Override
    public String toString() {
        return "AdjustmentCommand[ref=" + reference + ", posting=" + postingDate + ", lines="
                + lines.size() + "]";
    }
}
