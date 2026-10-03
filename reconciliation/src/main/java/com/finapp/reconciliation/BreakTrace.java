package com.finapp.reconciliation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A break's trace (`P8-TSK-014`, ADR-0069 §7): the stored identifier chain from the break to
 * the raw settlement file and the journal entries, as typed steps — {@code file → batch →
 * recognition entry → run → item → decision (candidates) → allocation | park | break → notes →
 * resolution → adjustment proposal → journal entry}, and on the internal side {@code
 * expectation → journal entry → operation → provider evidence}. Identifiers only: raw file
 * content stays behind settlement's audited content read, provider payloads behind payments'.
 * Never joined by time — every step is a stored reference a previous step read.
 */
public record BreakTrace(UUID breakId, List<Step> steps, boolean truncated) {

    public BreakTrace {
        Objects.requireNonNull(breakId, "breakId must not be null");
        steps = List.copyOf(steps);
    }

    /** What a trace node is. */
    public enum NodeKind {
        BREAK,
        EXPECTATION,
        EXTERNAL_ITEM,
        SUSPENSE_ITEM,
        RUN,
        DECISION,
        ALLOCATION,
        PARK,
        RESOLUTION,
        ADJUSTMENT_PROPOSAL,
        JOURNAL_ENTRY,
        SETTLEMENT_LINE,
        SETTLEMENT_BATCH,
        SETTLEMENT_FILE,
        OPERATION,
        PROVIDER_EVIDENCE,
        NOTE,
        EVIDENCE_LINK
    }

    /** How one node reaches the next — each a stored reference. */
    public enum Relation {
        /** The break stands on its subject. */
        SUBJECT,
        /** The break continues an earlier one ({@code follows_break_id}). */
        FOLLOWS,
        NOTED,
        LINKED,
        RESOLVED_BY,
        /** A resolution's adjustment proposal. */
        PROPOSED_AS,
        /** A park, an unpark, a resolution or a recognition posted this entry. */
        POSTED_AS,
        /** An item's canonical settlement line. */
        CARRIED_BY_LINE,
        IN_RUN,
        OF_BATCH,
        FROM_FILE,
        /** An accepted batch's fee recognition entry. */
        RECOGNISED_BY,
        DECIDED_BY,
        /** A decision decided this item. */
        DECIDED,
        ALLOCATED,
        ALLOCATED_TO,
        /** An item's remainder parked as this suspense item. */
        PARKED_AS,
        /** A suspense item's own park, and the parking entry. */
        PARKED_BY,
        /**
         * What released a suspense item: an unpark's park, or - a release that posts no inverse -
         * the resolution its {@code cause_ref} names. *(Corrected 2026-10-02 by the Phase 8 -> 9
         * transition, REC-9: the park-less releases were dropped.)*
         */
        RELEASED_BY,
        /** A suspense item's owning break, where it is not the traced one. */
        OWNED_BY,
        /** A suspense item's or an allocation's external item. */
        OF_ITEM,
        /** An expectation's completing entry. */
        OPENED_BY,
        /** An expectation tracks this operation. */
        TRACKS,
        /** A retained provider statement about the operation. */
        EVIDENCED_BY,
        /**
         * A parking's suspense item, and the expectation its entry's clearing line opened —
         * the one entry both carry (`P8-TSK-020`).
         */
        EXPECTED_AS
    }

    /** One stored reference: {@code from —relation→ to}. */
    public record Step(
            NodeKind fromKind, String fromId, Relation relation, NodeKind toKind, String toId) {

        public Step {
            Objects.requireNonNull(fromKind, "fromKind must not be null");
            Objects.requireNonNull(fromId, "fromId must not be null");
            Objects.requireNonNull(relation, "relation must not be null");
            Objects.requireNonNull(toKind, "toKind must not be null");
            Objects.requireNonNull(toId, "toId must not be null");
        }
    }
}
