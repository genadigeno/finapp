package com.finapp.app.checkout;

import com.finapp.checkout.CheckoutTransactionRunner;
import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link CheckoutTransactionRunner} over Spring's template (`P6-TSK-008`) — the
 * {@code paymentTransactionRunner} shape, and the reason it is a class rather than a lambda in
 * the beans file is that {@code CheckoutBeans} constructs it inside another bean method and a
 * named type reads better than a nested anonymous one there.
 *
 * <p>The connection's lifetime is the call: bound inside, released before return. That is what
 * makes "one transaction per swept row" a property of {@code CheckoutExpirySweeper}'s
 * straight-line code rather than a promise about how somebody calls it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CheckoutTransactions implements CheckoutTransactionRunner {

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
