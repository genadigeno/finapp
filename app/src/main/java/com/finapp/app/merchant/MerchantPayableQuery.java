package com.finapp.app.merchant;

import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MerchantPayable} inside one transaction (`P6-TSK-010`) - the boundary the controller
 * needs and the {@code merchant} module deliberately does not own, because the unit of work is
 * the caller's (ADR-0033). Built in {@code MerchantBeans} beside {@code MerchantTransactionReport}
 * for the same reason: a named type with its own template, rather than a controller reaching for
 * whichever {@code TransactionTemplate} bean the context happens to hold.
 */
public final class MerchantPayableQuery {

    private final MerchantPayable payables;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public MerchantPayableQuery(
            MerchantPayable payables, TransactionTemplate transactions, DataSource dataSource) {
        this.payables = Objects.requireNonNull(payables, "payables must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** Every payable the merchant has, read in one transaction. */
    public List<MerchantPayable.Payable> payablesOf(MerchantId merchant) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return payables.payablesOf(unitOfWork, merchant);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
