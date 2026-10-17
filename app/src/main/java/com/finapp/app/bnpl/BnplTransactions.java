package com.finapp.app.bnpl;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * bnpl's unit of work over Spring (`P12-TSK-004`) - lending's {@code LendingTransactions} shape, composed here because
 * the bnpl module carries no framework: one transaction, default propagation, one connection for the work.
 */
public final class BnplTransactions {

    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public BnplTransactions(TransactionTemplate transactions, DataSource dataSource) {
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
