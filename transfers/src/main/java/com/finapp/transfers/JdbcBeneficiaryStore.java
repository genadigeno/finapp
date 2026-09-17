package com.finapp.transfers;

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
 * {@link BeneficiaryStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcCustomerAccountStore} shape: the savepoint converge, SQLState rather than message
 * matching, and refusals described through {@link DatabaseFailure#describe} so no
 * {@code SQLException} — whose constraint-violation {@code DETAIL} carries the whole refused
 * row, display name included — ever reaches a log.
 */
public final class JdbcBeneficiaryStore implements BeneficiaryStore<Connection> {

    private static final String TABLE = "transfers.beneficiary";

    private static final String COLUMNS =
            "id, party_id, display_name, destination_account_id, status, created_at, removed_at";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public Creation createOrConverge(Connection unitOfWork, Beneficiary fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try {
            // A savepoint, because the unique violation ABORTS the transaction (P1-TSK-006):
            // without it the caller's other writes could not follow a lost race, and the retry
            // would re-run the command rather than converge. A pre-flight SELECT is not a
            // substitute - two instances would both see the slot free and one would get 23505
            // anyway.
            Savepoint beforeInsert = unitOfWork.setSavepoint("beneficiary_create");
            try {
                insert(unitOfWork, fresh);
                unitOfWork.releaseSavepoint(beforeInsert);
                return new Creation(fresh, true);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner that just refused our insert.
                return findLive(unitOfWork, fresh.partyId(), fresh.destinationAccountId())
                        .map(existing -> new Creation(existing, false))
                        .orElseThrow(
                                () ->
                                        new TransfersStorageException(
                                                "the one-live-beneficiary index refused an insert"
                                                        + " but no live row is visible for the"
                                                        + " (party, destination) pair - a"
                                                        + " concurrent creator may have rolled"
                                                        + " back; retry"));
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe(
                            "saving a beneficiary for party " + fresh.partyId(), failure));
        }
    }

    private static void insert(Connection unitOfWork, Beneficiary beneficiary)
            throws SQLException {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, beneficiary.id().value());
            insert.setObject(2, beneficiary.partyId());
            insert.setString(3, beneficiary.displayName());
            insert.setObject(4, beneficiary.destinationAccountId());
            insert.setString(5, beneficiary.status().name());
            insert.setTimestamp(6, Timestamp.from(beneficiary.createdAt()));
            insert.setTimestamp(
                    7, beneficiary.removedAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<Beneficiary> findLive(
            Connection unitOfWork, UUID partyId, UUID destinationAccountId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(destinationAccountId, "destinationAccountId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                // The partial index's own predicate, generated from the same
                                // definition (BeneficiaryStatus.sqlTerminalValueList), so
                                // "live" here and "guarded there" cannot disagree.
                                + " WHERE party_id = ? AND destination_account_id = ?"
                                + " AND status NOT IN ("
                                + BeneficiaryStatus.sqlTerminalValueList()
                                + ")")) {
            read.setObject(1, partyId);
            read.setObject(2, destinationAccountId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe(
                            "reading the live beneficiary of party " + partyId, failure));
        }
    }

    @Override
    public Optional<Beneficiary> findOwned(
            Connection unitOfWork, BeneficiaryId beneficiary, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031).
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE id = ? AND party_id = ?")) {
            read.setObject(1, beneficiary.value());
            read.setObject(2, partyId);
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrate(row));
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe("reading an owned beneficiary", failure));
        }
    }

    @Override
    public java.util.List<Beneficiary> listLiveFor(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE party_id = ? AND status NOT IN ("
                                + BeneficiaryStatus.sqlTerminalValueList()
                                + ") ORDER BY created_at, id")) {
            read.setObject(1, partyId);
            try (ResultSet rows = read.executeQuery()) {
                java.util.List<Beneficiary> live = new java.util.ArrayList<>();
                while (rows.next()) {
                    live.add(rehydrate(rows));
                }
                return java.util.List.copyOf(live);
            }
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe(
                            "listing the beneficiaries of party " + partyId, failure));
        }
    }

    @Override
    public boolean remove(
            Connection unitOfWork, BeneficiaryId beneficiary, UUID partyId, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement move =
                unitOfWork.prepareStatement(
                        // party_id = ? IS the ownership check, in the statement (ADR-0031), and
                        // the status predicate is the concurrency arbiter: one of N concurrent
                        // removals matches the live row, the rest converge on zero rows. The
                        // machine's one edge is carried by the pair of literals; V003's trigger
                        // holds the same edge for writers that never ran this code.
                        "UPDATE " + TABLE + " SET status = ?, removed_at = ?"
                                + " WHERE id = ? AND party_id = ? AND status = ?")) {
            move.setString(1, BeneficiaryStatus.REMOVED.name());
            move.setTimestamp(2, Timestamp.from(at));
            move.setObject(3, beneficiary.value());
            move.setObject(4, partyId);
            move.setString(5, BeneficiaryStatus.ACTIVE.name());
            return move.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new TransfersStorageException(
                    DatabaseFailure.describe("removing a beneficiary", failure));
        }
    }

    private static Beneficiary rehydrate(ResultSet row) throws SQLException {
        Timestamp removedAt = row.getTimestamp("removed_at");
        return Beneficiary.rehydrate(
                BeneficiaryId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                row.getString("display_name"),
                row.getObject("destination_account_id", UUID.class),
                BeneficiaryStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                removedAt == null ? null : removedAt.toInstant());
    }
}
