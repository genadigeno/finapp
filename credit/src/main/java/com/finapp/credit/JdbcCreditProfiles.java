package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link CreditProfiles} over {@code credit.credit_profile} (`P10-TSK-004`, credit {@code V003}).
 *
 * <p>Stateless: the one field mints ids. The arbiter of "one profile per party" is the database's
 * {@code UNIQUE (party_id)}, never this class, and the lock is the row's.
 */
@RequiredArgsConstructor
public final class JdbcCreditProfiles implements CreditProfiles<Connection> {

    private static final String COLUMNS = "id, party_id, created_at";

    @NonNull private final IdGenerator ids;

    @Override
    public CreditProfile ensure(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        try {
            // The database stamps created_at; the value bound here is overwritten by the trigger.
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_profile (" + COLUMNS + ") VALUES (?, ?, statement_timestamp())"
                            + " ON CONFLICT (party_id) DO NOTHING RETURNING " + COLUMNS)) {
                insert.setObject(1, CreditProfileId.next(ids).value());
                insert.setObject(2, partyId);
                try (ResultSet born = insert.executeQuery()) {
                    if (born.next()) {
                        return read(born);
                    }
                }
            }
            // The conflict waited for the winner's commit; this new statement sees its row.
            return find(unitOfWork, partyId, "").orElseThrow(() -> new CreditStorageException(
                    "ON CONFLICT DO NOTHING found a conflicting profile but none is visible - the caller is not"
                            + " READ COMMITTED"));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("ensuring a credit profile", failure));
        }
    }

    @Override
    public Optional<CreditProfile> lockForDecision(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(partyId, "partyId");
        try {
            return find(unitOfWork, partyId, " FOR UPDATE");
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a credit profile", failure));
        }
    }

    private static Optional<CreditProfile> find(Connection unitOfWork, UUID partyId, String lock)
            throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM credit.credit_profile WHERE party_id = ?" + lock)) {
            select.setObject(1, partyId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        }
    }

    private static CreditProfile read(ResultSet row) throws SQLException {
        Timestamp createdAt = row.getTimestamp("created_at");
        return new CreditProfile(
                CreditProfileId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                createdAt.toInstant());
    }
}
