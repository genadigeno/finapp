package com.finapp.app.accounting;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AccountingTransactions} over Spring's transaction template (`P14-TSK-002`; {@code SpringRiskTransactions}'s
 * shape): the template opens the transaction, the work runs on the connection bound to it, released however it ends.
 */
public final class SpringAccountingTransactions implements AccountingTransactions {

    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public SpringAccountingTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
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
