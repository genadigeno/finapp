package com.finapp.paymentmethods;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PaymentMethodStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcBeneficiaryStore} shape: the savepoint converge, SQLState rather than message
 * matching, and refusals described through {@link DatabaseFailure#describe} so no
 * {@code SQLException} — whose constraint-violation {@code DETAIL} carries the whole refused
 * row, <strong>token reference included</strong> — ever reaches a log.
 *
 * <p>This class is the token's one production unwrap site besides its own type: writing the
 * column and binding the converge read's parameter are where the bare value must exist, and
 * both are named in {@code SecretsAreUnwrappedInOnePlaceTest} with this claim.
 */
public final class JdbcPaymentMethodStore implements PaymentMethodStore<Connection> {

    private static final String TABLE = "paymentmethods.payment_method";

    private static final String COLUMNS =
            "id, party_id, token_reference, brand, display_suffix, expiry_month, expiry_year,"
                    + " status, created_at, detached_at";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Attachment attachOrConverge(Connection unitOfWork, PaymentMethod fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes could not follow a lost race, and the retry
            // would re-run the command rather than converge. A pre-flight SELECT is not a
            // substitute - two instances would both see the slot free and one would get 23505
            // anyway.
            Savepoint beforeInsert = unitOfWork.setSavepoint("payment_method_attach");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Attachment(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return findLive(unitOfWork, fresh.partyId(), fresh.token())
                        .map(existing -> new Attachment(existing, false))
                        .orElseThrow(
                                () ->
                                        new PaymentMethodsStorageException(
                                                "the one-live-payment-method index refused an"
                                                        + " insert but no live row is visible"
                                                        + " for the (party, token) pair - a"
                                                        + " concurrent attacher may have rolled"
                                                        + " back; retry"));
            }
        } catch (SQLException failure) {
            throw new PaymentMethodsStorageException(
                    DatabaseFailure.describe(
                            "attaching a payment method for party " + fresh.partyId(), failure));
        }
    }

    private static void insert(Connection unitOfWork, PaymentMethod method) throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, method.id().value());
            insert.setObject(2, method.partyId());
            // The one production unwrap besides the type's own: the column is what the token
            // reference exists to reach.
            insert.setString(3, method.token().expose());
            insert.setString(4, method.brand());
            insert.setString(5, method.displaySuffix());
            insert.setInt(6, method.expiryMonth());
            insert.setInt(7, method.expiryYear());
            insert.setString(8, method.status().name());
            insert.setTimestamp(9, Timestamp.from(method.createdAt()));
            insert.setTimestamp(10, method.detachedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<PaymentMethod> findLive(
            Connection unitOfWork, UUID partyId, TokenReference token) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                // The partial index's own predicate, generated from the same
                                // definition (PaymentMethodStatus.sqlTerminalValueList), so
                                // "live" here and "guarded there" cannot disagree.
                                + " WHERE party_id = ? AND token_reference = ?"
                                + " AND status NOT IN ("
                                + PaymentMethodStatus.sqlTerminalValueList()
                                + ")")) {
            read.setObject(1, partyId);
            read.setString(2, token.expose());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new PaymentMethodsStorageException(
                    DatabaseFailure.describe(
                            "reading the live payment method of party " + partyId, failure));
        }
    }

    @Override
    public boolean detach(
            Connection unitOfWork, PaymentMethodId method, UUID partyId, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement move =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031), and
                        // the status predicate is the concurrency arbiter: one of N concurrent
                        // detaches matches the live row, the rest converge on zero rows. The
                        // machine's one edge is carried by the pair of literals; V002's trigger
                        // holds the same edge for writers that never ran this code.
                        "UPDATE " + TABLE + " SET status = ?, detached_at = ?"
                                + " WHERE id = ? AND party_id = ? AND status = ?")) {
            move.setString(1, PaymentMethodStatus.DETACHED.name());
            move.setTimestamp(2, Timestamp.from(at));
            move.setObject(3, method.value());
            move.setObject(4, partyId);
            move.setString(5, PaymentMethodStatus.ACTIVE.name());
            return move.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentMethodsStorageException(
                    DatabaseFailure.describe("detaching a payment method", failure));
        }
    }

    private static PaymentMethod rehydrate(ResultSet row) throws SQLException {
        Timestamp detachedAt = row.getTimestamp("detached_at");
        return PaymentMethod.rehydrate(
                PaymentMethodId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                TokenReference.of(row.getString("token_reference")),
                row.getString("brand"),
                row.getString("display_suffix"),
                row.getInt("expiry_month"),
                row.getInt("expiry_year"),
                PaymentMethodStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                detachedAt == null ? null : detachedAt.toInstant());
    }
}
