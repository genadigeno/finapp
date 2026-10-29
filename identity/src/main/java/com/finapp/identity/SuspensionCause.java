package com.finapp.identity;

/**
 * Who suspended an identity - recorded by identity beside the status, in the same conditional write (identity
 * {@code V024}; the Phase 13 completion gate, owner item 25). Identity owns the decision: an administrator's
 * reinstatement lifts any suspension; risk's lifts only its own, so a risk case's action never undoes a suspension an
 * administrator decided ({@code INV-RSK-02}: risk is never an alternative owner of identity state).
 */
public enum SuspensionCause {

    /** Identity's administration door - an administrator's decision. */
    ADMINISTRATOR,

    /** Risk's {@code SUSPEND_IDENTITY} action, through identity's own command. */
    RISK
}
