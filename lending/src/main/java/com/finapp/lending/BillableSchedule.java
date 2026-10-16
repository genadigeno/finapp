package com.finapp.lending;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.EngineVersion;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The latest schedule version of an instalment loan as billing reads it (`P11-TSK-017`; {@code PHASE_11_PLAN.md} section
 * 12.2 steps 5 and 7): its identity and version, its engine, the level instalment {@code A} principal due is cut from,
 * the intended day the calendar continues on after the final due date, and its instalments - identity, sequence and
 * period - oldest first. Only the latest version is ever billed: a superseded version's unbilled instalments never are.
 *
 * @param instalment the level instalment {@code A}
 */
public record BillableSchedule(
        UUID id, int version, EngineVersion engine, Money instalment, int intendedDay, List<Instalment> instalments) {

    public BillableSchedule {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        instalments = List.copyOf(Objects.requireNonNull(instalments, "instalments must not be null"));
        if (version < 1 || instalments.isEmpty()) {
            throw new IllegalArgumentException("a schedule version is numbered from 1 and has instalments");
        }
        for (int i = 0; i < instalments.size(); i++) {
            if (instalments.get(i).sequence() != i + 1) {
                throw new IllegalArgumentException("a schedule's instalments are numbered 1..n in order");
            }
        }
    }

    /** One instalment: its row identity, sequence and period {@code [D(j-1), Dj)}. */
    public record Instalment(UUID id, int sequence, AccrualPeriod period) {

        public Instalment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(period, "period must not be null");
        }
    }

    /** The final instalment - the one that bills all remaining principal. */
    public Instalment last() {
        return instalments.get(instalments.size() - 1);
    }

    /** The accrual calendar: these periods, continued monthly on the intended day after the final due date. */
    public InstalmentCalendar calendar() {
        return new InstalmentCalendar(instalments.stream().map(Instalment::period).toList(), intendedDay);
    }

    /** The version and the size - never an amount. */
    @Override
    public String toString() {
        return "BillableSchedule[" + id + ", v" + version + ", " + instalments.size() + " instalments]";
    }
}
