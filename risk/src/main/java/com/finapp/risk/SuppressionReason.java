package com.finapp.risk;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an operator proposes to suppress an alert - the closed code beside the proposer's text (`P13-TSK-015`;
 * {@code risk V010}'s {@code risk_alert_suppression_reason_code_is_known}; {@code PHASE_13_PLAN.md} section 15's
 * {@code finapp.risk.alert.suppressed{reason_code}}). A suppression closes an alert without a case - the act a
 * supervisor asks about - so it is two persons' act with a reason, and its code a bounded vocabulary a metric may tag.
 *
 * <p><strong>Deliberately no {@code DUPLICATE}:</strong> a duplicate is the system's to fold - it attaches to the
 * subject's open case ({@link AlertStatus#ATTACHED}) - and is never a reason to suppress.
 */
public enum SuppressionReason {

    /** The rule misfired for this subject and window - nothing of the pattern it looks for is present. */
    FALSE_POSITIVE,

    /** The activity is explained - the subject's documents or history account for it. */
    ACTIVITY_EXPLAINED,

    /** The rule or its parameters are wrong - the remedy is a policy version, and the alert is not worth a case. */
    RULE_DEFECT,

    /** The evidence the rule read is wrong - a data defect the owning domain corrects. */
    DATA_DEFECT;

    /** The code named {@code code}, or empty for anything outside the vocabulary. */
    public static Optional<SuppressionReason> named(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(reason -> reason.name().equals(code)).findFirst();
    }
}
