package com.finapp.app.accounting;

import java.sql.Connection;
import java.util.function.Function;

/**
 * Where report code reads (`P14-TSK-008`; {@code INV-ACC-14}, owner decision C10; {@code PHASE_14_PLAN.md} section 8.1
 * T-report's generate leg): <strong>a separate connection</strong> - never the caller's unit of work, which may hold a
 * claim, an idempotency record or a lock - in <strong>one {@code REPEATABLE READ READ ONLY} transaction</strong>, always
 * rolled back. A write inside it is refused by the database ({@code 25006}); the snapshot sees one instant of the books,
 * so every statement a definition makes agrees with every other. A port, so a hermetic suite can stand in for the
 * database; production's is {@link JdbcReportSnapshots}.
 *
 * <p>A new class rather than a method on {@link AccountingTransactions}: that port is every accounting door's writable
 * unit of work, and the snapshot must not be reachable as one.
 */
public interface ReportSnapshots {

    /** {@code work} on its own connection, in one read-only {@code REPEATABLE READ} transaction, always rolled back. */
    <R> R read(Function<Connection, R> work);
}
