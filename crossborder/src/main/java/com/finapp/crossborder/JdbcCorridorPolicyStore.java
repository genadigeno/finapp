package com.finapp.crossborder;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC corridor policy storage (`P9-TSK-015`, ADR-0033). The machine, the four-eyes rule, the
 * partial uniques and the frozen content are {@code crossborder V002}'s, for every writer; this class
 * maps a unique violation on insert to {@link CorridorPolicyAdministration.ProposalPending}.
 */
@RequiredArgsConstructor
public final class JdbcCorridorPolicyStore implements CorridorPolicyStore {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String VERSION_COLUMNS = "id, version, status, proposed_by, proposed_at, decided_by";

    @NonNull private final IdGenerator ids;

    @Override
    public int maxVersion(Connection unitOfWork) {
        try (PreparedStatement select =
                        unitOfWork.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM crossborder.corridor_policy_version");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading the latest corridor policy version", failure));
        }
    }

    @Override
    public void insertProposal(
            Connection unitOfWork,
            CorridorPolicyId id,
            int version,
            CorridorPolicyProposal proposal,
            String proposedBy,
            Instant at) {
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO crossborder.corridor_policy_version (id, version, status, proposed_by,"
                                    + " proposed_at, proposal_reason) VALUES (?, ?, 'PROPOSED', ?, ?, ?)")) {
                insert.setObject(1, id.value());
                insert.setInt(2, version);
                insert.setString(3, proposedBy);
                insert.setTimestamp(4, Timestamp.from(at));
                insert.setString(5, proposal.reason());
                insert.executeUpdate();
            }
            try (PreparedStatement corridor =
                    unitOfWork.prepareStatement(
                            "INSERT INTO crossborder.corridor (policy_id, source_currency, destination_currency,"
                                    + " destination_country, rails, fee_fixed_minor, fee_margin, fee_rounding,"
                                    + " maximum_minor, screening_validity_hours, delivery_estimate_hours, required_data)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (CorridorTerms terms : proposal.corridors()) {
                    corridor.setObject(1, id.value());
                    corridor.setString(2, terms.key().source().code());
                    corridor.setString(3, terms.key().destination().code());
                    corridor.setString(4, terms.key().country().code());
                    corridor.setArray(5, unitOfWork.createArrayOf("text", terms.rails().toArray()));
                    corridor.setLong(6, terms.feeFixed().minorUnits());
                    corridor.setBigDecimal(7, terms.feeMargin());
                    corridor.setString(8, terms.feeRounding().name());
                    corridor.setLong(9, terms.maximum().minorUnits());
                    corridor.setLong(10, terms.screeningValidity().toHours());
                    corridor.setLong(11, terms.deliveryEstimate().toHours());
                    corridor.setArray(12, unitOfWork.createArrayOf("text",
                            terms.requiredData().stream().map(Enum::name).sorted().toArray()));
                    corridor.addBatch();
                }
                corridor.executeBatch();
            }
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())
                    || (failure.getNextException() != null
                            && UNIQUE_VIOLATION.equals(failure.getNextException().getSQLState()))) {
                throw new CorridorPolicyAdministration.ProposalPending(failure);
            }
            throw new CrossborderStorageException(DatabaseFailure.describe("proposing a corridor policy", failure));
        }
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, CorridorPolicyId id) {
        return one(unitOfWork,
                "SELECT " + VERSION_COLUMNS + " FROM crossborder.corridor_policy_version WHERE id = ? FOR UPDATE", id.value());
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork) {
        return one(unitOfWork,
                "SELECT " + VERSION_COLUMNS + " FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE' FOR UPDATE",
                null);
    }

    @Override
    public boolean decide(
            Connection unitOfWork,
            CorridorPolicyId id,
            CorridorPolicyStatus from,
            CorridorPolicyStatus to,
            String decidedBy,
            String reason,
            Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE crossborder.corridor_policy_version SET status = ?, decided_by = ?, decided_at = ?,"
                                + " decision_reason = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setTimestamp(3, Timestamp.from(at));
            update.setString(4, reason);
            update.setObject(5, id.value());
            update.setString(6, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("deciding a corridor policy", failure));
        }
    }

    @Override
    public boolean retire(Connection unitOfWork, CorridorPolicyId id, Instant at) {
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE crossborder.corridor_policy_version SET status = 'RETIRED', retired_at = ?"
                                + " WHERE id = ? AND status = 'ACTIVE'")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("retiring a corridor policy", failure));
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            CorridorPolicyId id,
            Optional<CorridorPolicyStatus> from,
            CorridorPolicyStatus to,
            String actorId,
            String reason,
            Instant at) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO crossborder.corridor_policy_event (id, policy_id, from_status, to_status,"
                                + " actor_id, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, id.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, actorId);
            insert.setString(6, reason);
            insert.setTimestamp(7, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a corridor policy event", failure));
        }
    }

    @Override
    public List<VersionView> versions(Connection unitOfWork, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("a version list is 1..100 long");
        }
        List<VersionView> views = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM crossborder.corridor_policy_version ORDER BY version DESC LIMIT ?")) {
            select.setInt(1, limit);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    views.add(new VersionView(rehydrate(row), row.getTimestamp("proposed_at").toInstant(), List.of()));
                }
            }
            List<VersionView> withCorridors = new ArrayList<>();
            for (VersionView view : views) {
                withCorridors.add(new VersionView(view.row(), view.proposedAt(), corridorsOf(unitOfWork, view.row().id())));
            }
            return withCorridors;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("listing corridor policies", failure));
        }
    }

    @Override
    public Optional<VersionView> active(Connection unitOfWork) {
        return view(unitOfWork,
                "SELECT " + VERSION_COLUMNS + " FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'", null);
    }

    @Override
    public Optional<VersionView> version(Connection unitOfWork, CorridorPolicyId id) {
        Objects.requireNonNull(id, "id must not be null");
        return view(unitOfWork,
                "SELECT " + VERSION_COLUMNS + " FROM crossborder.corridor_policy_version WHERE id = ?", id.value());
    }

    private Optional<VersionView> view(Connection unitOfWork, String sql, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            if (id != null) {
                select.setObject(1, id);
            }
            VersionRow version;
            Instant proposedAt;
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                version = rehydrate(row);
                proposedAt = row.getTimestamp("proposed_at").toInstant();
            }
            return Optional.of(new VersionView(version, proposedAt, corridorsOf(unitOfWork, version.id())));
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading a corridor policy", failure));
        }
    }

    private List<CorridorTerms> corridorsOf(Connection unitOfWork, CorridorPolicyId id) throws SQLException {
        List<CorridorTerms> corridors = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT * FROM crossborder.corridor WHERE policy_id = ?"
                                + " ORDER BY source_currency, destination_currency, destination_country")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                    CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                    String[] rails = (String[]) row.getArray("rails").getArray();
                    String[] required = (String[]) row.getArray("required_data").getArray();
                    Set<RequiredData> requiredData =
                            Arrays.stream(required).map(RequiredData::valueOf).collect(Collectors.toUnmodifiableSet());
                    corridors.add(
                            new CorridorTerms(
                                    new CorridorKey(source, destination, CountryCode.of(row.getString("destination_country"))),
                                    List.of(rails),
                                    Money.ofMinorUnits(row.getLong("fee_fixed_minor"), source),
                                    row.getBigDecimal("fee_margin"),
                                    RoundingPolicy.valueOf(row.getString("fee_rounding")),
                                    Money.ofMinorUnits(row.getLong("maximum_minor"), destination),
                                    Duration.ofHours(row.getLong("screening_validity_hours")),
                                    Duration.ofHours(row.getLong("delivery_estimate_hours")),
                                    requiredData));
                }
            }
        }
        return corridors;
    }

    private Optional<VersionRow> one(Connection unitOfWork, String sql, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            if (id != null) {
                select.setObject(1, id);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rehydrate(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("locking a corridor policy", failure));
        }
    }

    private static VersionRow rehydrate(ResultSet row) throws SQLException {
        return new VersionRow(
                CorridorPolicyId.of(row.getObject("id", UUID.class)),
                row.getInt("version"),
                CorridorPolicyStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                Optional.ofNullable(row.getString("decided_by")));
    }
}
