package com.finapp.bnpl;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The servicing hold an obligation's disputes place - <strong>a condition, derived, never a state</strong> (`P12-TSK-023`;
 * ADR-0106 decision 3, A20; {@code BNPL_LIFECYCLES.md} sections 5 and 8 (d)). Read from the dispute rows: each
 * {@link Window} is one held instalment of one dispute - its due date (stable across schedule versions), the business date
 * the dispute opened on and, once resolved (refund, uphold or withdrawal), the business date of the lift.
 *
 * <ul>
 *   <li><strong>Held on a date</strong> ({@link #heldOn}): some dispute holding the due date opened on or before the date and
 *       is not resolved by it. A held instalment takes no auto-collection, no days past due and no late fee.
 *   <li><strong>The clock start</strong> ({@link #clockFrom}): the later of the due date and the latest lift by the date of a
 *       dispute that held it and was <em>resolved</em> - a refund or upheld - the due date when none was. Days past due and
 *       the late fee's day count from it, so a good-faith contest never makes the customer delinquent retroactively.
 *       <strong>A customer's withdrawal counts for nothing</strong> (the Phase 12 to 13 gate's A5-F2): its lift never moves
 *       the clock, so the instalment is judged from its due date as if it had never been held - an open, withdraw and
 *       re-open loop cannot restart days past due and the fee day for ever.
 * </ul>
 *
 * <p>{@code bnpl V015}'s {@code bnpl.dispute_holds} and {@code bnpl.dispute_clock_start} are the same rule, read by
 * {@code bnpl.days_past_due} and the fee and collection triggers. No amount here; {@link #toString()} is redacted.
 */
public record BnplDisputeHold(List<Window> windows) {

    /** No dispute ever held anything. */
    public static final BnplDisputeHold NONE = new BnplDisputeHold(List.of());

    /**
     * One held instalment of one dispute - {@code withdrawn} when the dispute ended in its customer's withdrawal, whose lift
     * never moves the clock (A5-F2).
     */
    public record Window(BnplDisputeId dispute, LocalDate dueDate, LocalDate openedOn, Optional<LocalDate> liftedOn,
            boolean withdrawn) {

        public Window {
            Objects.requireNonNull(dispute, "dispute must not be null");
            Objects.requireNonNull(dueDate, "dueDate must not be null");
            Objects.requireNonNull(openedOn, "openedOn must not be null");
            Objects.requireNonNull(liftedOn, "liftedOn must not be null");
            if (liftedOn.isPresent() && liftedOn.get().isBefore(openedOn)) {
                throw new IllegalArgumentException("a hold is lifted no earlier than it was placed");
            }
        }

        /** A window whose dispute was not withdrawn - open, or resolved as a refund or upheld. */
        public Window(BnplDisputeId dispute, LocalDate dueDate, LocalDate openedOn, Optional<LocalDate> liftedOn) {
            this(dispute, dueDate, openedOn, liftedOn, false);
        }

        /** Whether this window holds its instalment on {@code day}. */
        boolean holdsOn(LocalDate day) {
            return !openedOn.isAfter(day) && liftedOn.map(lifted -> lifted.isAfter(day)).orElse(true);
        }
    }

    public BnplDisputeHold {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows must not be null"));
    }

    /** Whether an open dispute holds the instalment due {@code due} on {@code day}. */
    public boolean heldOn(LocalDate due, LocalDate day) {
        Objects.requireNonNull(due, "due");
        Objects.requireNonNull(day, "day");
        return windows.stream().anyMatch(window -> window.dueDate().equals(due) && window.holdsOn(day));
    }

    /**
     * Where the instalment due {@code due} counts its days past due and its late fee's day from, on {@code day} - a
     * withdrawn dispute's lift never moves it (A5-F2).
     */
    public LocalDate clockFrom(LocalDate due, LocalDate day) {
        Objects.requireNonNull(due, "due");
        Objects.requireNonNull(day, "day");
        LocalDate from = due;
        for (Window window : windows) {
            if (window.dueDate().equals(due) && !window.withdrawn() && window.liftedOn().isPresent()
                    && !window.liftedOn().get().isAfter(day) && window.liftedOn().get().isAfter(from)) {
                from = window.liftedOn().get();
            }
        }
        return from;
    }

    @Override
    public String toString() {
        return "BnplDisputeHold[" + windows.size() + " windows]";
    }
}
