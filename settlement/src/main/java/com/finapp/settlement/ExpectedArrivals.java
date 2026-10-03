package com.finapp.settlement;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What each pulled source owes us now (`P8-TSK-021`, the plan's cadence rule) — DERIVED from
 * stored rows, never a stored schedule, so ten instances derive the same worklist and a restart
 * loses nothing. Pure: the caller reads the rows.
 *
 * <ul>
 *   <li><strong>A daily report</strong> (the PSP's, the payout provider's, the bank's): every
 *       business date in the lookback window whose cut-off has passed — the day after it, plus
 *       the cut-off — and for which no batch was accepted.
 *   <li><strong>The scheme's cycle report</strong>: every cycle token the platform's own records
 *       name, once the lag since it was first seen has passed, for which no batch was accepted
 *       (the cycle is the batch's {@code external_batch_ref}).
 * </ul>
 *
 * <p>A key is an ISO date or a cycle token — {@code settlement.pull_permit}'s business key.
 */
public final class ExpectedArrivals {

    private ExpectedArrivals() {}

    /** A cycle token the platform's own completions and parkings name, first seen at. */
    public record ReferencedCycle(String cycle, Instant firstSeen) {

        public ReferencedCycle {
            Objects.requireNonNull(cycle, "cycle must not be null");
            Objects.requireNonNull(firstSeen, "firstSeen must not be null");
        }
    }

    /**
     * The daily keys owed: {@code [today - lookbackDays, today - 1]}, oldest first, each past its
     * cut-off at {@code now} and not in {@code accepted}.
     */
    public static List<String> daily(
            Instant now, int lookbackDays, Duration cutOff, Set<LocalDate> accepted) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(cutOff, "cutOff must not be null");
        Objects.requireNonNull(accepted, "accepted must not be null");
        if (lookbackDays < 1) {
            throw new IllegalArgumentException("the lookback is at least one day: " + lookbackDays);
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> owed = new ArrayList<>();
        for (LocalDate date = today.minusDays(lookbackDays); date.isBefore(today);
                date = date.plusDays(1)) {
            Instant due = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plus(cutOff);
            if (!due.isAfter(now) && !accepted.contains(date)) {
                owed.add(date.toString());
            }
        }
        return owed;
    }

    /** The first date of the daily lookback window at {@code now}. */
    public static LocalDate windowStart(Instant now, int lookbackDays) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(lookbackDays);
    }

    /**
     * The cycle keys owed: each referenced token whose lag has passed at {@code now} and which
     * is not in {@code accepted}, in token order.
     */
    public static List<String> cycles(
            List<ReferencedCycle> referenced, Instant now, Duration lag, Set<String> accepted) {
        Objects.requireNonNull(referenced, "referenced must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(lag, "lag must not be null");
        Objects.requireNonNull(accepted, "accepted must not be null");
        return referenced.stream()
                .filter(cycle -> !cycle.firstSeen().plus(lag).isAfter(now))
                .map(ReferencedCycle::cycle)
                .filter(cycle -> !accepted.contains(cycle))
                .distinct()
                .sorted()
                .toList();
    }
}
