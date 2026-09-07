package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-JDBC MFA enrolment store (`P1-TSK-017`, ADR-0033).
 *
 * <p>Every state change is a conditional {@code UPDATE … WHERE} whose row count is the outcome, so
 * there is no read-then-write anywhere here and nothing for two instances to lose.
 */
public final class JdbcMfaEnrolmentStore implements MfaEnrolmentStore<Connection> {

    private static final String TABLE = "identity.mfa_enrolment";

    private static final String COLUMNS =
            "id, identity_id, type, secret_ciphertext, secret_nonce, key_version, algorithm,"
                    + " digits, period_seconds, status, created_at, confirmed_at, discarded_at,"
                    + " last_used_step";

    @Override
    public void insert(Connection unitOfWork, MfaEnrolment enrolment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(enrolment, "enrolment must not be null");

        String sql =
                "INSERT INTO " + TABLE + " (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, enrolment.id().value());
            insert.setObject(2, enrolment.identityId().value());
            insert.setString(3, enrolment.type().name());
            insert.setBytes(4, enrolment.encryptedSecret().ciphertext());
            insert.setBytes(5, enrolment.encryptedSecret().nonce());
            insert.setInt(6, enrolment.encryptedSecret().keyVersion());
            insert.setString(7, enrolment.parameters().algorithm().name());
            insert.setInt(8, enrolment.parameters().digits());
            insert.setInt(9, enrolment.parameters().periodSeconds());
            insert.setString(10, enrolment.status().name());
            insert.setTimestamp(11, Timestamp.from(enrolment.createdAt()));
            insert.setTimestamp(12, enrolment.confirmedAt().map(Timestamp::from).orElse(null));
            insert.setTimestamp(13, enrolment.discardedAt().map(Timestamp::from).orElse(null));
            if (enrolment.lastUsedStep().isPresent()) {
                insert.setLong(14, enrolment.lastUsedStep().getAsLong());
            } else {
                insert.setNull(14, java.sql.Types.BIGINT);
            }
            insert.executeUpdate();
        } catch (SQLException e) {
            // Never the SQLException: PostgreSQL puts the whole refused row in a constraint
            // violation's DETAIL, and that row holds the ciphertext and the nonce (P1-TSK-008).
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not insert MFA enrolment " + enrolment.id(), e));
        }
    }

    @Override
    public Optional<MfaEnrolment> findPending(
            Connection unitOfWork, IdentityId identityId, MfaFactorType type) {
        return findByStatus(unitOfWork, identityId, type, MfaFactorStatus.PENDING);
    }

    @Override
    public Optional<MfaEnrolment> findActive(
            Connection unitOfWork, IdentityId identityId, MfaFactorType type) {
        return findByStatus(unitOfWork, identityId, type, MfaFactorStatus.ACTIVE);
    }

    private Optional<MfaEnrolment> findByStatus(
            Connection unitOfWork, IdentityId identityId, MfaFactorType type,
            MfaFactorStatus status) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        // The status predicate is in the STATEMENT, and for findActive that is this task's
        // acceptance criterion: a challenge cannot satisfy itself with a factor nobody confirmed,
        // because the query never returns one. A load-then-check would be one refactor away from it.
        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE identity_id = ? AND type = ? AND status = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            select.setString(2, type.name());
            select.setString(3, status.name());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read an MFA enrolment", e));
        }
    }

    @Override
    public boolean confirm(Connection unitOfWork, MfaEnrolmentId id, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // Conditional on PENDING, so ten instances confirming one enrolment produce one transition
        // and nine are told they lost - and a code presented twice finds nothing pending the second
        // time, which is what makes the state machine the replay defence on this path.
        String sql =
                "UPDATE " + TABLE + " SET status = 'ACTIVE', confirmed_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not confirm MFA enrolment " + id, e));
        }
    }

    @Override
    public boolean consumeStep(Connection unitOfWork, MfaEnrolmentId id, long step) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");

        // `last_used_step < ?` is the whole replay defence, and it is in the STATEMENT so two
        // instances presenting one code cannot both pass it. It refuses earlier steps as well as
        // the same one - RFC 6238 5.2 - so a code captured a minute ago is dead once a later one
        // has been used.
        String sql =
                "UPDATE " + TABLE + " SET last_used_step = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'"
                        + " AND (last_used_step IS NULL OR last_used_step < ?)";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setLong(1, step);
            update.setObject(2, id.value());
            update.setLong(3, step);
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not consume a one-time password step", e));
        }
    }

    @Override
    public int discardPending(
            Connection unitOfWork, IdentityId identityId, MfaFactorType type, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // `status = 'PENDING'` is load-bearing and is a security property, not a filter: without it
        // this would discard an ACTIVE factor, so anyone who reached the enrolment endpoint could
        // disable somebody's second factor without proving anything (INV-IDN-05).
        String sql =
                "UPDATE " + TABLE + " SET status = 'DISCARDED', discarded_at = ?"
                        + " WHERE identity_id = ? AND type = ? AND status = 'PENDING'";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, identityId.value());
            update.setString(3, type.name());
            return update.executeUpdate();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe(
                            "Could not discard the pending MFA enrolment of " + identityId, e));
        }
    }

    private static java.util.OptionalLong lastUsedStep(ResultSet rows) throws SQLException {
        long step = rows.getLong("last_used_step");
        return rows.wasNull() ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(step);
    }

    private static MfaEnrolment read(ResultSet rows) throws SQLException {
        Timestamp confirmedAt = rows.getTimestamp("confirmed_at");
        Timestamp discardedAt = rows.getTimestamp("discarded_at");
        return MfaEnrolment.rehydrate(
                MfaEnrolmentId.of(rows.getObject("id", java.util.UUID.class)),
                IdentityId.of(rows.getObject("identity_id", java.util.UUID.class)),
                MfaFactorType.valueOf(rows.getString("type")),
                new SecretCipher.Encrypted(
                        rows.getBytes("secret_ciphertext"),
                        rows.getBytes("secret_nonce"),
                        rows.getInt("key_version")),
                new TotpParameters(
                        TotpAlgorithm.valueOf(rows.getString("algorithm")),
                        rows.getInt("digits"),
                        rows.getInt("period_seconds")),
                MfaFactorStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                discardedAt == null ? null : discardedAt.toInstant(),
                lastUsedStep(rows));
    }
}
