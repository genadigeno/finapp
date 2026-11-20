package com.finapp.transfers;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The transfers' figures for the operations overview (`P17-TSK-039`, gap G-150): a window's transfers by outcome - status and
 * failure reason - with how many and how much, per currency at its stored scale.
 *
 * <p>A transfer's identifier is a UUIDv7 minted with its {@code initiated_at}, by the same instance in the same act (ADR-0013),
 * so the window is an identifier range on the primary key - the platform's day window ({@code RiskDay}) - and no index on
 * {@code initiated_at} is needed.
 */
public interface TransferReports {

    /** One outcome in one currency: how many transfers, and their amounts' exact sum in minor units at {@code scale}. */
    record OutcomeSum(
            TransferStatus status,
            Optional<FailureReason> failureReason,
            CurrencyCode currency,
            int scale,
            long count,
            BigDecimal minorTotal) {
        public OutcomeSum {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(failureReason, "failureReason must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(minorTotal, "minorTotal must not be null");
            if (minorTotal.scale() != 0) {
                throw new IllegalArgumentException("a minor-unit total is a whole number");
            }
        }

        /** The total as a decimal amount at the stored scale - exact, never a float. */
        public BigDecimal amount() {
            return minorTotal.movePointLeft(scale);
        }
    }

    /** The transfers initiated in {@code [from, to)} by outcome and currency. */
    List<OutcomeSum> outcomes(Connection unitOfWork, Instant from, Instant to);

    /**
     * The lowest UUIDv7 of the millisecond {@code at} falls in - the id generator's layout (ADR-0013: 48 bits of milliseconds, the
     * version nibble, a zero counter, the RFC variant, zero randomness) - so every id minted at that millisecond or later sorts at
     * or after it in the database's order and every earlier one before it.
     */
    static UUID firstIdAt(Instant at) {
        long millisecond = Objects.requireNonNull(at, "at must not be null").toEpochMilli();
        if (millisecond < 0 || millisecond >= (1L << 48)) {
            throw new IllegalArgumentException("a UUIDv7 holds a 48-bit millisecond: " + millisecond);
        }
        return new UUID((millisecond << 16) | 0x7000L, 0x8000_0000_0000_0000L);
    }
}
