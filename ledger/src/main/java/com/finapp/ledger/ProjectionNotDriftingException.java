package com.finapp.ledger;

import java.io.Serial;

/**
 * Nothing to propose (`P14-TSK-026`, ADR-0119): the account's projection reads what its journal and holds derive, or an
 * entry committed to it mid-reading so nothing is known to be wrong yet ({@code ledger.ProjectionNotDrifting}, a
 * {@code 409}: read again, and re-run the close's check if it was the drift that sent you here). Names the account and
 * which, never a figure ({@code INV-AUD-02}).
 */
public final class ProjectionNotDriftingException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ProjectionNotDriftingException(LedgerAccountId account, boolean inFlight) {
        super("the projection of account " + account + (inFlight ? " was posted to mid-reading; nothing is known to be wrong - read it again" : " already reads what its journal and holds derive; nothing to rebuild"));
    }
}
