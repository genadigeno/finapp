package com.finapp.lending;

/**
 * A loan write-off's machine (`P14-TSK-021`, {@code lending V032}): {@code PROPOSED -> APPROVED | REJECTED}, one way,
 * decided once. A proposal that can no longer post stands {@code PROPOSED} until a person rejects it - its door out, on any
 * account status.
 */
public enum LoanWriteOffStatus {

    /** Named by one person, with what they read under the loan's lock. Nothing is posted. */
    PROPOSED,

    /** Approved by another person, posting rule 14 and moving the loan {@code WRITTEN_OFF}. */
    APPROVED,

    /** Rejected: terminal, nothing posted. */
    REJECTED
}
