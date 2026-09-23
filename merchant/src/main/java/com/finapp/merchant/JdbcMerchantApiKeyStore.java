package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MerchantApiKeyStore} over JDBC (`P6-TSK-002`, ADR-0033: explicit SQL, no ORM).
 *
 * <p><strong>Every read here is authoritative and unconditional about it.</strong> Nothing in
 * this class caches, memoises or holds a key between requests — the one optimisation
 * available at this door is also the one that would make revocation a promise the
 * architecture cannot keep, and {@code NoProcessLocalSessionStateTest}'s reasoning applies to
 * a merchant credential exactly as it does to a session.
 */
public final class JdbcMerchantApiKeyStore implements MerchantApiKeyStore<Connection> {

    private static final String COLUMNS =
            "k.id, k.merchant_id, k.secret_hash, k.algorithm, k.status, k.issued_at,"
                    + " k.issued_by, k.revoked_at";

    @Override
    public void insert(Connection unitOfWork, MerchantApiKey key) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant_api_key"
                                + " (id, merchant_id, secret_hash, algorithm, status, issued_at,"
                                + " issued_by)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, key.id().value());
            insert.setObject(2, key.merchantId().value());
            // The hash, never the secret - there is no column for one.
            insert.setString(3, hashOf(key));
            insert.setString(4, key.algorithm());
            insert.setString(5, key.status().name());
            insert.setTimestamp(6, Timestamp.from(key.issuedAt()));
            insert.setString(7, key.issuedBy());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("issuing a merchant api key", failure));
        }
    }

    @Override
    public Optional<MerchantApiKey> findLiveFor(Connection unitOfWork, MerchantApiKeyId id) {
        // THE MERCHANT'S STANDING IS IN THE JOIN, not in a follow-up read: one question of
        // authoritative state answers "is this key live AND is its merchant trading?", so a
        // suspension takes effect on every instance's very next request with nothing to
        // invalidate anywhere (the port's contract says why).
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM merchant.merchant_api_key k"
                                + " JOIN merchant.merchant m ON m.id = k.merchant_id"
                                + " WHERE k.id = ? AND k.status = 'ACTIVE'"
                                + "   AND m.status = 'ACTIVE'")) {
            select.setObject(1, id.value());
            return single(select);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("authenticating a merchant api key", failure));
        }
    }

    @Override
    public List<MerchantApiKey> listFor(Connection unitOfWork, MerchantId tenant) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM merchant.merchant_api_key k"
                                + " WHERE k.merchant_id = ?"
                                + " ORDER BY k.issued_at DESC")) {
            select.setObject(1, tenant.value());
            try (ResultSet rows = select.executeQuery()) {
                List<MerchantApiKey> keys = new ArrayList<>();
                while (rows.next()) {
                    keys.add(read(rows));
                }
                return List.copyOf(keys);
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("listing a merchant's api keys", failure));
        }
    }

    @Override
    public Optional<MerchantApiKey> findOwnedForUpdate(
            Connection unitOfWork, MerchantId tenant, MerchantApiKeyId id) {
        // merchant_id = ? IN THE STATEMENT (INV-MER-01): naming another merchant's key and
        // naming one that does not exist are one empty answer, arbitrated by the database
        // rather than by a filter the caller could forget to apply.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS
                                + " FROM merchant.merchant_api_key k"
                                + " WHERE k.id = ? AND k.merchant_id = ?"
                                + " FOR UPDATE")) {
            select.setObject(1, id.value());
            select.setObject(2, tenant.value());
            return single(select);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a merchant api key", failure));
        }
    }

    @Override
    public boolean revoke(
            Connection unitOfWork, MerchantApiKey before, MerchantApiKey revoked, String reason) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE merchant.merchant_api_key"
                                + " SET status = ?, revoked_at = ?"
                                + " WHERE id = ? AND status = ?")) {
            update.setString(1, revoked.status().name());
            update.setTimestamp(
                    2, Timestamp.from(revoked.revokedAt().orElseThrow()));
            update.setObject(3, revoked.id().value());
            update.setString(4, before.status().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("revoking a merchant api key", failure));
        }
        appendHistory(unitOfWork, revoked, before.status(), reason);
        return true;
    }

    private void appendHistory(
            Connection unitOfWork, MerchantApiKey revoked, MerchantApiKeyStatus from, String reason) {
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.merchant_api_key_event"
                                + " (key_id, from_status, to_status, reason, actor_id,"
                                + " actor_type, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, revoked.id().value());
            insert.setString(2, from.name());
            insert.setString(3, revoked.status().name());
            insert.setString(4, reason);
            insert.setString(5, actor.id());
            insert.setString(6, actor.type().name());
            insert.setTimestamp(7, Timestamp.from(revoked.revokedAt().orElseThrow()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("recording an api key revocation", failure));
        }
    }

    private static Optional<MerchantApiKey> single(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(read(row)) : Optional.empty();
        }
    }

    private static MerchantApiKey read(ResultSet row) throws SQLException {
        Timestamp revoked = row.getTimestamp(8);
        return MerchantApiKey.rehydrate(
                MerchantApiKeyId.of(row.getObject(1, UUID.class)),
                MerchantId.of(row.getObject(2, UUID.class)),
                com.finapp.sharedkernel.security.Sensitive.of(row.getString(3)),
                row.getString(4),
                MerchantApiKeyStatus.valueOf(row.getString(5)),
                row.getTimestamp(6).toInstant(),
                row.getString(7),
                revoked == null ? null : revoked.toInstant());
    }

    /**
     * The stored hash, unwrapped at the ONE write that persists it — the single
     * {@code expose()} on this path, the {@code SessionToken.hash()} discipline.
     */
    private static String hashOf(MerchantApiKey key) {
        return key.storedHash().expose();
    }
}
