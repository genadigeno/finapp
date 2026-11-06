package com.finapp.ledger;

import java.io.Serial;

/**
 * The account's projection cannot be rebuilt by this door (`P14-TSK-026`, ADR-0119; {@code ledger.ProjectionNotRebuildable},
 * a {@code 409}): its history cannot be derived (mixed scales, a hold at another scale - {@code INV-MON-03}), its row is
 * persisted at a scale the derivation does not have (the scale is an identity column, the migrator's to change), or its row
 * stands for an account with no entries, which {@code last_entry_seq >= 1} cannot say (deleting the row is the migrator's
 * act, V006). Names the account and the cause, never a figure ({@code INV-AUD-02}).
 */
public final class ProjectionNotRebuildableException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public ProjectionNotRebuildableException(LedgerAccountId account, String cause) {
        super("the projection of account " + account + " cannot be rebuilt here: " + cause);
    }
}
