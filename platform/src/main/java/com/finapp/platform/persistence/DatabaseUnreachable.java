package com.finapp.platform.persistence;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a failure is the database lost - not reachable, or gone mid-transaction - rather than anything a statement did
 * (`P16-REM-002`; {@code THREAT_MODEL.md} section 6 item 61 (i)).
 *
 * <p>A command that could not reach the database is answered {@code 503 api.TemporarilyUnavailable} with a {@code Retry-After}, so a
 * client knows the refusal is temporary and sends the same request under the same {@code Idempotency-Key} again: either the command
 * committed and the key replays it, or nothing was kept and it is a first attempt. A {@code 500} said none of that.
 *
 * <h2>What is the database lost</h2>
 *
 * <ul>
 *   <li>a connection the driver could not make, or lost: SQLState class {@code 08} (connection exception), and
 *       {@link SQLNonTransientConnectionException};
 *   <li>the server going away under the connection or refusing it while it starts or recovers - a failover's or a shutdown's
 *       {@code 57P01} (admin shutdown), {@code 57P02} (crash shutdown) and {@code 57P03} (cannot connect now);
 *   <li>the pool's wait ended because no connection could be made: Hikari's {@link SQLTransientConnectionException} carrying, as its
 *       cause, the last attempt's failure, itself one of the above.
 * </ul>
 *
 * <h2>What is not</h2>
 *
 * <ul>
 *   <li><strong>An exhausted pool while the database is up</strong>: the same pool timeout with no cause - every connection was
 *       made and is busy. That is an overload; shedding answers it {@code api.Overloaded} before the handler (ADR-0137), and one that
 *       slips past stays the failure it is;
 *   <li>a statement's own failure on a live connection - a statement timeout ({@code 57014}), a constraint, a serialisation refusal
 *       - each keeping its own answer.
 * </ul>
 *
 * <p><strong>Read down the whole cause chain, and from the message too</strong>, as {@code InboxFailures} does: a store wraps the
 * driver's exception and, because a constraint violation's message carries the row, keeps only its SQLState, in text
 * ({@code DatabaseFailure#describe}'s {@code (SQLState 08006)}, a module store's {@code : SQLSTATE 08006}).
 */
public final class DatabaseUnreachable {

    private static final Set<String> SHUTDOWN_STATES = Set.of("57P01", "57P02", "57P03");

    /** Either spelling a store writes; five characters, so {@code SQLState unknown} does not match. */
    private static final Pattern SQLSTATE_IN_MESSAGE = Pattern.compile("\\bSQL ?STATE ([0-9A-Z]{5})\\b", Pattern.CASE_INSENSITIVE);

    private DatabaseUnreachable() {}

    /** {@code true} when the failure is the database lost or unreachable, and nothing a statement did. */
    public static boolean is(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLTransientConnectionException) {
                // The pool's timeout says nothing by itself: lost only if what it waited on could not be made.
                return current.getCause() != null && is(current.getCause());
            }
            if (lostItself(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean lostItself(Throwable failure) {
        if (failure instanceof SQLNonTransientConnectionException) {
            return true;
        }
        if (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() == 5) {
            return lostState(sql.getSQLState());
        }
        String message = failure.getMessage();
        if (message != null) {
            Matcher named = SQLSTATE_IN_MESSAGE.matcher(message);
            while (named.find()) {
                if (lostState(named.group(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean lostState(String sqlState) {
        String state = sqlState.toUpperCase(Locale.ROOT);
        return state.startsWith("08") || SHUTDOWN_STATES.contains(state);
    }
}
