package com.finapp.app.merchant;

import com.finapp.merchant.MerchantTransactionRunner;
import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MerchantTransactionRunner} over Spring's template (`P6-TSK-011`) — the
 * {@code CheckoutTransactions} shape. The connection's lifetime is the call: bound inside,
 * released before return, which is what makes "one transaction per effected row" a property of
 * {@code PayoutDestinationEffectuation}'s straight-line code rather than a promise about how
 * somebody calls it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class MerchantTransactions implements MerchantTransactionRunner {

    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    @Override
    public <R> R inTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
