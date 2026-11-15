package com.finapp.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * A ledger class that touches {@code java.sql} and executes no statement, for {@code ModuleBoundaryRulesTest}'s teeth
 * (`P15-REM-001`, the gate's finding): it converts a {@code Timestamp}, reads an {@code SQLException}'s state and asks a
 * connection whether it is read-only, and reads or writes no table, so another module may name it. A test class claiming
 * {@code com.finapp.ledger}; see {@code SystemClockProbe} for why.
 */
public final class TimestampConverterProbe {

    private TimestampConverterProbe() {}

    public static Instant instant(Timestamp stamp) {
        return stamp.toInstant();
    }

    public static String state(SQLException failure) {
        return failure.getSQLState();
    }

    public static boolean readOnly(Connection unitOfWork) throws SQLException {
        return unitOfWork.isReadOnly();
    }
}
