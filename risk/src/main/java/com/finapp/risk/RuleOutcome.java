package com.finapp.risk;

/**
 * What one rule's evaluation came to (`P13-TSK-003`; {@code INV-RSK-04}): it fired, it did not, or the reading it
 * judges was {@link Reading absent} - recorded as such on the hit, because "we did not look" is a different
 * explanation from "we looked and it was not so".
 */
public enum RuleOutcome {
    /** The rule's condition held: its weight contributes and its hard decision counts. */
    FIRED,
    /** The reading was present and the condition did not hold. */
    NOT_FIRED,
    /** The reading the rule judges was absent: the rule did not fire and says why. */
    ABSENT
}
