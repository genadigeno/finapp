package com.finapp.app.lending;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lending's unit of work over Spring (`P11-TSK-004`) - the {@code creditTransactionRunner} shape, composed here because
 * the lending module carries no framework: one transaction, default propagation, one connection for the work.
 */
public final class LendingTransactions {

    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public LendingTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** {@code work} in one transaction on one connection; a thrown refusal rolls it back whole. */
    public <R> R inTransaction(Function<Connection, R> work) {
        return transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
