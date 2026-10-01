package com.finapp.reconciliation;

/**
 * How a resolution ended, as {@code finapp.reconciliation.resolution} counts it (`P8-TSK-024`,
 * `PHASE_8_PLAN.md` §15): the machine's three decisions, the platform's evidence, and the one
 * refusal worth counting - an approval refused because its subject moved.
 */
public enum ResolutionOutcome {
    APPROVED,
    REJECTED,
    WITHDRAWN,
    EVIDENCED,
    STALE
}
