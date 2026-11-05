package com.finapp.ledger;

import java.io.Serial;

/**
 * A rebuild of the account already stands {@code PROPOSED} (`P14-TSK-026`; V031's
 * {@code projection_rebuild_one_standing_per_account}): decide it first ({@code ledger.ProjectionRebuildStanding}, a
 * {@code 409}).
 */
public final class ProjectionRebuildStandingException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ProjectionRebuildStandingException(LedgerAccountId account) {
        super("a rebuild of the projection of account " + account + " already awaits a decision");
    }
}
