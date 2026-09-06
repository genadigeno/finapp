package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-JDBC credential store.
 *
 * <p>Explicit SQL on the connection it is handed, which ADR-0033 makes the platform-wide decision
 * and which every Phase 0 writer already does. {@code JdbcClient} is built on a {@code DataSource}
 * and would acquire its <em>own</em> connection - a second transaction, with the atomicity gone
 * while every test still passed.
 */
public final class JdbcCredentialStore implements CredentialStore<Connection> {

    private static final String TABLE = "identity.credential";

    /** PostgreSQL {@code unique_violation}. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String COLUMNS =
            "id, identity_id, type, algorithm, memory_kib, iterations, parallelism, derivation,"
                    + " status, created_at, superseded_at";

    @Override
    public void insert(Connection unitOfWork, Credential credential) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(credential, "credential must not be null");

        String sql =
                "INSERT INTO " + TABLE + " (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, credential.id().value());
            insert.setObject(2, credential.identityId().value());
            insert.setString(3, credential.type().name());
            insert.setString(4, credential.algorithm().name());
            insert.setInt(5, credential.parameters().memoryKib());
            insert.setInt(6, credential.parameters().iterations());
            insert.setInt(7, credential.parameters().parallelism());
            insert.setString(8, credential.credentialDerivation().expose());
            insert.setString(9, credential.status().name());
            insert.setTimestamp(10, Timestamp.from(credential.createdAt()));
            insert.setTimestamp(
                    11, credential.supersededAt().map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new ActiveCredentialAlreadyExistsException(e);
            }
            // Neither message names the derivation or anything derived from it (INV-AUD-02). The
            // credential's identifier is safe: it identifies a row and says nothing about a secret.
            throw new CredentialStorageException(
                    "Could not insert credential " + credential.id(), e);
        }
    }

    @Override
    public boolean supersede(Connection unitOfWork, CredentialId credentialId, Instant supersededAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(credentialId, "credentialId must not be null");
        Objects.requireNonNull(supersededAt, "supersededAt must not be null");

        // Conditional UPDATE ... WHERE, never read-then-write. Two instances issuing this against
        // one credential is the normal case (ADR-0014): the second blocks on the row lock, then
        // sees status = 'SUPERSEDED' and affects zero rows. The row count IS the outcome, which is
        // what makes a lost update impossible rather than unlikely.
        String sql =
                "UPDATE " + TABLE + " SET status = ?, superseded_at = ?"
                        + " WHERE id = ? AND status = ?";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setString(1, CredentialStatus.SUPERSEDED.name());
            update.setTimestamp(2, Timestamp.from(supersededAt));
            update.setObject(3, credentialId.value());
            update.setString(4, CredentialStatus.ACTIVE.name());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new CredentialStorageException(
                    "Could not supersede credential " + credentialId, e);
        }
    }

    @Override
    public Optional<Credential> findActive(
            Connection unitOfWork, IdentityId identityId, CredentialType type) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(type, "type must not be null");

        String sql =
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE identity_id = ? AND type = ? AND status = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            select.setString(2, type.name());
            select.setString(3, CredentialStatus.ACTIVE.name());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Credential credential = read(rows);
                if (rows.next()) {
                    // Unreachable while the partial unique index exists. If it ever is reached the
                    // index is broken, and picking the first row would hide that behind an
                    // authentication that quietly used whichever credential the planner returned.
                    throw new CredentialStorageException(
                            "More than one active credential for identity " + identityId
                                    + "; the partial unique index is not doing its job",
                            null);
                }
                return Optional.of(credential);
            }
        } catch (SQLException e) {
            throw new CredentialStorageException(
                    "Could not read the active credential for identity " + identityId, e);
        }
    }

    private static Credential read(ResultSet rows) throws SQLException {
        Timestamp supersededAt = rows.getTimestamp("superseded_at");
        return Credential.rehydrate(
                CredentialId.of((java.util.UUID) rows.getObject("id")),
                IdentityId.of((java.util.UUID) rows.getObject("identity_id")),
                CredentialType.valueOf(rows.getString("type")),
                CredentialAlgorithm.valueOf(rows.getString("algorithm")),
                new DerivationParameters(
                        rows.getInt("memory_kib"),
                        rows.getInt("iterations"),
                        rows.getInt("parallelism")),
                Sensitive.of(rows.getString("derivation")),
                CredentialStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                supersededAt == null ? null : supersededAt.toInstant());
    }
}
