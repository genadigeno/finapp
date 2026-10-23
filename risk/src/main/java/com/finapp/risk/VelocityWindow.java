package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * One {@code velocity_window} row as it stands (`P13-TSK-006`; {@code RISK_LIFECYCLES.md} section 4.3;
 * {@code INV-RSK-03}): its key, the permitted movements counted, their sum - minor units with currency and scale, never
 * a balance ({@code INV-MON-01}) - and the database instant of the latest count.
 */
public record VelocityWindow(VelocityWindowKey key, long count, Money sum, Instant lastCountedAt) {

    public VelocityWindow {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sum, "sum");
        Objects.requireNonNull(lastCountedAt, "lastCountedAt");
        if (count < 1 || count > EvaluationInputs.MAX_COUNT) {
            throw new IllegalArgumentException("a window counts 1 to " + EvaluationInputs.MAX_COUNT + " movements");
        }
        if (sum.minorUnits() < 0 || sum.minorUnits() > EvaluationInputs.MAX_MINOR) {
            throw new IllegalArgumentException("a window's sum is 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
        }
    }

    /** The window's figures as a reading - what `-003`'s engine compares at or above a threshold. */
    public VelocityReading reading() {
        return new VelocityReading(count, sum.minorUnits());
    }

    /** True when this window's sum is in {@code amount}'s currency and at its scale - the only sum it may add to. */
    public boolean sameMoneyAs(Money amount) {
        return sum.currency().equals(amount.currency()) && sum.scale() == amount.scale();
    }

    @Override
    public String toString() {
        // Never the figures or the subject (INV-RSK-08): a window is the Transaction Risk Profile's input.
        return "VelocityWindow[" + key + "]";
    }
}
