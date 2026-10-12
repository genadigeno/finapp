package com.finapp.lending;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The payout machine, path X (`P11-TSK-015`; {@code LENDING_LIFECYCLES.md} section 4.7, ADR-0097 point 4) - lending only
 * <em>observes</em> payments' withdrawal:
 *
 * <pre>
 * PENDING ──claim──> DISPATCHING ──payments accepted──> DISPATCHED ──> PAID_OUT | FAILED
 *                         └──payments refused or could not route (the hold released)──> NOT_DISPATCHED
 * </pre>
 *
 * <p>Declared here so the machine reads in one place; {@code lending V009}'s {@code CHECK} and trigger are its other two
 * ranks. {@code UNKNOWN} at payments is {@code DISPATCHED} here - held, no interest - until payments concludes from an
 * authenticated inquiry; lending never re-dispatches ({@code INV-RAIL-02}). The planned {@code RETURNED} is not a state:
 * payments' withdrawal keeps no return record (the instant rail is final on acceptance, {@code INV-REV-03}), so it would
 * have no producer.
 */
public enum PayoutStatus {

    /** Born in the disbursement beside the hold; the payout sweep's to claim. */
    PENDING,

    /** Claimed with a database-stamped permit, committed before payments is called; a lapsed permit re-drives. */
    DISPATCHING,

    /** Payments holds the withdrawal (named by value), awaiting the scheme's word - interest waits. */
    DISPATCHED,

    /** Payments concluded the withdrawal {@code COMPLETED}: the funds reached the borrower's bank. */
    PAID_OUT,

    /** Payments concluded the withdrawal {@code FAILED}: the hold released, the funds free in the wallet. */
    FAILED,

    /** Payments refused or could not route the withdrawal: lending released the hold, the funds free in the wallet. */
    NOT_DISPATCHED;

    /** The states reachable from this one. */
    public Set<PayoutStatus> permittedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(DISPATCHING);
            case DISPATCHING -> EnumSet.of(DISPATCHED, NOT_DISPATCHED);
            case DISPATCHED -> EnumSet.of(PAID_OUT, FAILED);
            case PAID_OUT, FAILED, NOT_DISPATCHED -> EnumSet.noneOf(PayoutStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** The values as a SQL literal list - {@code lending V009}'s status {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
