package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** {@link PayoutThresholds} over {@code merchant.payout_four_eyes_threshold} (`P17-TSK-031`, V020): the latest version wins. */
public final class JdbcPayoutThresholds implements PayoutThresholds {

    @Override
    public Optional<Threshold> requiresApproval(Connection unitOfWork, Money cumulative) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(cumulative, "cumulative must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT version, threshold_minor, scale"
                + " FROM merchant.payout_four_eyes_threshold WHERE currency = ? ORDER BY version DESC LIMIT 1")) {
            read.setString(1, cumulative.currency().code());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    // Fail closed: a currency nobody gave a threshold is one a second person decides.
                    return Optional.of(new Threshold(0, Money.ofMinorUnits(0, cumulative.currency())));
                }
                Money threshold = Money.ofMinorUnits(row.getLong("threshold_minor"), cumulative.currency());
                return cumulative.compareTo(threshold) > 0
                        ? Optional.of(new Threshold(row.getInt("version"), threshold))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("reading the payout threshold", failure));
        }
    }
}
