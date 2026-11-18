package com.finapp.identity;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IdentityDirectoryStore} in explicit SQL (`P17-TSK-014`). Lock-free reads of committed rows, every instant the row's own.
 *
 * <p>The directory is driven from {@code identity.role_assignment}: the identities that ever held a role are found there first,
 * then joined to {@code identity.identity} by its key. So a page costs what the staff number, however many customers the
 * identity table holds, and no new index serves it.
 */
public final class JdbcIdentityDirectoryStore implements IdentityDirectoryStore<Connection> {

    @Override
    public List<Entry> page(
            Connection unitOfWork, Optional<RoleName> role, Optional<IdentityStatus> status, Optional<Cursor> after, int limit) {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one identity, not " + limit);
        }
        String sql = "SELECT i.id, i.status, i.created_at,"
                + " array_remove(array_agg(r.role_name ORDER BY r.role_name) FILTER (WHERE r.revoked_at IS NULL), NULL) AS live_roles"
                + " FROM identity.identity i"
                + " JOIN identity.role_assignment r ON r.identity_id = i.id"
                + " WHERE true"
                + (role.isPresent() ? " AND EXISTS (SELECT 1 FROM identity.role_assignment held WHERE held.identity_id = i.id"
                        + " AND held.role_name = ? AND held.revoked_at IS NULL)" : "")
                + (status.isPresent() ? " AND i.status = ?" : "")
                + (after.isPresent() ? " AND (i.created_at, i.id) < (?, ?)" : "")
                + " GROUP BY i.id, i.status, i.created_at"
                + " ORDER BY i.created_at DESC, i.id DESC LIMIT ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            int at = 1;
            if (role.isPresent()) {
                select.setString(at++, role.get().name());
            }
            if (status.isPresent()) {
                select.setString(at++, status.get().name());
            }
            if (after.isPresent()) {
                select.setTimestamp(at++, Timestamp.from(after.get().createdAt()));
                select.setObject(at++, after.get().identityId());
            }
            select.setInt(at, limit);
            try (ResultSet rows = select.executeQuery()) {
                List<Entry> page = new ArrayList<>();
                while (rows.next()) {
                    page.add(new Entry(
                            IdentityId.of(rows.getObject("id", UUID.class)),
                            IdentityStatus.valueOf(rows.getString("status")),
                            rows.getTimestamp("created_at").toInstant(),
                            roles(rows.getArray("live_roles"))));
                }
                return page;
            }
        } catch (SQLException e) {
            throw new IdentityStorageException(DatabaseFailure.describe("Could not read the staff directory", e));
        }
    }

    @Override
    public Optional<Detail> detail(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(identityId, "identityId must not be null");
        try {
            Optional<Detail> found = Optional.empty();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT i.status, i.status_changed_at, i.suspension_cause, i.created_at,"
                            + " EXISTS (SELECT 1 FROM identity.mfa_enrolment factor WHERE factor.identity_id = i.id"
                            + " AND factor.status = 'ACTIVE') AS factor_enrolled"
                            + " FROM identity.identity i WHERE i.id = ?")) {
                select.setObject(1, identityId.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    IdentityStatus status = IdentityStatus.valueOf(row.getString("status"));
                    boolean suspended = status == IdentityStatus.SUSPENDED;
                    found = Optional.of(new Detail(
                            identityId,
                            status,
                            suspended ? Optional.of(row.getTimestamp("status_changed_at").toInstant()) : Optional.empty(),
                            suspended ? Optional.ofNullable(row.getString("suspension_cause")) : Optional.empty(),
                            row.getBoolean("factor_enrolled"),
                            List.of(),
                            row.getTimestamp("created_at").toInstant()));
                }
            }
            List<LiveRole> roles = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT role_name, assigned_at, assigned_by FROM identity.role_assignment"
                            + " WHERE identity_id = ? AND revoked_at IS NULL ORDER BY assigned_at, role_name")) {
                select.setObject(1, identityId.value());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        roles.add(new LiveRole(
                                RoleName.valueOf(rows.getString("role_name")),
                                rows.getTimestamp("assigned_at").toInstant(),
                                IdentityId.of(rows.getObject("assigned_by", UUID.class))));
                    }
                }
            }
            Detail read = found.orElseThrow();
            return Optional.of(new Detail(read.identityId(), read.status(), read.suspendedAt(), read.suspensionCause(),
                    read.factorEnrolled(), roles, read.createdAt()));
        } catch (SQLException e) {
            throw new IdentityStorageException(DatabaseFailure.describe("Could not read an identity's detail", e));
        }
    }

    @Override
    public Optional<IdentityId> byLoginIdentifier(Connection unitOfWork, String loginIdentifier) {
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            select.setString(1, loginIdentifier);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(IdentityId.of(row.getObject("id", UUID.class))) : Optional.empty();
            }
        } catch (SQLException e) {
            // Never the identifier itself in the message: it may be a person's own handle.
            throw new IdentityStorageException(DatabaseFailure.describe("Could not look an identity up", e));
        }
    }

    private static List<RoleName> roles(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((String[]) array.getArray()).map(RoleName::valueOf).toList();
    }
}
