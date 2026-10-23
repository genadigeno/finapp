package com.finapp.risk;

/**
 * What set a customer risk profile version (`P13-TSK-007`; {@code RISK_LIFECYCLES.md} section 4.9; {@code INV-RSK-10},
 * {@code INV-RSK-07}) - the {@code cause} column of {@code customer_risk_profile_version}, held to the same two codes by
 * its {@code CHECK}.
 */
public enum ProfileCause {

    /** The {@code MONITORING} policy version's rating bands over the party's signal score, through the engine. */
    RECOMPUTE,

    /**
     * An approved case disposition under four eyes (`P13-TSK-016`) - the only cause that may set, or lift,
     * {@link RiskRating#PROHIBITED}.
     */
    DISPOSITION
}
