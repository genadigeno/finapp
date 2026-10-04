package com.finapp.fx;

/**
 * Which flow a pricing pair prices (`P9-TSK-007`, `PHASE_9_PLAN.md`: "customer markup - pricing
 * policy, per purpose"): a wallet conversion, or the FX leg of a cross-border payment - each with
 * its own window (O7: 30 s and 60 s).
 */
public enum PricingPurpose {
    CONVERSION,
    CROSS_BORDER
}
