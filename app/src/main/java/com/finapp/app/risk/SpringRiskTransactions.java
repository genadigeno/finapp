package com.finapp.app.risk;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RiskTransactions} over Spring (`P13-TSK-019`; bnpl's {@code BnplTransactions}, composed here because the risk
 * module carries no framework): one transaction, default propagation, one connection for the work; the snapshot a
 * read-only {@code REPEATABLE READ} template over the same manager.
 */
public final class SpringRiskTransactions implements RiskTransactions {

    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final DataSource dataSource;

    public SpringRiskTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.snapshots = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager(),
                "the template's transaction manager"));
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshots.setReadOnly(true);
    }

    @Override
    public <R> R inTransaction(Function<Connection, R> work) {
        return within(transactions, work);
    }

    @Override
    public <R> R inSnapshot(Function<Connection, R> work) {
        return within(snapshots, work);
    }

    private <R> R within(TransactionTemplate template, Function<Connection, R> work) {
        return template.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
