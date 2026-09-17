package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link BalanceDisplay} over explicit SQL (ADR-0033), on the caller's connection.
 *
 * <p>One statement, one snapshot: the owner's ledger accounts (the
 * {@code ledger_account_by_owner} index `P3-TSK-002` created for exactly this read) left-joined
 * to their projection rows, so an account and its balance cannot come from two instants. The
 * amounts compose through {@link Money#ofPersisted} — the stored scale is the stored fact
 * ({@code INV-MON-05}) — and the subtraction runs through {@link Money#minus}, so a cross-scale
 * or overflowing pair refuses in the kernel rather than producing a plausible number
 * ({@code INV-MON-03}, {@code INV-MON-06}).
 */
public final class JdbcBalanceDisplay implements BalanceDisplay<Connection> {

    @Override
    public List<DisplayedBalance> balancesFor(Connection unitOfWork, UUID ownerRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT a.currency AS account_currency,"
                                + " b.posted_minor, b.holds_minor, b.scale"
                                + " FROM ledger.ledger_account a"
                                + " LEFT JOIN ledger.account_balance b ON b.ledger_account_id = a.id"
                                + " WHERE a.owner_ref = ?"
                                + " ORDER BY a.currency, a.id")) {
            read.setObject(1, ownerRef);
            try (ResultSet rows = read.executeQuery()) {
                List<DisplayedBalance> balances = new ArrayList<>();
                while (rows.next()) {
                    CurrencyCode currency =
                            CurrencyCode.of(rows.getString("account_currency").trim());
                    long postedMinor = rows.getLong("posted_minor");
                    boolean noProjectionRow = rows.wasNull();
                    if (noProjectionRow) {
                        // Never posted to: no row is the projection's honest state
                        // (P3-TSK-009 - current or absent along with the fact), and the
                        // display's honest answer is zero in the account's own currency.
                        Money zero = Money.ofMinorUnits(0, currency);
                        balances.add(new DisplayedBalance(zero, zero, zero));
                        continue;
                    }
                    int scale = rows.getInt("scale");
                    Money settled = Money.ofPersisted(postedMinor, currency, scale);
                    Money holds =
                            Money.ofPersisted(rows.getLong("holds_minor"), currency, scale);
                    balances.add(
                            new DisplayedBalance(settled, holds, settled.minus(holds)));
                }
                return List.copyOf(balances);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the displayed balances of owner " + ownerRef, failure));
        }
    }
}
