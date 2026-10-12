package com.finapp.app.lending;

import com.finapp.lending.LendingUnitOfWork;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lending's unit of work over Spring (`P11-TSK-004`) - the {@code creditTransactionRunner} shape, composed here because
 * the lending module carries no framework: one transaction, default propagation, one connection for the work. Lending's
 * {@link LendingUnitOfWork} - the seam the payout's multi-transaction step runs through (`P11-TSK-015`).
 */
public final class LendingTransactions implements LendingUnitOfWork {

    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshots;
    private final DataSource dataSource;

    public LendingTransactions(TransactionTemplate transactions, DataSource dataSource) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.snapshots = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager(),
                "the template's transaction manager"));
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshots.setReadOnly(true);
    }

    /**
     * {@code work} in one read-only {@code REPEATABLE READ} snapshot (`P11-TSK-012`): a read that spans several
     * statements - a loan's six balances - sees one instant, every entry wholly in or wholly out. It decides nothing.
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
