package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.ValuationRates;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * accounting's {@link ValuationRates} over fx's <strong>reference</strong> snapshots (`P14-TSK-014`; ADR-0116 decision
 * 2; ADR-0075 decision 1; {@code INV-ACC-11}, {@code INV-FX-05}) - in {@code app}, the one module that sees accounting, fx
 * and the ledger (the {@code LedgerPurposeChart} precedent: an adapter SELECTing another module's table under the
 * application role's existing grant, {@code fx V002}'s {@code SELECT} on {@code fx.rate_snapshot}).
 *
 * <p><strong>Only {@code fx.rate_snapshot}, only the declared reference source.</strong> ADR-0075's rate chain is
 * reference -&gt; provider -&gt; internal -&gt; customer -&gt; executed -&gt; cover; every link after the first is
 * execution evidence a customer or a provider was bound by ({@code INV-FX-05}) and is never read here - not
 * {@code fx.quote}, {@code fx.trade}, {@code fx.cover} nor the provider's evidence. The reference is the one link that is
 * independent and never executable, so it is the one a valuation may freeze.
 *
 * <p><strong>The valued currencies</strong> are the ledger's postable ones ({@link SupportedCurrencies#ALL}) other than
 * {@link ValuationRates#FUNCTIONAL}, each read as {@code EUR/<currency>} - which the reference declares in that canonical
 * direction for every one of them ({@link ReferenceSourceDeclaration#declares}), so nothing is ever inverted; a currency
 * the reference does not publish against EUR refuses construction, which refuses startup.
 *
 * <p>One statement per currency, on the caller's unit of work, locking nothing: the observation with the highest
 * {@code observed_at} strictly before the instant, served by {@code fx V002}'s {@code rate_snapshot_latest} index. The
 * rate is read as the column's {@code BigDecimal}, untouched - never stripped, rounded or passed through a floating-point
 * value ({@code INV-MON-01}).
 */
public final class FxReferenceValuationRates implements ValuationRates {

    /** The last observation of one reference pair strictly before an instant - fx's reference rows and nothing else. */
    static final String LAST_BEFORE = "SELECT id, source, base_currency, quote_currency, rate, observed_at, received_at"
            + " FROM fx.rate_snapshot WHERE source = ? AND base_currency = ? AND quote_currency = ? AND observed_at < ?"
            + " ORDER BY observed_at DESC LIMIT 1";

    private final String source;
    private final List<CurrencyCode> valued;

    /**
     * Over {@code source}'s snapshots, valuing every currency of {@code posted} but EUR.
     *
     * @throws IllegalStateException when a valued currency's EUR pair is not a declared canonical reference pair
     */
    FxReferenceValuationRates(String source, List<CurrencyCode> posted) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        List<CurrencyCode> currencies = new ArrayList<>();
        for (CurrencyCode currency : Objects.requireNonNull(posted, "posted must not be null")) {
            if (FUNCTIONAL.equals(currency) || currencies.contains(currency)) {
                continue;
            }
            if (!ReferenceSourceDeclaration.declares(pair(currency))) {
                throw new IllegalStateException("the reference does not publish " + pair(currency) + " in its canonical"
                        + " direction; a valuation never inverts a rate");
            }
            currencies.add(currency);
        }
        if (currencies.isEmpty()) {
            throw new IllegalStateException("a valuation values at least one currency besides EUR");
        }
        this.valued = List.copyOf(currencies);
    }

    /** The platform's reference ({@link ReferenceSourceDeclaration#SOURCE}) over the ledger's postable currencies. */
    public static FxReferenceValuationRates reference() {
        return new FxReferenceValuationRates(ReferenceSourceDeclaration.SOURCE, SupportedCurrencies.ALL);
    }

    @Override
    public List<CurrencyCode> valued() {
        return valued;
    }

    @Override
    public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(before, "before must not be null");
        List<Observed> observed = new ArrayList<>();
        try (PreparedStatement read = unitOfWork.prepareStatement(LAST_BEFORE)) {
            for (CurrencyCode currency : valued) {
                read.setString(1, source);
                read.setString(2, FUNCTIONAL.code());
                read.setString(3, currency.code());
                read.setTimestamp(4, Timestamp.from(before));
                try (ResultSet row = read.executeQuery()) {
                    observed.add(new Observed(currency, row.next() ? Optional.of(observation(row)) : Optional.empty()));
                }
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the reference rates", failure));
        }
        return observed;
    }

    private static Observation observation(ResultSet row) throws SQLException {
        return new Observation(
                row.getObject("id", UUID.class),
                row.getString("source"),
                CurrencyCode.of(row.getString("base_currency")),
                CurrencyCode.of(row.getString("quote_currency")),
                row.getBigDecimal("rate"),
                row.getTimestamp("observed_at").toInstant(),
                row.getTimestamp("received_at").toInstant());
    }

    private static ReferencePair pair(CurrencyCode currency) {
        return new ReferencePair(FUNCTIONAL, currency);
    }
}
