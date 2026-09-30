package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.UUID;

/**
 * Whether an evidence link's target exists OUTSIDE this module (`P8-TSK-014`, ADR-0069 §7) —
 * a settlement file, batch or line, a ledger journal entry, a payments provider evidence row.
 * Declared here, implemented in {@code app} over those modules' public read stores (ADR-0064:
 * reconciliation compiles against no sibling but {@code ledger}). Read-only and lock-free; a
 * link to a target that does not exist is refused, never stored dangling. The targets this
 * module owns ({@code RUN}, {@code DECISION}, {@code OPERATION}) are verified by
 * {@link BreakCaseStore}, never here.
 */
public interface EvidenceTargets {

    /**
     * @param kind one of the externally owned kinds
     * @param id the target's own identifier
     */
    boolean exists(Connection unitOfWork, EvidenceTargetKind kind, UUID id);
}
