package com.finapp.creditmath;

/**
 * Why the schedule engine produced no schedule (`P11-TSK-006`; ADR-0093 sections 1 and 5): a typed answer, never an
 * exception from arithmetic and never a negatively amortising schedule.
 */
public enum ScheduleRefusal {

    /**
     * Some period's principal portion would not be positive - the first period's interest at least the level
     * instalment (a long first period at a high rate), or the balance exhausted before the last period. The terms door
     * answers it {@code 422 lending.TermsNotAmortising}; an offer that meets it closes its application
     * {@code CLOSED_UNDECIDED (TERMS_NOT_AMORTISING)} (`P11-TSK-011`).
     */
    NOT_AMORTISING,

    /**
     * The terms name something this engine version does not compute - another product kind, another schedule engine
     * version, a rate type, frequency, day count or rounding mode version 1 does not implement ({@code INV-LND-09}).
     */
    CONVENTION_NOT_IMPLEMENTED,

    /**
     * The request itself is not one: a principal that is not positive, not in the terms' currency, not at its scale or
     * beyond the engine's range, a term or an intended day outside the engine's range, or a recalculation's next due
     * date that is not after its effective date or not on its intended day.
     */
    INVALID_REQUEST
}
