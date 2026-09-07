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

/** Plain-JDBC role assignment store (`P1-TSK-020`, ADR-0033). */
public final class JdbcRoleAssignmentStore implements RoleAssignmentStore<Connection> {

    private static final String TABLE = "identity.role_assignment";

    private final IdGenerator ids;

    public JdbcRoleAssignmentStore(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

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
                "UPDATE " + TABLE + " SET revoked_at = ?, revoked_by = ?"
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
