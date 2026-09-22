package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The merchant's view of itself (`P6-TSK-002`), read with the tenant the credential
 * established — never one a caller supplied.
 */
public class MerchantSelfView {

    /**
     * What a merchant sees of itself: its own identifiers and standing. No party reference —
     * the legal party behind a merchant is the platform's and the operator's concern, and
     * publishing it here would export an identifier from another bounded context to a
     * counterparty with no use for it.
     */
    public record SelfView(
            String merchantId, String displayName, String settlementCurrency, String status) {}

    private final MerchantStore<Connection> merchants;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public MerchantSelfView(
            MerchantStore<Connection> merchants,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.merchants = Objects.requireNonNull(merchants, "merchants must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** The authenticated merchant's record. The tenant IS the predicate. */
    public SelfView of(AuthenticatedMerchant tenant) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Merchant merchant =
                inOneTransaction(
                        unitOfWork -> merchants.findById(unitOfWork, tenant.merchantId()))
                        .orElseThrow(
                                () ->
                                        // Unreachable in practice: the key's own lookup joined
                                        // this row and required it ACTIVE. Loud rather than a
                                        // default, because a default would be a guess about
                                        // somebody's commercial standing.
                                        new ApiException(
                                                PlatformErrorCode.NOT_FOUND,
                                                "The authenticated merchant no longer exists"));
        return new SelfView(
                merchant.id().value().toString(),
                merchant.displayName(),
                merchant.settlementCurrency().code(),
                merchant.status().name());
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
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
