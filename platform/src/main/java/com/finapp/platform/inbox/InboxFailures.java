package com.finapp.platform.inbox;

import com.finapp.platform.inbox.kafka.BrokerAccessRefusedException;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a failed delivery says anything about the record (`P15-TSK-028`).
 *
 * <p><strong>The first risk of parking is parking a good record.</strong> A database that blinked, a pool that ran dry or a replica
 * too old to read a version says nothing about the record: the same record succeeds a moment later or on another build. Such a
 * failure must never count towards the bound, so the classification looks down the whole cause chain, because a store wraps the
 * {@link SQLException} that says what really happened.
 *
 * <p>Everything not named here counts as the record's own - a constraint it broke, a payload that will not parse, a handler defect
 * that it reliably triggers - and the budget in {@link ParkingPolicy} still applies. A failure class this list has not met is
 * therefore bounded twice: by a count and by time on the database's clock.
 *
 * <p>The list is deliberately of the kind of failure, and of the SQLState <em>class</em> where PostgreSQL publishes one:
 * <ul>
 *   <li>{@code 08} connection exception; {@code 40} transaction rollback (a serialization failure, a deadlock);
 *   <li>{@code 53} insufficient resources; {@code 55} object not in prerequisite state ({@code 55P03} is a lock not available);
 *   <li>{@code 57} operator intervention (a shutdown, a statement cancelled); {@code 58} system error; {@code XX} internal error.
 * </ul>
 *
 * <p>Not {@code 25P02}, "current transaction is aborted": that is a statement after a failure the handler swallowed, whose cause is
 * lost, and it repeats for ever if the handler always does it, so it stays bounded rather than stalling the partition without end.
 *
 * <p><strong>The SQLState is read from the message too, because the stores keep it nowhere else</strong> (the fix round's F1). A
 * constraint violation's message carries the row, so a store deliberately drops the driver's exception and keeps only its
 * SQLState in text: {@code DatabaseFailure#describe}'s {@code (SQLState 08006)}, and the module stores' {@code : SQLSTATE 08006}.
 * Walking the cause chain alone saw nothing of a store's failure, so a brownout counted against every valid record it touched.
 */
public final class InboxFailures {

    private static final Set<String> TRANSIENT_SQLSTATE_CLASSES = Set.of("08", "40", "53", "55", "57", "58", "XX");

    /** Either spelling a store writes; five characters, so {@code SQLState unknown} and {@code SQLSTATE null} do not match. */
    private static final Pattern SQLSTATE_IN_MESSAGE = Pattern.compile("\\bSQL ?STATE ([0-9A-Z]{5})\\b", Pattern.CASE_INSENSITIVE);

    private InboxFailures() {}

    /** {@code true} when the failure is the infrastructure's or the build's, and must not count against the record. */
    public static boolean isTransient(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (isTransientItself(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientItself(Throwable failure) {
        if (failure instanceof EventNotYetReadable
                || failure instanceof BrokerAccessRefusedException
                || failure instanceof InterruptedException
                || failure instanceof IOException
                || failure instanceof TimeoutException
                || failure instanceof SQLTransientException
                || failure instanceof SQLRecoverableException
                || failure instanceof SQLNonTransientConnectionException) {
            return true;
        }
        if (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2) {
            return isTransientState(sql.getSQLState());
        }
        String message = failure.getMessage();
        if (message != null) {
            Matcher named = SQLSTATE_IN_MESSAGE.matcher(message);
            return named.find() && isTransientState(named.group(1));
        }
        return false;
    }

    private static boolean isTransientState(String sqlState) {
        return TRANSIENT_SQLSTATE_CLASSES.contains(sqlState.substring(0, 2).toUpperCase(Locale.ROOT));
    }
}
