package com.finapp.lending;

import java.util.Objects;
import java.util.UUID;

/**
 * One period of a {@link RepaymentSchedule} as a persisted row (`P11-TSK-014`, {@code lending V008}'s
 * {@code loan_instalment}): an immutable projection row - the {@link SchedulePeriod} the engine computed, with its row
 * identity and its schedule. {@code UNIQUE (schedule_id, sequence)}; held to its schedule at commit
 * ({@code INV-LND-03}). Billing (`P11-TSK-017`) bills against it; it is never updated.
 */
public record LoanInstalment(UUID id, UUID schedule, SchedulePeriod period) {

    public LoanInstalment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");
        Objects.requireNonNull(period, "period must not be null");
    }

    /** The sequence and due date only ({@link SchedulePeriod}'s own redaction). */
    @Override
    public String toString() {
        return "LoanInstalment[" + period + "]";
    }
}
