package com.finapp.ledger;

import com.finapp.sharedkernel.correlation.Correlation;
import java.util.Objects;
import java.util.Optional;

/**
 * Who and what stands behind a journal entry (`P3-TSK-005`, {@code INV-LED-05}).
 *
 * <p>Kept beside the entry rather than on it: the accounting value (`P3-TSK-004`) answers
 * <em>does this balance?</em>, and this answers <em>who may be asked about it?</em> — the
 * command (`P3-TSK-006`) holds both and hands them to the store together, which is the one
 * write path {@code INV-LED-04} permits.
 *
 * @param entryType what kind of act this records
 * @param reference the originating economic event, identifier-shaped — a posting nobody can
 *     explain is a posting nobody can defend
 * @param reason required for an {@code ADJUSTMENT} ({@code INV-REV-04}), absent otherwise
 *     unless a later entry type decides it wants one; free prose written by a person, so it
 *     never appears in this record's {@code toString}
 * @param reverses the original a {@code REVERSAL} compensates ({@code INV-REV-01}) — present
 *     exactly when the type is {@code REVERSAL}, mirroring the schema's implication
 *     {@code CHECK}
 * @param actorId who commanded the posting, by identifier — a person's identity id, or the
 *     platform's reserved {@code system}. The identifier alone, deliberately: the journal's
 *     column holds an id, and the actor's TYPE is the audit record of the same command's to
 *     state authoritatively — a second typed copy here would be a guess on read-back
 * @param correlation the flow's correlation, <strong>with the cause resolved</strong>: the
 *     journal's causation column is {@code NOT NULL}, and at a flow root the request is the
 *     cause (`P1-TSK-006`'s answer) — resolving that is the command's job, before here
 * @param idempotencyScope the idempotency scope of the command that wrote the entry, so every
 *     entry ties back to exactly one commanded effect ({@code INV-LED-04}, {@code INV-IDEM-01})
 */
public record PostingAttribution(
        JournalEntryType entryType,
        String reference,
        Optional<String> reason,
        Optional<JournalEntryId> reverses,
        String actorId,
        Correlation correlation,
        String idempotencyScope) {

    public PostingAttribution {
        Objects.requireNonNull(entryType, "entryType must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(reason, "reason must not be null (use Optional.empty())");
        Objects.requireNonNull(reverses, "reverses must not be null (use Optional.empty())");
        if ((entryType == JournalEntryType.REVERSAL) != reverses.isPresent()) {
            // The schema's implication CHECK, stated where a caller meets it first
            // (INV-REV-01): a reversal references its original, and nothing else may.
            throw new IllegalArgumentException(
                    "a REVERSAL references the entry it compensates, and no other kind does"
                            + " (INV-REV-01)");
        }
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        Objects.requireNonNull(idempotencyScope, "idempotencyScope must not be null");
        if (actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must name who commanded the posting");
        }
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must name the economic event");
        }
        if (idempotencyScope.isBlank()) {
            throw new IllegalArgumentException("idempotencyScope must name the command");
        }
        if (entryType == JournalEntryType.ADJUSTMENT && reason.isEmpty()) {
            throw new IllegalArgumentException(
                    "an adjustment records its reason (INV-REV-04): a person moving value the"
                            + " system would not have moved is legitimate only as far as its"
                            + " justification");
        }
        if (correlation.cause().isEmpty()) {
            throw new IllegalArgumentException(
                    "the journal records causation NOT NULL: at a flow root the request is the"
                            + " cause (P1-TSK-006), and resolving that is the command's job"
                            + " before attribution");
        }
    }

    /** Identifiers and enumerated names — never the reason, which is a person's free prose. */
    @Override
    public String toString() {
        return "PostingAttribution[" + entryType + ", ref=" + reference + ", " + actorId
                + ", " + correlation + "]";
    }
}
