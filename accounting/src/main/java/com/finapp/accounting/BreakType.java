package com.finapp.accounting;

/**
 * What an accounting break records (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 13.3; ADR-0117; owner decision C11)
 * - the three levels of GL &lt;-&gt; ledger &lt;-&gt; source reconciliation, each a close check producing born-once
 * breaks, <strong>never a correction</strong> ({@code INV-ACC-12}): resolution is a correcting entry through the owning
 * domain's existing door, referenced by the break. A GL break has no external evidence and parks no value;
 * reconciliation's own breaks and suspense are disclosed, never copied into this vocabulary.
 */
public enum BreakType {

    /** Level 1: a journal line no rule of the pinned version maps. */
    MAPPING_GAP,
    /** Level 1: a journal line two rules of the pinned version map. */
    DOUBLE_MAPPING,
    /** Level 2: an entry whose posting-key namespace the register does not hold. */
    UNREGISTERED_POSTING,
    /** Level 2: an entry whose lines do not match its registered rule's shape. */
    SHAPE_MISMATCH,
    /** Level 2: an entry not balanced per currency - impossible by the ledger's trigger, and still checked. */
    UNBALANCED_ENTRY,
    /** Level 3: a product proof's finding - missing, duplicate, amount or currency discrepancy, unreversed reversal. */
    SOURCE_DISCREPANCY,
    /** Level 3: an entry expected and not yet posted - blocking for accruals, disclosed for evidence in transit. */
    SOURCE_DELAYED
}
