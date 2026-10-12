package com.finapp.lending;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One lending transaction on one connection (`P11-TSK-015`) - the seam a step spanning several transactions with a
 * provider call between them (the payout's T-pyo) is driven through, because the lending module carries no framework:
 * {@code app}'s {@code LendingTransactions} is the production one. A thrown refusal rolls the work back whole.
 */
public interface LendingUnitOfWork {

    <R> R inTransaction(Function<Connection, R> work);
}
