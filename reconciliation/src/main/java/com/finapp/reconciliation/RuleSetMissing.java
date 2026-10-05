package com.finapp.reconciliation;

import java.util.Objects;
import java.util.UUID;

/**
 * A declared source has no {@code ACTIVE} rule set version (`P9-TSK-011`, PHASE_9_PLAN.md section
 * 12.9.2): no migration seeds one for a new source (D26) - its version 1 is a four-eyes act - so
 * until two people activate it, nothing from that source can be dated or matched. A typed refusal,
 * never a guessed default (an opener that guessed would date expectations nothing decided,
 * {@code INV-HIST-04}): the settlement file that needed it waits {@code PARSED} with the accept
 * leg's backoff, and {@code finapp.reconciliation.rule_set.missing{source}} reads 1 and alerts -
 * never a hot retry loop.
 */
public final class RuleSetMissing extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final UUID sourceId;

    public RuleSetMissing(UUID sourceId) {
        super("source " + Objects.requireNonNull(sourceId, "sourceId must not be null")
                + " has no ACTIVE rule set version: its version 1 is proposed and approved by two"
                + " controllers (RuleSetAdministration's first-version path)");
        this.sourceId = sourceId;
    }

    /** The source without an active version. */
    public UUID sourceId() {
        return sourceId;
    }
}
