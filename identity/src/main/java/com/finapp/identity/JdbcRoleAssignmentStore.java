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
