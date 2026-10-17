package com.finapp.bnpl;

import java.util.Objects;

/**
 * What pay-in-4's schedule engine answers (`P12-TSK-008`; ADR-0102 decision 2): a {@link Projected} schedule, or a
 * {@link Refused} one with its typed reason - the caller decides what a refusal means at its door (a {@code 422} at the
 * terms proposal, a closure at the offer), and nothing in between is an exception. The {@code ScheduleOutcome} shape of
 * {@code creditmath}'s engine, bnpl's own type: the two engines' answers are never interchangeable.
 */
public sealed interface BnplScheduleOutcome {

    /** The schedule, conserving by construction ({@link BnplSchedule}). */
    record Projected(BnplSchedule schedule) implements BnplScheduleOutcome {
        public Projected {
            Objects.requireNonNull(schedule, "schedule");
        }
    }

    /** No schedule: the reason, and a detail naming the defect - never an amount. */
    record Refused(BnplScheduleRefusal reason, String detail) implements BnplScheduleOutcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
