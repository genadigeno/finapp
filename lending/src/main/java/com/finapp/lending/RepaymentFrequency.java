package com.finapp.lending;

/**
 * How often a loan's repayments fall due (`P11-TSK-002`; {@code PHASE_11_PLAN.md} section 12.1) - pinned on the
 * agreement.
 *
 * <p><strong>Only implemented members.</strong> {@code MONTHLY} alone, because the schedule and statement engines
 * version 1 implement nothing else: a frequency that could be named on terms but that no engine computes would be a
 * contract the platform cannot honour. Another frequency is a new member together with the engine version that
 * implements it.
 */
public enum RepaymentFrequency {

    /** One due date per calendar month, on the agreement's repayment or statement day (1-28). */
    MONTHLY
}
