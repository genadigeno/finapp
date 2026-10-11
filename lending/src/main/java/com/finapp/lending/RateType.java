package com.finapp.lending;

/**
 * How a terms version's nominal rate behaves over a loan's life (`P11-TSK-005`; {@code PHASE_11_PLAN.md} section 12.1,
 * ADR-0092 section 2).
 *
 * <p><strong>Only implemented members</strong> ({@code INV-LND-09}). {@code FIXED} alone: a variable rate needs a
 * reference-rate source the platform does not have, and an accrual engine version that reads it - a member added here
 * without both would be a contract the platform cannot honour.
 */
public enum RateType {

    /** The nominal annual rate pinned on the agreement holds for the loan's life. */
    FIXED
}
