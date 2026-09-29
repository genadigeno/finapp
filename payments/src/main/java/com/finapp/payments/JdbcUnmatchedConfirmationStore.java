package com.finapp.payments;

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

/** {@link UnmatchedConfirmationStore} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcUnmatchedConfirmationStore
        implements UnmatchedConfirmationStore<Connection> {

    private static final String COLUMNS =
            "id, rail, scheme_reference, amount_minor, currency, scale, received_at,"
                    + " entry_ref, cause, attempt_id, named_reference, settlement_cycle";

    @Override
    public boolean insert(Connection unitOfWork, UnmatchedConfirmation confirmation) {
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.unmatched_confirmation (" + COLUMNS + ")"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                // The fresh-id duplicate's arbiter (V015's shape): the
                                // UNIQUE decides race-free, and the loser converges on the
                                // record that stands.
                                + " ON CONFLICT (rail, scheme_reference) DO NOTHING")) {
            insert.setObject(1, confirmation.id());
            insert.setString(2, confirmation.rail().value());
            insert.setString(3, confirmation.schemeReference().value());
            insert.setLong(4, confirmation.amount().minorUnits());
            insert.setString(5, confirmation.amount().currency().code());
            insert.setShort(6, (short) confirmation.amount().scale());
            insert.setTimestamp(7, Timestamp.from(confirmation.receivedAt()));
            insert.setObject(8, confirmation.entryRef());
            UnmatchedConfirmation.Attribution attribution = confirmation.attribution();
            insert.setString(9, attribution.cause().name());
            insert.setObject(10, attribution.attempt().map(PaymentAttemptId::value).orElse(null));
            insert.setString(11, attribution.namedReference().map(EndToEndReference::value).orElse(null));
            insert.setString(12, attribution.settlementCycle().orElse(null));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "parking unmatched confirmation " + confirmation.id(), failure));
        }
    }

    @Override
    public Optional<UnmatchedConfirmation> findByReference(
            Connection unitOfWork, RailId rail, ProviderReference schemeReference) {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.unmatched_confirmation"
                                + " WHERE rail = ? AND scheme_reference = ?")) {
            read.setString(1, rail.value());
            read.setString(2, schemeReference.value());
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading an unmatched confirmation by reference", failure));
        }
    }

    @Override
    public PaymentAttemptStore.UnknownReading parkedReading(Connection unitOfWork) {
        // INV-REC-05's operational face: count and oldest age, the server's clock, floored
        // at zero - the attempt gauges' exact discipline.
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT count(*),"
                                + " GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM now()"
                                + "   - min(received_at)))::bigint, 0))"
                                + " FROM payments.unmatched_confirmation")) {
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new PaymentAttemptStore.UnknownReading(
                        row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the suspense gauge", failure));
        }
    }

    @Override
    public java.util.List<UnmatchedConfirmation> page(
            Connection unitOfWork, java.util.UUID after, int limit) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM payments.unmatched_confirmation"
                                + " WHERE id > ? ORDER BY id LIMIT ?")) {
            select.setObject(1, after);
            select.setInt(2, limit);
            try (ResultSet rows = select.executeQuery()) {
                java.util.List<UnmatchedConfirmation> page = new java.util.ArrayList<>();
                while (rows.next()) {
                    page.add(rehydrate(rows));
                }
                return java.util.List.copyOf(page);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("paging unmatched confirmations", failure));
        }
    }

    private static UnmatchedConfirmation rehydrate(ResultSet row) throws SQLException {
        return new UnmatchedConfirmation(
                row.getObject("id", UUID.class),
                RailId.of(row.getString("rail")),
                new ProviderReference(row.getString("scheme_reference")),
                // The STORED scale, never re-derived (INV-MON-05).
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency")),
                        row.getShort("scale")),
                row.getTimestamp("received_at").toInstant(),
                row.getObject("entry_ref", UUID.class),
                new UnmatchedConfirmation.Attribution(
                        UnmatchedConfirmation.Cause.valueOf(row.getString("cause")),
                        Optional.ofNullable(row.getObject("attempt_id", UUID.class))
                                .map(PaymentAttemptId::of),
                        Optional.ofNullable(row.getString("named_reference"))
                                .map(EndToEndReference::new),
                        Optional.ofNullable(row.getString("settlement_cycle"))));
    }
}
