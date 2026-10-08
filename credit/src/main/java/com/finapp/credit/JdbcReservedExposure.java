package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code P10-TSK-010}'s {@link ReservedExposure} contract over {@code credit_decision} (`P10-TSK-016`;
 * {@code INV-CRD-09}): the sum, exactly, of the party's {@code APPROVED} amounts in the currency still valid on the
 * database's clock ({@code valid_until > statement_timestamp()}) and not consumed. Read under the party's profile lock
 * by the deciding transaction, so two decisions for one party serialise and the second sees the first's reservation.
 * Version 2: version 1 was the zero that stood in before decisions existed.
 */
public final class JdbcReservedExposure implements ReservedExposure<Connection> {

    public static final int VERSION = 2;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Money reservedFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(currency, "currency");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(sum(d.approved_minor), 0) FROM credit.credit_decision d WHERE d.party_id = ?"
                        + " AND d.outcome = 'APPROVED' AND d.currency = ? AND d.valid_until > statement_timestamp()"
                        + " AND NOT EXISTS (SELECT 1 FROM credit.credit_decision_consumption c WHERE c.decision_id = d.id)")) {
            select.setObject(1, partyId);
            select.setString(2, currency.code());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Money.ofMinorUnits(row.getLong(1), currency);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a party's reserved exposure", failure));
        }
    }
}
