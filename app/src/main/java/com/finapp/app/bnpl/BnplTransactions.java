package com.finapp.app.bnpl;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * bnpl's unit of work over Spring (`P12-TSK-004`) - lending's {@code LendingTransactions} shape, composed here because
 * the bnpl module carries no framework: one transaction, default propagation, one connection for the work.
 */
public final class BnplTransactions {

    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final DataSource dataSource;

    public BnplTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.snapshots = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager(),
                "the template's transaction manager"));
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshots.setReadOnly(true);
    }

    /**
     * {@code work} in one read-only {@code REPEATABLE READ} snapshot (`P12-TSK-013`, lending's {@code inSnapshot}): a read
     * that spans several statements - an agreement's six balances - sees one instant, every entry wholly in or wholly out.
     * It decides nothing.
     */
    public <R> R inSnapshot(Function<Connection, R> work) {
        return snapshots.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
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
