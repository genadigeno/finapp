package com.finapp.app.risk;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * risk's unit of work over Spring (`P13-TSK-002`) - bnpl's {@code BnplTransactions} shape, composed here because the
 * risk module carries no framework: one transaction, default propagation, one connection for the work. T-pol runs in
 * it; T-eval (`-004`) never does - an evaluation runs on the caller's connection, in the caller's transaction.
 */
public final class RiskTransactions {

    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final DataSource dataSource;

    public RiskTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.snapshots = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager(),
                "the template's transaction manager"));
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshots.setReadOnly(true);
    }

    /** {@code work} in one read-only {@code REPEATABLE READ} snapshot: a read spanning several statements sees one instant. */
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
