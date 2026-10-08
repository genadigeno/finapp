package com.finapp.credit;

/**
 * The derived figures a policy rule may read (ADR-0086 section 1, PHASE_10_PLAN.md section 12.6) - closed, like the
 * attribute vocabulary: a new figure is a reviewed code change with a new engine version, never an expression. Their
 * arithmetic is the assessment's (ADR-0088 and the scorecard); a rule only compares them.
 */
public enum PolicyFigure {

    /** The scorecard's integer score. */
    SCORE(AttributeValueType.INTEGER),

    /** Income less expenditure, obligations and the stressed repayment, in the product's currency. */
    DISPOSABLE_INCOME(AttributeValueType.MONEY),

    /** Whether the disposable income meets the policy's minimum. */
    AFFORDABLE(AttributeValueType.BOOLEAN),

    /** External balance, platform credit, reservations and the requested amount, in the product's currency. */
    EXPOSURE(AttributeValueType.MONEY),

    /** The policy's maximum exposure less the exposure - negative when the request would exceed it. */
    EXPOSURE_HEADROOM(AttributeValueType.MONEY);

    private final AttributeValueType valueType;

    PolicyFigure(AttributeValueType valueType) {
        this.valueType = valueType;
    }

    /** The type of the figure's value. */
    public AttributeValueType valueType() {
        return valueType;
    }
}
