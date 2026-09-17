package com.finapp.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A request to reverse a posted entry, wholly or in part (`P3-TSK-016`, {@code INV-REV-01}).
 *
 * <p>The lines are the caller's, validated against the original: each must post to an
 * account the original posted to <em>at the opposite direction</em>, within what remains
 * un-reversed there ({@code INV-REV-02}). A full reversal supplies the original's lines with
 * {@link Direction#opposite()} applied; a partial one supplies its own amounts — exact, so
 * no allocation or rounding ever happens inside a reversal ({@code INV-MON-03}: a scaled
 * fraction would invent the rounding step this design refuses to have).
 *
 * <p><strong>No reason field</strong>: a reversal is commanded by a flow — a refund, a
 * correction — whose own records carry the why; the reason regime is the
 * <em>adjustment</em>'s ({@code INV-REV-04}, `P3-TSK-017`), which V004's implication
 * {@code CHECK} was written to leave exactly this free. Actor and correlation come from the
 * established contexts, never from fields.
 *
 * @param idempotencyKey the caller's key — a retried refund is one reversal
 *     ({@code INV-IDEM-01})
 * @param original the entry being compensated; must not itself be a {@code REVERSAL}
 * @param postingDate the accounting date of the <em>reversal</em> — a domain input: a
 *     correction posts today about then ({@code DOMAIN_MODEL.md} §Time)
 * @param valueDate when the reversal's value applies
 * @param reference the originating economic event of the reversal itself
 * @param lines the compensating money, directions already swapped
 */
public record ReversalCommand(
        String idempotencyKey,
        JournalEntryId original,
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        List<JournalLine> lines) {

    public ReversalCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(original, "original must not be null");
        Objects.requireNonNull(postingDate, "postingDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }

    /** Identifiers and dates — the lines' own {@code toString}s already carry no amount. */
    @Override
    public String toString() {
        return "ReversalCommand[original=" + original + ", ref=" + reference + ", lines="
                + lines.size() + "]";
    }
}
