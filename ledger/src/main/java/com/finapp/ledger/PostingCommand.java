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
 * <h2>Dating (`P14-TSK-006`, owner decision C3)</h2>
 *
 * <p>{@link Dating#AS_STATED} - the default, and what the five-argument constructor means - posts on
 * {@code postingDate} or is refused by the posting cutoff ({@link PeriodClosedException}).
 * {@link Dating#FLOOR_AT_FIRST_OPEN} is a <em>system</em> back-dating poster's explicit request,
 * registered as its rule's cutoff reaction in accounting's posting rule register: {@code postingDate}
 * is the date the poster states (its economic or stored date), and the ledger posts on that date or,
 * when the date lies on or before the cutoff, on the first open date - the day after it - read under
 * the cutoff's own shared key in the posting's transaction ({@link PostingCutoffs#firstOpenOnOrAfter}).
 * The value date is never touched, and the key and its fingerprint bind the <strong>stated</strong>
 * date, so a replay converges on the first entry whatever the cutoff did meanwhile. A person's
 * adjustment has no such request: one dated into a locked period is refused at approval.
 *
 * @param idempotencyKey the caller's key — a Phase 4 transfer derives it from its own
 *     identifier, so a retried transfer is one posting
 * @param postingDate the accounting date deciding the period - under
 *     {@link Dating#FLOOR_AT_FIRST_OPEN} the stated date the floor starts from
 * @param valueDate when value is available
 * @param reference the originating economic event, identifier-shaped
 * @param lines the money; balance is validated at entry construction, before any claim
 * @param dating whether the stated posting date is final or floored at the first open date
 */
public record PostingCommand(
        String idempotencyKey,
        LocalDate postingDate,
        LocalDate valueDate,
        String reference,
        List<JournalLine> lines,
        Dating dating) {

    /**
     * How the posting date is taken (`P14-TSK-006`, C3). Not a re-dating the ledger chooses: the
     * poster asks, its rule's register entry says so, and the floored entry's audit record names the
     * stated date ({@code flooredFrom=}).
     */
    public enum Dating {
        /** Posted on the stated date, or refused by the posting cutoff ({@code ledger.PeriodClosed}). */
        AS_STATED,
        /**
         * Posted on the stated date, or - when the cutoff covers it - on the first open date, the
         * value date kept: a system poster's catch-up never refused by a lock (C3).
         */
        FLOOR_AT_FIRST_OPEN
    }

    public PostingCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(postingDate, "postingDate must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        Objects.requireNonNull(dating, "dating must not be null");
        lines = List.copyOf(lines);
    }

    /** A command posted on its stated date ({@link Dating#AS_STATED}) - every poster but C3's floored ones. */
    public PostingCommand(
            String idempotencyKey,
            LocalDate postingDate,
            LocalDate valueDate,
            String reference,
            List<JournalLine> lines) {
        this(idempotencyKey, postingDate, valueDate, reference, lines, Dating.AS_STATED);
    }

    /** Identifiers and dates — the lines' own {@code toString}s already carry no amount. */
    @Override
    public String toString() {
        return "PostingCommand[ref=" + reference + ", posting=" + postingDate
                + (dating == Dating.FLOOR_AT_FIRST_OPEN ? " (floored at the first open date)" : "")
                + ", lines=" + lines.size() + "]";
    }
}
