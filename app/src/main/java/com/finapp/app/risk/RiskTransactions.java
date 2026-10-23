package com.finapp.app.risk;

import java.sql.Connection;
import java.util.function.Function;

/**
 * risk's unit of work (`P13-TSK-002` the shape, bnpl's {@code BnplTransactions}; `P13-TSK-019` made it a port so a
 * hermetic suite can run a sweep or a desk over fakes without naming a data source): one transaction, default
 * propagation, one connection for the work; and one read-only {@code REPEATABLE READ} snapshot for a read that spans
 * several statements. The composition root's implementation is {@link SpringRiskTransactions}, the one bean
 * {@code RiskBeans.riskTransactions}.
 */
public interface RiskTransactions {

    /** {@code work} in one transaction on one connection; a thrown refusal rolls it back whole. */
    <R> R inTransaction(Function<Connection, R> work);

    /**
     * {@code work} in one read-only {@code REPEATABLE READ} snapshot (lending's {@code inSnapshot}): a read that spans
     * several statements sees one instant, every row wholly in or wholly out. It decides nothing.
     */
    <R> R inSnapshot(Function<Connection, R> work);
}
