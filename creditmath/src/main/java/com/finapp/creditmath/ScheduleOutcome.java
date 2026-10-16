package com.finapp.creditmath;

import java.util.Objects;

/**
 * What the schedule engine answers (`P11-TSK-006`; ADR-0093): a {@link Projected} schedule, or a {@link Refused} one
 * with its typed reason - the caller decides what a refusal means at its door (a {@code 422} at the terms proposal, an
 * application closure at the offer), and nothing in between is an exception.
 */
public sealed interface ScheduleOutcome {

    /** The schedule, conserving by construction ({@link Schedule}). */
    record Projected(Schedule schedule) implements ScheduleOutcome {
        public Projected {
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    /**
     * No schedule: the reason, and a detail naming the defect - a term in months or a period in days, never an amount or
     * a rate.
     */
    record Refused(ScheduleRefusal reason, String detail) implements ScheduleOutcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
