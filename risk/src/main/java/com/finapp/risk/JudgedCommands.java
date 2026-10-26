package com.finapp.risk;

import java.sql.Connection;
import java.util.Collection;
import java.util.Set;

/**
 * The judged commands as their <strong>owners</strong> hold them (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 13.3,
 * 17; {@code INV-RSK-01}; {@code DESIGN-023} D2) - the other side of {@link AssessmentCensus}, read through a port
 * {@code app} implements over transfers', payments' and crossborder's own tables, because counting the judged commands
 * from risk's own table would prove nothing (a tautology). Reads on the caller's snapshot, no lock, no edge.
 *
 * <p>The command reference is the seam's: a transfer's id, a withdrawal's id, a payment intent's id, a cross-border
 * payment's <em>quote</em> id - the {@code command_ref} the assessment was sealed under.
 */
public interface JudgedCommands {

    /**
     * The references of the commands of {@code operation} that the owner committed as judged - the rows the seam judged
     * by construction, and no row it may not have - whose own identifier was minted on {@code day} (a primary-key range).
     */
    Set<CommandRef> judged(Connection snapshot, RiskOperation operation, RiskDay day);

    /**
     * Of {@code refs}, those whose command the owner holds a record of - whatever its state and its day. A refused
     * cross-border command leaves the owner no row by design, so the census never asks this of one.
     */
    Set<CommandRef> recorded(Connection snapshot, RiskOperation operation, Collection<CommandRef> refs);
}
