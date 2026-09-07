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
import java.util.UUID;

/** Plain-JDBC recovery request store (`P1-TSK-023`, ADR-0033). */
public final class JdbcRecoveryRequestStore implements RecoveryRequestStore<Connection> {

    /**
     * Mints the request identifier.
     *
     * <p>The {@code JdbcRoleAssignmentStore} precedent, and it removes a question rather than adding
     * a dependency: a caller passing a fresh identifier makes {@code initiate} look like it targets
     * an existing resource, which is the shape {@code OwnershipIsScopedTest} exists to interrogate.
     * A creation has no owner to check, and the signature now says so.
     */
    private final com.finapp.sharedkernel.id.IdGenerator ids;

    public JdbcRecoveryRequestStore(com.finapp.sharedkernel.id.IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    private static final String TABLE = "identity.recovery_request";

    private static final String COLUMNS =
            "id, identity_id, channel_id, status, credential_id, initiated_at, expires_at";

    /**
     * The identity's active credential, as a scalar subquery.
     *
     * <p>Written once and used twice — at initiation to bind the request, and at completion to check
     * the binding still holds. Two copies would be two chances to write a subtly different
     * predicate, and the whole control is that the two agree.
     *
     * <p><strong>A method rather than a constant, and the build rule decided that.</strong>
     * {@code secretsAreWrapped} flagged the field this used to be: its name contains
     * {@code credential}, and the rule cannot tell a SQL fragment from a stored secret. Renaming to
     * dodge the vocabulary was available and was refused — {@code P1-TSK-017} rejected exactly that
     * for {@code sharedSecret}, and it is no more honest for being easier. What the rule is actually
     * about is <em>a value that could print itself</em>, and a fragment computed from an argument is
     * not a stored value at all. The {@code P1-TSK-007} answer: change the code, and the call sites
     * read better for it.
     */
    private static String activeCredentialOf(String identityColumn) {
        return "(SELECT c.id FROM identity.credential c"
                + " WHERE c.identity_id = " + identityColumn + " AND c.status = 'ACTIVE' LIMIT 1)";
    }

    @Override
    public int cancelLiveFor(Connection unitOfWork, LoginIdentifier login, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(login, "login must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // The identity is resolved by SUBSELECT rather than by a prior lookup, so this costs one
        // statement whether the identifier names anybody or not (AuthenticationThrottle's reason).
        String sql =
                "UPDATE " + TABLE + " SET status = 'CANCELLED', cancelled_at = ?"
                        + " WHERE status = 'INITIATED'"
                        + " AND identity_id ="
                        + " (SELECT i.id FROM identity.identity i WHERE i.login_identifier = ?)";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setString(2, login.value());
            return update.executeUpdate();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not cancel a recovery request", e));
        }
    }

    @Override
    public Optional<RecoveryRequest> initiate(
            Connection unitOfWork,
            LoginIdentifier login,
            ContactChannelKind kind,
            SingleUseToken token,
            Instant at,
            Instant expiresAt,
            Instant notBefore) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(login, "login must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(notBefore, "notBefore must not be null");

        // ONE STATEMENT, and that is the enumeration-safety property rather than a tidiness one.
        //
        //   JOIN contact_channel ... verified_at IS NOT NULL   INV-IDN-06's requirement, as a join
        //   i.status = 'ACTIVE'                                a suspended identity does not recover
        //   NOT EXISTS (... initiated_at > ?)                  cooling-off, over EVERY status so a
        //                                                      just-cancelled request still counts
        //   ON CONFLICT ... DO NOTHING                         ten concurrent initiations produce one
        //
        // The alternative - look the identity up, then check the channel, then check cooling-off -
        // runs a different number of queries for an account that exists, and that is a timing
        // channel disclosing existence (P1-TSK-008's finding, in a different flow).
        String sql =
                "INSERT INTO " + TABLE
                        + " (id, identity_id, channel_id, token_hash, status, credential_id,"
                        + " initiated_at, expires_at)"
                        + " SELECT ?, i.id, c.id, ?, 'INITIATED', "
                        + activeCredentialOf("i.id")
                        + ", ?, ?"
                        + " FROM identity.identity i"
                        + " JOIN identity.contact_channel c"
                        + "   ON c.identity_id = i.id AND c.kind = ? AND c.verified_at IS NOT NULL"
                        + " WHERE i.login_identifier = ?"
                        + "   AND i.status = 'ACTIVE'"
                        + "   AND NOT EXISTS ("
                        + "        SELECT 1 FROM " + TABLE + " r"
                        + "         WHERE r.identity_id = i.id AND r.initiated_at > ?)"
                        + " ON CONFLICT (identity_id) WHERE status = 'INITIATED' DO NOTHING"
                        + " RETURNING " + COLUMNS;
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, ids.next());
            insert.setString(2, token.hash().expose());
            insert.setTimestamp(3, Timestamp.from(at));
            insert.setTimestamp(4, Timestamp.from(expiresAt));
            insert.setString(5, kind.name());
            insert.setString(6, login.value());
            insert.setTimestamp(7, Timestamp.from(notBefore));
            try (ResultSet rows = insert.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not initiate a recovery request", e));
        }
    }

    @Override
    public Optional<RecoveryRequest> consume(
            Connection unitOfWork, RecoveryRequestId id, SingleUseToken presented, Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(presented, "presented must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // Every clause is load-bearing:
        //   id = ? AND token_hash = ?     both, so a token cannot be spent against another request
        //   status = 'INITIATED'          single use; a replay finds nothing to consume
        //   expires_at > ?                expiry is DERIVED, so it is checked here rather than read
        //                                 from a status column a sweep would have to maintain
        //   credential_id IS NOT DISTINCT FROM (active credential)
        //                                 THE CONCURRENT-RECOVERY-AND-LOGIN CONTROL. An attacker
        //                                 initiates, the customer changes their password, and the
        //                                 attacker's token is now dead. IS NOT DISTINCT FROM rather
        //                                 than =, so "there was no credential and there still is
        //                                 none" matches while "there was none and now there is one"
        //                                 does not - which is the same attack against an identity
        //                                 that had not set a credential yet.
        String sql =
                "UPDATE " + TABLE + " SET status = 'COMPLETED', completed_at = ?"
                        + " WHERE id = ? AND token_hash = ?"
                        + " AND status = 'INITIATED'"
                        + " AND expires_at > ?"
                        + " AND credential_id IS NOT DISTINCT FROM "
                        + activeCredentialOf(TABLE + ".identity_id")
                        + " RETURNING " + COLUMNS;
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, id.value());
            update.setString(3, presented.hash().expose());
            update.setTimestamp(4, Timestamp.from(at));
            try (ResultSet rows = update.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not complete a recovery request", e));
        }
    }

    private static RecoveryRequest read(ResultSet rows) throws SQLException {
        UUID credentialId = (UUID) rows.getObject("credential_id");
        return new RecoveryRequest(
                RecoveryRequestId.of((UUID) rows.getObject("id")),
                IdentityId.of((UUID) rows.getObject("identity_id")),
                ContactChannelId.of((UUID) rows.getObject("channel_id")),
                RecoveryStatus.valueOf(rows.getString("status")),
                Optional.ofNullable(credentialId).map(CredentialId::of),
                rows.getTimestamp("initiated_at").toInstant(),
                rows.getTimestamp("expires_at").toInstant());
    }
}
