package com.finapp.platform.inbox;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the connections the consumer shell runs its own transactions on (`P2-TSK-002`).
 *
 * <p>{@code OutboxConnectionSource}'s reasoning, on the consuming side: {@link InboxConsumer} is
 * handed a unit of work because the dedupe record must join the transaction that produced the
 * effect ({@code INV-IDEM-04}), but the <em>shell</em> that drives it has no caller and no
 * caller transaction — a record arrives from the broker, long after the producing transaction
 * committed. The shell must therefore control its own commit boundary, because that boundary is
 * the load-bearing ordering of the whole design: the database commit happens first, and only
 * then is the record acknowledged to the broker. A shell that borrowed transactions from outside
 * could not promise that order.
 *
 * <p>Deliberately a fresh interface rather than a reuse of the outbox's: they are structurally
 * identical and semantically opposite ends of the pipe, and importing a type named
 * {@code Outbox...} to open the inbox's connections is the kind of reading trap a one-line
 * interface is cheap insurance against.
 */
@FunctionalInterface
public interface InboxConnectionSource {

    /** Opens a connection. The caller closes it. */
    Connection open() throws SQLException;
}
