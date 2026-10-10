package com.finapp.lending;

import java.util.Objects;

/**
 * The day-count conventions a loan's terms may name (`P11-TSK-002`; ADR-0094, owner decision L5, assumption A22).
 *
 * <p><strong>The enum admits only implemented members</strong> ({@code INV-LND-09}: terms naming a convention no
 * engine version implements are refused). A convention that could be written on a terms version but that no accrual
 * engine computes would be a contract the platform cannot honour - so the refusal is made by the type, before any
 * terms exist: there is nothing else to name. Each member declares the {@link LendingEngine#ACCRUAL} engine version
 * that first implements it, checked as the class loads; another convention ({@code ACT_360}, {@code 30E/360}) is a new
 * member together with the new accrual engine version that implements it - never a member alone.
 */
public enum DayCountConvention {

    /**
     * Actual days over a fixed 365-day year: a day's interest is {@code principal x rate / 365}, 29 February accruing
     * one ordinary day (L5). Implemented by {@link EngineVersion#ACCRUAL_ENGINE_V1}.
     */
    ACT_365F(EngineVersion.ACCRUAL_ENGINE_V1);

    private final EngineVersion implementedBy;

    DayCountConvention(EngineVersion implementedBy) {
        this.implementedBy = Objects.requireNonNull(implementedBy, "implementedBy");
        if (implementedBy.engine() != LendingEngine.ACCRUAL) {
            throw new IllegalStateException(name() + ": a day-count convention is implemented by an accrual engine");
        }
    }

    /** The accrual engine version that first implements this convention. */
    public EngineVersion implementedBy() {
        return implementedBy;
    }
}
