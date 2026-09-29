package com.finapp.settlement;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One transaction, begun and committed around {@code work} (`P8-TSK-008`) — the payments
 * {@code TransactionRunner} shape, owned by this module so the parse leg's per-file
 * containment is production code here rather than a composition each caller re-performs:
 * a file's outcome commits whole, a file's failure rolls back whole, and the failure's own
 * record is a second, smaller transaction that survives the first one's rollback
 * (ADR-0066 §9).
 *
 * <p>The connection's lifetime is the call: open when {@code work} begins, commit when it
 * returns, roll back when it throws, never leaked past the return.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
