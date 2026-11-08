package com.finapp.platform.retention;

import java.io.Serial;

/** One batch of a retention sweep failed. Its rows were not deleted, and the next tick tries again (`P15-TSK-029`). */
public final class RetentionSweepException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String sqlState;

    RetentionSweepException(String table, String sqlState, Throwable cause) {
        super("The retention sweep of " + table + " failed", cause);
        this.sqlState = sqlState;
    }

    /** The database's SQLState, or {@code null}: locale-independent, and it carries no row's values. */
    public String sqlState() {
        return sqlState;
    }
}
