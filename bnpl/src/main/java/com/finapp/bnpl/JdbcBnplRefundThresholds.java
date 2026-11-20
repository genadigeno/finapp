package com.finapp.bnpl;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link BnplRefundThresholds} over {@code bnpl.refund_four_eyes_threshold} (`P17-TSK-116`, V023): the latest version
 * wins. A row whose scale is not the amount's - a purchase is persisted at its currency's scale, so this is a row nobody
 * should have written - fails closed, as a currency with no row does.
 */
public final class JdbcBnplRefundThresholds implements BnplRefundThresholds {

    @Override
    public Optional<Threshold> requiresApproval(Connection unitOfWork, Money cumulative) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(cumulative, "cumulative must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT version, threshold_minor, scale"
                + " FROM bnpl.refund_four_eyes_threshold WHERE currency = ? ORDER BY version DESC LIMIT 1")) {
            read.setString(1, cumulative.currency().code());
            try (ResultSet row = read.executeQuery()) {
                Money zero = Money.ofPersisted(0, cumulative.currency(), cumulative.scale());
                if (!row.next()) {
                    // Fail closed: a currency nobody gave a threshold is one a second person decides.
                    return Optional.of(new Threshold(0, zero));
                }
                int version = row.getInt("version");
                if (row.getInt("scale") != cumulative.scale()) {
                    return Optional.of(new Threshold(version, zero));
                }
                Money threshold = Money.ofPersisted(row.getLong("threshold_minor"), cumulative.currency(),
                        cumulative.scale());
                return cumulative.compareTo(threshold) > 0
                        ? Optional.of(new Threshold(version, threshold))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new BnplStorageException(DatabaseFailure.describe("reading the refund threshold", failure));
        }
    }
}
