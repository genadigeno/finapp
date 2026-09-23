package com.finapp.app.kyc;

import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One transaction, one connection, for the {@code kyc} orchestrations (`P2-TSK-011`).
 *
 * <p>Extracted from {@code VerificationRunService} the moment a third copy was about to exist
 * (the run service, the assessment, the callback service) — the mechanical
 * template-plus-{@link DataSourceUtils} pairing that makes every store write join the
 * transaction the operation opened, so an audit record or a review task written beside a check
 * commits or rolls back with it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class KycUnitOfWork {

    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    <T> T inTransaction(Function<Connection, T> work) {
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
