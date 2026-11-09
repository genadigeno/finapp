package com.finapp.platform.abuse;

import java.io.Serial;
import java.util.Objects;

/**
 * The count could not be taken (`P15-TSK-022`). Never an admission: a caller that catches this refuses the request.
 *
 * <p>It carries a closed reason and the SQLState, and no message of the driver's: a {@code CHECK} failure's detail names the row (the
 * {@code DatabaseFailure} reasoning).
 */
public final class WindowCounterUnavailableException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why no count was taken: a closed set, safe as a metric tag. */
    public enum Reason {
        /** This instance is running its maximum of counts, and no place came free in time. */
        AT_CAPACITY,
        /** The connection, the pool or the server: a timeout, a dropped connection, a server out of resources or going down. */
        UNREACHABLE,
        /** PostgreSQL answered and refused the statement: a schema the code was not built against, a broken constraint. */
        STATEMENT_FAILED
    }

    private final Reason reason;
    private final String sqlState;

    WindowCounterUnavailableException(String message, Reason reason, String sqlState) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.sqlState = sqlState;
    }

    public Reason reason() {
        return reason;
    }

    /** The database's SQLState, or {@code null} when no statement ran. */
    public String sqlState() {
        return sqlState;
    }
}
