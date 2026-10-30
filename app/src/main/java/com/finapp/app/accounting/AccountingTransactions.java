package com.finapp.app.accounting;

import java.sql.Connection;
import java.util.function.Function;

/**
 * accounting's unit of work (`P14-TSK-002`; risk's {@code RiskTransactions}, `P13-TSK-002`): one transaction, default
 * propagation, one connection for the work - a port so a hermetic suite can run a desk over a fake without naming a data
 * source. The composition root's implementation is {@link SpringAccountingTransactions}, the one bean
 * {@code AccountingBeans.accountingTransactions}. The read-only {@code REPEATABLE READ} snapshot the GL and report reads
 * need ({@code INV-ACC-14}, owner decision C10) arrives with the first of them (`P14-TSK-007`, `-008`).
 */
public interface AccountingTransactions {

    /** {@code work} in one transaction on one connection; a thrown refusal rolls it back whole. */
    <R> R inTransaction(Function<Connection, R> work);
}
