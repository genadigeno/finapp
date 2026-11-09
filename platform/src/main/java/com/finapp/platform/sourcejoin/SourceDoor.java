package com.finapp.platform.sourcejoin;

/** The ledger door an entry was written through: which of the journal's entry types a join answers for. */
public enum SourceDoor {
    POSTING,
    REVERSAL,
    ADJUSTMENT
}
