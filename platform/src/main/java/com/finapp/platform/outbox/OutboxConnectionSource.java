package com.finapp.platform.outbox;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the connections the relay runs its own transactions on.
 *
 * <p><strong>Why the relay opens transactions when the writer must not.</strong>
 * {@link OutboxWriter} is handed a unit of work because it must join the transaction that
 * produced the fact ({@code INV-EVT-01}). The relay is the opposite case: it runs on its own,
 * long after that transaction committed, and there is no caller transaction to join. Taking a
 * connection from outside would mean the relay could not control its own commit boundaries —
 * and those boundaries are what stop two instances from publishing the same aggregate at once.
 *
 * <p>A {@code javax.sql.DataSource} satisfies this as {@code dataSource::getConnection}. It is
 * not required as the parameter type because no connection pool has been chosen and no
 * data-access mechanism has been decided (unresolved question 12); a one-method interface keeps
 * that decision open and keeps a test double to one lambda.
 */
@FunctionalInterface
public interface OutboxConnectionSource {

    /** Opens a connection. The caller closes it. */
    Connection open() throws SQLException;
}
