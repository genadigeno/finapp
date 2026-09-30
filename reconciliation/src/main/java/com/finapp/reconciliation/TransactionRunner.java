package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One transaction, begun and committed around {@code work} (`P8-TSK-011`) — the settlement
 * {@code TransactionRunner} shape, owned by this module so the run leg's per-chunk
 * containment is production code here rather than a composition each caller re-performs:
 * a chunk commits whole, a chunk's failure rolls back whole to the last cursor, and the
 * failure's own record is a second, smaller transaction that survives the first one's
 * rollback (ADR-0068 §3).
 *
 * <p>The connection's lifetime is the call: open when {@code work} begins, commit when it
 * returns, roll back when it throws, never leaked past the return.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
