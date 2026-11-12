package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Plain-JDBC role assignment store (`P1-TSK-020`, ADR-0033). */
@RequiredArgsConstructor
public final class JdbcRoleAssignmentStore implements RoleAssignmentStore<Connection> {

    private static final String TABLE = "identity.role_assignment";

    @NonNull private final IdGenerator ids;

    @Override
    public Set<RoleName> liveRolesOf(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        // `revoked_at IS NULL` is in the STATEMENT. A revoked role must stop granting on the next
        // request, and a filter applied afterwards is one a later caller can forget.
        String sql =
                "SELECT role_name FROM " + TABLE
                        + " WHERE identity_id = ? AND revoked_at IS NULL";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, identityId.value());
            try (ResultSet rows = select.executeQuery()) {
                Set<RoleName> roles = EnumSet.noneOf(RoleName.class);
                while (rows.next()) {
                    roles.add(RoleName.valueOf(rows.getString("role_name")));
                }
                return Set.copyOf(roles);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not read the roles of " + identityId, e));
        }
    }

    @Override
    public void lockForRoleChange(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        // The identity's own row, FOR NO KEY UPDATE (`P15-TSK-032`): every grant and revocation of this identity takes it first,
        // so they serialise and each reads the roles the other committed. NO KEY: a session insert's foreign key (KEY SHARE)
        // passes; JdbcSessionStore's bulk revocation still takes FOR UPDATE on the same row to hold issuance back.
        try (PreparedStatement lock = unitOfWork.prepareStatement(
                "SELECT 1 FROM identity.identity WHERE id = ? FOR NO KEY UPDATE")) {
            lock.setObject(1, identityId.value());
            lock.executeQuery().close();
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not lock identity " + identityId + " for a role change", e));
        }
    }

    @Override
    public Set<Holding> lockActiveHoldingsOf(Connection unitOfWork, Set<RoleName> roles) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(roles, "roles must not be null");

        // Every live holding of the roles by an ACTIVE identity, locked FOR UPDATE in the primary key's order (`P15-TSK-032`):
        // two revocations lock the same rows in the same order, so the second waits on the first and PostgreSQL re-checks each
        // row it waited on, dropping one the first revoked. The holder's status is read with the row, not locked.
        String sql =
                "SELECT held.identity_id, held.role_name FROM " + TABLE + " held"
                        + " JOIN identity.identity holder ON holder.id = held.identity_id"
                        + " WHERE held.revoked_at IS NULL AND held.role_name = ANY (?) AND holder.status = 'ACTIVE'"
                        + " ORDER BY held.id FOR UPDATE OF held";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setArray(1, unitOfWork.createArrayOf("text", roles.stream().map(RoleName::name).toArray()));
            try (ResultSet rows = select.executeQuery()) {
                Set<Holding> holdings = new java.util.HashSet<>();
                while (rows.next()) {
                    holdings.add(new Holding(
                            IdentityId.of(rows.getObject("identity_id", java.util.UUID.class)),
                            RoleName.valueOf(rows.getString("role_name"))));
                }
                return Set.copyOf(holdings);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(DatabaseFailure.describe("Could not lock the holdings of " + roles, e));
        }
    }

    @Override
    public java.util.Optional<IdentityId> liveHolderByLogin(Connection unitOfWork, LoginIdentifier login) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(login, "login must not be null");
        // ONE STATEMENT, whatever the identifier names (`P15-TSK-042`): an unknown identifier, a customer's and a
        // role-holder's all run this read and nothing else, so the public sign-in's cost does not say which it was.
        // `revoked_at IS NULL` in the statement, for liveRolesOf's reason.
        String sql =
                "SELECT signing_in.id FROM identity.identity signing_in"
                        + " WHERE signing_in.login_identifier = ?"
                        + " AND EXISTS (SELECT 1 FROM " + TABLE + " held"
                        + " WHERE held.identity_id = signing_in.id AND held.revoked_at IS NULL)";
        return holderBy(unitOfWork, sql, select -> select.setString(1, login.value()),
                "Could not read whether a login identifier holds a role");
    }

    @Override
    public java.util.Optional<IdentityId> liveHolderOfRecovery(Connection unitOfWork, RecoveryRequestId request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(request, "request must not be null");
        String sql =
                "SELECT recovering.identity_id FROM identity.recovery_request recovering"
                        + " WHERE recovering.id = ?"
                        + " AND EXISTS (SELECT 1 FROM " + TABLE + " held"
                        + " WHERE held.identity_id = recovering.identity_id AND held.revoked_at IS NULL)";
        return holderBy(unitOfWork, sql, select -> select.setObject(1, request.value()),
                "Could not read whether a recovery request's identity holds a role");
    }

    private interface Binding {
        void bind(PreparedStatement select) throws SQLException;
    }

    private static java.util.Optional<IdentityId> holderBy(
            Connection unitOfWork, String sql, Binding binding, String failure) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            binding.bind(select);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? java.util.Optional.of(IdentityId.of(rows.getObject(1, java.util.UUID.class)))
                        : java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(DatabaseFailure.describe(failure, e));
        }
    }

    @Override
    public java.util.List<FactorlessHolding> holdingsWithoutFactor(Connection unitOfWork, Set<RoleName> roles, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        String sql =
                "SELECT holding.identity_id, holding.role_name, holding.assigned_at FROM " + TABLE + " holding"
                        + " WHERE holding.revoked_at IS NULL AND holding.role_name = ANY (?)"
                        + " AND NOT EXISTS (SELECT 1 FROM identity.mfa_enrolment factor"
                        + " WHERE factor.identity_id = holding.identity_id AND factor.status = 'ACTIVE')"
                        + " ORDER BY holding.assigned_at, holding.identity_id, holding.role_name LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setArray(1, unitOfWork.createArrayOf("text", roles.stream().map(RoleName::name).toArray()));
            select.setInt(2, limit);
            try (ResultSet rows = select.executeQuery()) {
                java.util.List<FactorlessHolding> found = new java.util.ArrayList<>();
                while (rows.next()) {
                    found.add(new FactorlessHolding(
                            IdentityId.of(rows.getObject("identity_id", java.util.UUID.class)),
                            RoleName.valueOf(rows.getString("role_name")),
                            rows.getTimestamp("assigned_at").toInstant()));
                }
                return java.util.List.copyOf(found);
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(DatabaseFailure.describe("Could not read the factorless holdings", e));
        }
    }

    @Override
    public boolean assign(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId assignedBy,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(assignedBy, "assignedBy must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // ON CONFLICT DO NOTHING against the partial unique index, so the row count is the outcome:
        // ten instances granting the same role produce one assignment and nine are told they lost.
        // No read-then-write, so there is nothing to lose.
        String sql =
                "INSERT INTO " + TABLE
                        + " (id, identity_id, role_name, assigned_by, assigned_at)"
                        + " VALUES (?, ?, ?, ?, ?)"
                        + " ON CONFLICT (identity_id, role_name) WHERE revoked_at IS NULL"
                        + " DO NOTHING";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, ids.next());
            insert.setObject(2, identityId.value());
            insert.setString(3, role.name());
            insert.setObject(4, assignedBy.value());
            insert.setTimestamp(5, Timestamp.from(at));
            return insert.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not assign " + role + " to " + identityId, e));
        }
    }

    @Override
    public boolean revoke(
            Connection unitOfWork,
            IdentityId identityId,
            RoleName role,
            IdentityId revokedBy,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(revokedBy, "revokedBy must not be null");
        Objects.requireNonNull(at, "at must not be null");

        // Conditional on still being live; the row count is the outcome. Marked, never deleted -
        // the application role holds no DELETE here.
        String sql =
                "UPDATE " + TABLE + " SET"
                        // GREATEST(?, assigned_at) - the P1-TSK-031 drift, clamped in the
                        // statement: the revoking instance's clock may read behind the one that
                        // granted the role (ADR-0014: skew is bounded, never zero), and a legal
                        // revocation must not die on V010's CHECK.
                        + " revoked_at = GREATEST(?, assigned_at), revoked_by = ?"
                        + " WHERE identity_id = ? AND role_name = ? AND revoked_at IS NULL";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, revokedBy.value());
            update.setObject(3, identityId.value());
            update.setString(4, role.name());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not revoke " + role + " from " + identityId, e));
        }
    }
}
