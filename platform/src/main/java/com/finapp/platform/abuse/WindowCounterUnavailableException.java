package com.finapp.platform.abuse;

import java.io.Serial;

/**
 * The count could not be taken (`P15-TSK-022`). Never an admission: a caller that catches this refuses the request.
 *
 * <p>It carries the SQLState and no message of the driver's: a {@code CHECK} failure's detail names the row (the
 * {@code DatabaseFailure} reasoning).
 */
public final class WindowCounterUnavailableException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String sqlState;

    WindowCounterUnavailableException(String reason, String sqlState) {
        super(reason);
        this.sqlState = sqlState;
    }

    /** The database's SQLState, or {@code null} when no statement ran. */
    public String sqlState() {
        return sqlState;
    }
}
