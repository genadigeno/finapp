package com.finapp.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A request to post one balanced journal entry (`P3-TSK-006`).
 *
 * <p>What a calling flow supplies, and nothing it should not: the money (as lines), the two
 * accounting dates (domain inputs, never clock reads — {@code DOMAIN_MODEL.md} §Time), the
 * economic event the posting records ({@code INV-LED-05}), and the idempotency key that makes
 * retrying it safe ({@code INV-IDEM-01}). The actor and correlation are deliberately
 * <em>not</em> fields — they come from the established contexts, so a caller cannot claim to
 * be somebody else by filling in a record.
 *
 * <p><strong>No entry type and no reason</strong>: this command posts, and
 * {@code JournalEntryType.POSTING} is not a parameter — the {@code P1-TSK-027} rule that a
 * caller able to ask for more has found the bypass. The adjustment command (`P3-TSK-017`)
 * arrives with its own permission, reason and design.
 *
 * @param idempotencyKey the caller's key — a Phase 4 transfer derives it from its own
 *     identifier, so a retried transfer is one posting
 * @param postingDate the accounting date deciding the period
 * @param valueDate when value is available
 * @param reference the originating economic event, identifier-shaped
 * @param lines the money; balance is validated at entry construction, before any claim
 */
public record PostingCommand(
        String idempotencyKey,
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        List<JournalLine> lines) {

    public PostingCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(postingDate, "postingDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
    }

    /** Identifiers and dates — the lines' own {@code toString}s already carry no amount. */
    @Override
    public String toString() {
        return "PostingCommand[ref=" + reference + ", posting=" + postingDate + ", lines="
                + lines.size() + "]";
    }
}
