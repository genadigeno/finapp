package com.finapp.crossborder;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC storage for cross-border offers (ADR-0033, `P9-TSK-018`); {@code crossborder V004} beneath. */
public final class JdbcOfferStore implements OfferStore {

    private static final String REQUEST_COLUMNS =
            "id, claim_key, owner_party, beneficiary_id, corridor_policy_id, corridor, fixed_side, amount_minor,"
                    + " amount_currency, rescreen_id, created_at";

    private static final String OFFER_COLUMNS =
            "id, offer_request_id, quote_id, owner_party, beneficiary_id, corridor_policy_id, corridor, fee_minor, fee_scale,"
                    + " source_minor, total_debit_minor, source_currency, destination_minor, destination_scale,"
                    + " destination_currency, delivery_estimate_hours, created_at";

    @Override
    public Optional<RequestRow> requestByClaim(Connection unitOfWork, String claimKey) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimKey, "claimKey must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + REQUEST_COLUMNS + " FROM crossborder.offer_request WHERE claim_key = ?")) {
            select.setString(1, claimKey);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(request(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading an offer request", failure));
        }
    }

    @Override
    public boolean insertRequest(Connection unitOfWork, RequestRow request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.offer_request (" + REQUEST_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (claim_key) DO NOTHING")) {
            insert.setObject(1, request.id());
            insert.setString(2, request.claimKey());
            insert.setObject(3, request.owner());
            insert.setObject(4, request.beneficiary().value());
            insert.setObject(5, request.corridorPolicy().value());
            insert.setString(6, request.corridor().code());
            insert.setString(7, request.fixedSource() ? "FIXED_SOURCE" : "FIXED_DESTINATION");
            insert.setLong(8, request.amount().minorUnits());
            insert.setString(9, request.amount().currency().code());
            insert.setObject(10, request.rescreen().orElse(null));
            insert.setTimestamp(11, Timestamp.from(request.createdAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording an offer request", failure));
        }
    }

    @Override
    public Optional<OfferRow> offerOfRequest(Connection unitOfWork, UUID requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        return offer(unitOfWork, "SELECT " + OFFER_COLUMNS + " FROM crossborder.payment_offer WHERE offer_request_id = ?",
                requestId, null);
    }

    @Override
    public void insertOffer(Connection unitOfWork, OfferRow offer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.payment_offer (" + OFFER_COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, offer.id());
            insert.setObject(2, offer.requestId());
            insert.setObject(3, offer.quoteId());
            insert.setObject(4, offer.owner());
            insert.setObject(5, offer.beneficiary().value());
            insert.setObject(6, offer.corridorPolicy().value());
            insert.setString(7, offer.corridor().code());
            insert.setLong(8, offer.fee().minorUnits());
            insert.setInt(9, offer.fee().scale());
            insert.setLong(10, offer.source().minorUnits());
            insert.setLong(11, offer.totalDebit().minorUnits());
            insert.setString(12, offer.source().currency().code());
            insert.setLong(13, offer.destination().minorUnits());
            insert.setInt(14, offer.destination().scale());
            insert.setString(15, offer.destination().currency().code());
            insert.setInt(16, offer.deliveryEstimateHours());
            insert.setTimestamp(17, Timestamp.from(offer.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a payment offer", failure));
        }
    }

    @Override
    public Optional<OfferRow> offerOwned(Connection unitOfWork, UUID quoteId, UUID owner) {
        Objects.requireNonNull(quoteId, "quoteId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        return offer(unitOfWork, "SELECT " + OFFER_COLUMNS + " FROM crossborder.payment_offer WHERE quote_id = ? AND owner_party = ?",
                quoteId, owner);
    }

    private static Optional<OfferRow> offer(Connection unitOfWork, String sql, UUID first, UUID second) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, first);
            if (second != null) {
                select.setObject(2, second);
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                int feeScale = row.getInt("fee_scale");
                return Optional.of(new OfferRow(
                        row.getObject("id", UUID.class),
                        row.getObject("offer_request_id", UUID.class),
                        row.getObject("quote_id", UUID.class),
                        row.getObject("owner_party", UUID.class),
                        BeneficiaryId.of(row.getObject("beneficiary_id", UUID.class)),
                        CorridorPolicyId.of(row.getObject("corridor_policy_id", UUID.class)),
                        CorridorKey.parse(row.getString("corridor")),
                        Money.ofPersisted(row.getLong("fee_minor"), source, feeScale),
                        Money.ofPersisted(row.getLong("source_minor"), source, feeScale),
                        Money.ofPersisted(row.getLong("total_debit_minor"), source, feeScale),
                        Money.ofPersisted(row.getLong("destination_minor"), destination, row.getInt("destination_scale")),
                        row.getInt("delivery_estimate_hours"),
                        row.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading a payment offer", failure));
        }
    }

    private static RequestRow request(ResultSet row) throws SQLException {
        CorridorKey corridor = CorridorKey.parse(row.getString("corridor"));
        boolean fixedSource = row.getString("fixed_side").equals("FIXED_SOURCE");
        CurrencyCode currency = CurrencyCode.of(row.getString("amount_currency"));
        return new RequestRow(
                row.getObject("id", UUID.class),
                row.getString("claim_key"),
                row.getObject("owner_party", UUID.class),
                BeneficiaryId.of(row.getObject("beneficiary_id", UUID.class)),
                CorridorPolicyId.of(row.getObject("corridor_policy_id", UUID.class)),
                corridor,
                fixedSource,
                Money.ofMinorUnits(row.getLong("amount_minor"), currency),
                Optional.ofNullable(row.getObject("rescreen_id", UUID.class)),
                row.getTimestamp("created_at").toInstant());
    }
}
