package com.finapp.risk;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A risk store's failure, named by its operation and SQLSTATE only (`P13-TSK-002`; bnpl's
 * {@code BnplStorageException}): never the driver's message, which can carry a value from the row.
 *
 * <p>The SQLSTATE is part of the message by every store's convention ({@code "... : SQLSTATE 57014"}) and readable back
 * through {@link #sqlState} (`P13-TSK-004`): T-eval tells the statement timeout ({@link #QUERY_CANCELED}) from any
 * other failure when it records the fail-safe's cause.
 */
public final class RiskStorageException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    /** PostgreSQL's {@code query_canceled} - what {@code statement_timeout} raises. */
    public static final String QUERY_CANCELED = "57014";

    private static final Pattern SQL_STATE = Pattern.compile("SQLSTATE ([0-9A-Z]{5})$");

    public RiskStorageException(String message) {
        super(message);
    }

    /** The SQLSTATE the message names, when it names one. */
    public Optional<String> sqlState() {
        String message = getMessage();
        if (message == null) {
            return Optional.empty();
        }
        Matcher matched = SQL_STATE.matcher(message);
        return matched.find() ? Optional.of(matched.group(1)) : Optional.empty();
    }
}
