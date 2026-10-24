package com.finapp.risk;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an override is proposed - the closed code beside the proposer's text (`P13-TSK-012`; {@code PHASE_13_PLAN.md}
 * section 12.3 "a reason code and text"; {@code risk V007}'s {@code decision_override_reason_code_shape}). The code is
 * what crosses into payments as the reason of the keyed door an override's action reaches - the text never does
 * ({@code INV-RSK-08}). Never a party, an amount or a rule name.
 */
public enum OverrideReason {

    /** The engine's signal was wrong for this movement. */
    FALSE_POSITIVE,

    /** The customer was reached and verified the movement as theirs. */
    CUSTOMER_VERIFIED,

    /** The activity is explained - the customer's documents or history account for it. */
    ACTIVITY_EXPLAINED,

    /** A deliberate, reasoned exception to the policy in force. */
    POLICY_EXCEPTION,

    /** The risk the decision named is confirmed - the reason a held movement is refused rather than released. */
    RISK_CONFIRMED;

    /** The code named {@code code}, or empty for anything outside the vocabulary. */
    public static Optional<OverrideReason> named(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(reason -> reason.name().equals(code)).findFirst();
    }
}
