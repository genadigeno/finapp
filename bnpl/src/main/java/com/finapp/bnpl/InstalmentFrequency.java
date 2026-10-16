package com.finapp.bnpl;

/**
 * How often a BNPL product's instalments fall due (`P12-TSK-002`; ADR-0102 decision 1, {@code PHASE_12_PLAN.md} sections
 * 2.3 and 12.2) - bnpl's own vocabulary, deliberately not lending's {@code RepaymentFrequency}, which is
 * {@code MONTHLY} only because lending's engines implement nothing else.
 *
 * <p><strong>Only implemented members</strong> (the {@code INV-LND-09} discipline): each is the frequency of an engine
 * bnpl schedules with - {@code BIWEEKLY} pay-in-4's {@code BNPL_SCHEDULE_ENGINE_V1} (`P12-TSK-008`), {@code MONTHLY}
 * pay-in-N's {@code SCHEDULE_ENGINE_V1} (lending's, through {@code creditmath}). Another frequency is a new member
 * together with the engine version that computes it.
 */
public enum InstalmentFrequency {

    /** Every 14 calendar days from the purchase's business date, no business-day adjustment (A5). */
    BIWEEKLY,

    /** One due date per calendar month, on the purchase's day of month, clamped (A6). */
    MONTHLY
}
