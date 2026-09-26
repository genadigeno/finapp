package com.finapp.paymentmethods;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PaymentMethodStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcBeneficiaryStore} shape: the savepoint converge, SQLState rather than message
 * matching, and refusals described through {@link DatabaseFailure#describe} so no
 * {@code SQLException} — whose constraint-violation {@code DETAIL} carries the whole refused
 * row, <strong>token and destination references included</strong> — ever reaches a log.
 *
 * <p>This class is each wrapped reference's one production unwrap site besides its own type:
 * writing the column and binding the converge read's parameter are where the bare value must
 * exist, and all four sites are named in {@code SecretsAreUnwrappedInOnePlaceTest} with this
 * claim.
 *
 * <p><strong>The converge read is chosen by the refused row's kind</strong> (`P7-TSK-007`):
 * a card lost its race on the (party, token) index, a bank account on the
 * (party, destination) index, and each is handed the live row its own slot holds.
 */
public final class JdbcPaymentMethodStore implements PaymentMethodStore<Connection> {

    private static final String TABLE = "paymentmethods.payment_method";

    private static final String COLUMNS =
            "id, party_id, kind, token_reference, brand, display_suffix, expiry_month,"
                    + " expiry_year, destination_reference, payee_check,"
                    + " no_match_acknowledged_at, status, created_at, detached_at";

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
                // committed winner that just refused our insert - on whichever slot the
                // fresh row's kind contends for.
                Optional<PaymentMethod> winner =
                        fresh.kind() == PaymentMethodKind.CARD_TOKEN
                                ? findLive(
                                        unitOfWork, fresh.partyId(), fresh.token().orElseThrow())
                                : findLiveByDestination(
                                        unitOfWork,
                                        fresh.partyId(),
                                        fresh.destination().orElseThrow());
                return winner.map(existing -> new Attachment(existing, false))
                        .orElseThrow(
                                () ->
                                        new PaymentMethodsStorageException(
                                                "the one-live-payment-method index refused an"
                                                        + " insert but no live row is visible"
                                                        + " for the slot - a concurrent"
                                                        + " attacher may have rolled back;"
                                                        + " retry"));
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
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, method.id().value());
            insert.setObject(2, method.partyId());
            insert.setString(3, method.kind().name());
            // The one production unwrap besides each type's own: the column is what the
            // wrapped reference exists to reach. Absent facts are the other kind's columns,
            // NULL by V003's coherence.
            insert.setString(4, method.token().map(TokenReference::expose).orElse(null));
            insert.setString(5, method.brand().orElse(null));
            insert.setString(6, method.displaySuffix());
            setNullableInt(insert, 7, method.expiryMonth());
            setNullableInt(insert, 8, method.expiryYear());
            insert.setString(
                    9, method.destination().map(DestinationReference::expose).orElse(null));
            insert.setString(10, method.payeeCheck().map(PayeeCheck::name).orElse(null));
            insert.setTimestamp(
                    11, method.noMatchAcknowledgedAt().map(Timestamp::from).orElse(null));
            insert.setString(12, method.status().name());
            insert.setTimestamp(13, Timestamp.from(method.createdAt()));
            insert.setTimestamp(14, method.detachedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        }
    }

    private static void setNullableInt(
            PreparedStatement statement, int index, Optional<Integer> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    @Override
    public Optional<PaymentMethod> findLive(
            Connection unitOfWork, UUID partyId, TokenReference token) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        return findLiveBy(unitOfWork, partyId, "token_reference", token.expose());
    }

    @Override
    public Optional<PaymentMethod> findLiveByDestination(
            Connection unitOfWork, UUID partyId, DestinationReference destination) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        return findLiveBy(unitOfWork, partyId, "destination_reference", destination.expose());
    }

    private static Optional<PaymentMethod> findLiveBy(
            Connection unitOfWork, UUID partyId, String slotColumn, String slotValue) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                // The partial indexes' own predicate, generated from the same
                                // definition (PaymentMethodStatus.sqlTerminalValueList), so
                                // "live" here and "guarded there" cannot disagree. The slot
                                // column is one of the two class constants, never caller text.
                                + " WHERE party_id = ? AND " + slotColumn + " = ?"
                                + " AND status NOT IN ("
                                + PaymentMethodStatus.sqlTerminalValueList()
                                + ")")) {
            read.setObject(1, partyId);
            read.setString(2, slotValue);
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
    public Optional<PaymentMethod> findOwned(
            Connection unitOfWork, PaymentMethodId method, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031).
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE id = ? AND party_id = ?")) {
            read.setObject(1, method.value());
            read.setObject(2, partyId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new PaymentMethodsStorageException(
                    DatabaseFailure.describe("reading an owned payment method", failure));
        }
    }

    @Override
    public java.util.List<PaymentMethod> listLiveFor(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE party_id = ? AND status NOT IN ("
                                + PaymentMethodStatus.sqlTerminalValueList()
                                + ") ORDER BY created_at, id")) {
            read.setObject(1, partyId);
            try (ResultSet rows = read.executeQuery()) {
                java.util.List<PaymentMethod> live = new java.util.ArrayList<>();
                while (rows.next()) {
                    live.add(rehydrate(rows));
                }
                return java.util.List.copyOf(live);
            }
        } catch (SQLException failure) {
            throw new PaymentMethodsStorageException(
                    DatabaseFailure.describe(
                            "listing the live payment methods of party " + partyId, failure));
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
        Timestamp acknowledgedAt = row.getTimestamp("no_match_acknowledged_at");
        Timestamp detachedAt = row.getTimestamp("detached_at");
        String token = row.getString("token_reference");
        String destination = row.getString("destination_reference");
        String payeeCheck = row.getString("payee_check");
        Integer expiryMonth = row.getObject("expiry_month", Integer.class);
        Integer expiryYear = row.getObject("expiry_year", Integer.class);
        return PaymentMethod.rehydrate(
                PaymentMethodId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                PaymentMethodKind.valueOf(row.getString("kind")),
                token == null ? null : TokenReference.of(token),
                row.getString("brand"),
                row.getString("display_suffix"),
                expiryMonth,
                expiryYear,
                destination == null ? null : DestinationReference.of(destination),
                payeeCheck == null ? null : PayeeCheck.valueOf(payeeCheck),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                PaymentMethodStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                detachedAt == null ? null : detachedAt.toInstant());
    }
}
